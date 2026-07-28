/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.ForumStore] — the
 * per-project discussion rooms, part of the LNL-117 collaboration fan-out.
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * One document per forum in `forums/{id}`, where `{id}` is the global `Long` id the
 * rest of the system addresses forums by, allocated from `_counters/forums` (see
 * [FirestoreCounters]). Each document denormalises its owning `projectId`, so
 * "this project's forums" is one equality query — the same shape
 * [FirestoreIssueStore.forProject] uses for a project's issues — with no join and no
 * composite index. The row's `position` (0 first, the same convention SQLite's
 * `Forums.sq` uses) is a field on the document, and [forProject] sorts on it in
 * memory so the read needs no `orderBy` and therefore no index.
 *
 * ── Ordering and append ─────────────────────────────────────────────────────
 *
 * [insert] appends to the end of the project's list. The next position is read with
 * a plain equality query *before* the transaction and written inside it alongside
 * the id allocation, mirroring the SQLite store's `nextPosition` (MAX(position)+1):
 * the counter is monotonic, so a burned id is fine, and — as the SQLite store's own
 * preamble concedes for its append — two simultaneous appends racing to the same
 * position is a concurrency edge the single-threaded contract does not exercise and
 * the web app never hits (forum creation is an administrator action). [setOrder]
 * rewrites every position in one [com.google.cloud.firestore.WriteBatch], so no
 * reader sees two forums claiming the same slot, exactly as
 * [FirestoreIssueStore.setGroupOrder] does for board ranks.
 *
 * No composite index is required by this store.
 *
 * @see FirestoreProvider
 * @see FirestoreCounters
 * @see se.soderbjorn.lunicle.store.ForumStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore

class FirestoreForumStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.ForumStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    /** This project's forums, in stored order (0 first). One equality query, sorted in memory. */
    override suspend fun forProject(projectId: Long): List<ForumRecord> =
        collection()
            .whereEqualTo(PROJECT_ID, projectId)
            .get().await()
            .documents.map { it.toRecord() }
            .sortedBy { it.position }

    override suspend fun findByIdInProject(id: Long, projectId: Long): ForumRecord? =
        findById(id)?.takeIf { it.projectId == projectId }

    override suspend fun findById(id: Long): ForumRecord? =
        doc(id).get().await().takeIf { it.exists() }?.toRecord()

    /**
     * Append a forum to the end of its project's list.
     *
     * The next position is read outside the transaction and the id allocation plus
     * the document write happen inside it, so the counter never advances past a
     * forum that was never written. See the class preamble on the append race.
     */
    override suspend fun insert(projectId: Long, name: String, description: String?): ForumRecord {
        val createdAt = now()
        val existing = collection().whereEqualTo(PROJECT_ID, projectId).get().await()
        val nextPosition = (existing.documents.mapNotNull { it.getLong(POSITION) }.maxOrNull() ?: -1L) + 1
        return firestore.runTransaction { txn ->
            val id = counters.next(txn, ID_COUNTER).getValue(ID_COUNTER)
            txn.set(
                doc(id),
                mapOf(
                    ID to id,
                    PROJECT_ID to projectId,
                    NAME to name,
                    DESCRIPTION to description,
                    POSITION to nextPosition,
                    CREATED_AT to createdAt,
                ),
            )
            ForumRecord(
                id = id,
                projectId = projectId,
                name = name,
                description = description,
                position = nextPosition,
                createdAt = createdAt,
            )
        }.await()
    }

    override suspend fun update(id: Long, name: String, description: String?) {
        doc(id).update(mapOf(NAME to name, DESCRIPTION to description)).await()
    }

    override suspend fun delete(id: Long) {
        doc(id).delete().await()
    }

    /**
     * Rewrite a whole project's forum order in one batch.
     *
     * A single [com.google.cloud.firestore.WriteBatch] applies every renumber
     * atomically — the document-model answer to the SQLite reorder transaction, and
     * for the same reason: no reader ever sees a half-applied order in which two
     * forums share a position. The caller has already checked [ids] names exactly
     * this project's forums.
     */
    override suspend fun setOrder(ids: List<Long>) {
        val batch = firestore.batch()
        ids.forEachIndexed { index, id -> batch.update(doc(id), POSITION, index.toLong()) }
        batch.commit().await()
    }

    private fun DocumentSnapshot.toRecord() = ForumRecord(
        id = getLong(ID)!!,
        projectId = getLong(PROJECT_ID)!!,
        name = getString(NAME).orEmpty(),
        description = getString(DESCRIPTION),
        position = getLong(POSITION) ?: 0L,
        createdAt = getLong(CREATED_AT)!!,
    )

    internal companion object {
        const val COLLECTION = "forums"
        const val ID_COUNTER = "forums"

        const val ID = "id"
        const val PROJECT_ID = "projectId"
        const val NAME = "name"
        const val DESCRIPTION = "description"
        const val POSITION = "position"
        const val CREATED_AT = "createdAt"
    }
}
