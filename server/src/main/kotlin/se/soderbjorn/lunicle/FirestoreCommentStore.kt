/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.CommentStore] — an
 * issue's comments, part of the LNL-127 issue-content fan-out.
 *
 * The same draft→publish shape [FirestoreIssueStore] set for an issue, in miniature:
 * a comment is born a draft ([insertDraft], `isDraft = true`, empty body) so an
 * inline image upload has an owner, and [publish] is the single write that makes it
 * visible.
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * One flat top-level collection, `comments/{id}`, with a denormalised `issueId` —
 * the flat-collection-with-parent-ref shape [FirestoreIssueStore] set for a
 * project's issues. That is what turns [forIssue] into a single equality query; the
 * draft filter and the oldest-first order are both applied in memory, so the query
 * needs no composite index. Ids are the global `Long`s the system addresses comments
 * by, drawn from `_counters/comments` (see [FirestoreCounters]). Every author column
 * stays as it is on the issue — `createdBy`/`createdByExternal`/`agentName` — so a
 * deleted account degrades identically here and on the issue the comment belongs to.
 *
 * A comment carries no `projectId`: it reaches its project through its issue, here as
 * in SQLite, and a second copy would be a second source of truth.
 *
 * No composite index is required.
 *
 * @see FirestoreProvider
 * @see FirestoreCounters
 * @see se.soderbjorn.lunicle.store.CommentStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.store.CommentStore

class FirestoreCommentStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : CommentStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    /**
     * Create the hidden draft row an inline upload can hang off, allocating its id in
     * the same transaction that writes it — a cancelled draft burns its number just
     * as SQLite's `AUTOINCREMENT` does.
     */
    override suspend fun insertDraft(
        issueId: Long,
        author: Author,
        createdAt: Long?,
        agentName: String?,
    ): Long {
        val timestamp = createdAt ?: now()
        return firestore.runTransaction { txn ->
            val id = counters.next(txn, COUNTER).getValue(COUNTER)
            txn.set(
                doc(id),
                mapOf(
                    ID to id,
                    ISSUE_ID to issueId,
                    BODY to "",
                    CREATED_AT to timestamp,
                    CREATED_BY to author.accountId,
                    CREATED_BY_EXTERNAL to author.externalName,
                    AGENT_NAME to agentName,
                    IS_DRAFT to true,
                ),
            )
            id
        }.await()
    }

    override suspend fun publish(id: Long, body: String) {
        doc(id).update(mapOf(BODY to body, IS_DRAFT to false)).await()
    }

    override suspend fun update(id: Long, body: String) {
        doc(id).update(mapOf(BODY to body)).await()
    }

    override suspend fun edit(
        id: Long,
        body: String,
        createdAt: Long,
        author: Author,
        agentName: String?,
    ) {
        doc(id).update(
            mapOf(
                BODY to body,
                CREATED_AT to createdAt,
                CREATED_BY to author.accountId,
                CREATED_BY_EXTERNAL to author.externalName,
                AGENT_NAME to agentName,
            ),
        ).await()
    }

    override suspend fun withPossibleMentions(): List<Pair<Long, String>> = bodiesContaining("@")

    override suspend fun withAttachmentLinks(): List<Pair<Long, String>> = bodiesContaining("/api/attachments/")

    override suspend fun delete(id: Long) {
        doc(id).delete().await()
    }

    override suspend fun findById(id: Long): CommentRecord? =
        doc(id).get().await().takeIf { it.exists() }?.toRecord()

    /** Published comments on an issue, oldest first — one equality query, drafts and order in memory. */
    override suspend fun forIssue(issueId: Long): List<CommentRecord> =
        collection()
            .whereEqualTo(ISSUE_ID, issueId)
            .get().await()
            .documents.map { it.toRecord() }
            .filter { !it.isDraft }
            .sortedWith(compareBy<CommentRecord> { it.createdAt }.thenBy { it.id })

    /**
     * Every comment whose body contains [needle], as id-to-body pairs — a
     * full-collection scan filtered in memory, because Firestore has no substring
     * predicate to push the SQLite `LIKE '%needle%'` down into. Drafts are included:
     * a body being typed can already mention somebody, and a draft must not be the one
     * place an old name survives. See [FirestoreIssueStore] for the same scan over
     * issue descriptions.
     */
    private suspend fun bodiesContaining(needle: String): List<Pair<Long, String>> =
        collection().get().await().documents
            .map { it.getLong(ID)!! to it.getString(BODY).orEmpty() }
            .filter { it.second.contains(needle) }

    private companion object {
        const val COLLECTION = "comments"
        const val COUNTER = "comments"

        const val ID = "id"
        const val ISSUE_ID = "issueId"
        const val BODY = "body"
        const val CREATED_AT = "createdAt"
        const val CREATED_BY = "createdBy"
        const val CREATED_BY_EXTERNAL = "createdByExternal"
        const val AGENT_NAME = "agentName"
        const val IS_DRAFT = "isDraft"
    }
}

private fun DocumentSnapshot.toRecord() = CommentRecord(
    id = getLong("id")!!,
    issueId = getLong("issueId")!!,
    body = getString("body").orEmpty(),
    createdAt = getLong("createdAt")!!,
    author = authorOf(getLong("createdBy"), getString("createdByExternal")),
    agentName = getString("agentName"),
    isDraft = getBoolean("isDraft") ?: false,
)
