/**
 * The Firestore [se.soderbjorn.lunicle.store.ProjectProvisioning] — making,
 * renaming, deleting and reordering a whole project over the document stores.
 *
 * The Firestore analogue of [ProjectRepository]. It exists because project
 * *creation* is not a store insert: a usable project is its row **and** its five
 * seeded vocabularies, and that orchestration is exactly what a store cannot hold.
 * The SQLite reference does it in one six-table transaction; here the row is
 * inserted through [se.soderbjorn.lunicle.store.ProjectStore] and the five default
 * vocabularies are seeded through their stores' `insert`, reusing the very same
 * [DEFAULT_LABELS]/[DEFAULT_COMPONENTS]/[DEFAULT_STATUSES]/[DEFAULT_PRIORITIES]/
 * [DEFAULT_RESOLUTIONS] lists (and [CLOSING_STATUS]) the SQLite path seeds, so the
 * two backends start a project identically.
 *
 * ── Seeding is one transaction, exactly as SQLite's is (LNL-131) ─────────────
 *
 * SQLite seeds the six tables in a single transaction, so a crash mid-seed leaves
 * no project at all. Firestore *does* support cross-collection transactions (up to
 * 500 writes), and a default board is ~20 vocabulary documents plus the project
 * document — well under that. The one thing that stopped [create] being atomic was
 * that the vocabulary stores' `insert()` each self-allocate an id via their own
 * counter transaction, and Firestore forbids nested transactions. So [create]
 * allocates every id up front instead — the project id from `_counters/projects`
 * and a contiguous block from `_counters/vocabulary`, reusing
 * [FirestoreIssueEventStore]'s block-reservation pattern — and writes the project
 * document and every default vocabulary document in one `runTransaction`, all reads
 * (the two counters and the project-position scan) before any write. A crash now
 * leaves either a fully-seeded project or none, the SQLite guarantee. The rows it
 * writes are the identical shape [seedVocabularyRow] and the concrete stores use,
 * so the board reads them back unchanged. The `update`, `delete` and `reorder`
 * paths are the same store calls the SQLite repository makes.
 *
 * @see ProjectRepository the SQLite reference implementation.
 * @see se.soderbjorn.lunicle.store.ProjectProvisioning
 */
package se.soderbjorn.lunicle

import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.store.ProjectProvisioning

class FirestoreProjectRepository(
    private val firestore: com.google.cloud.firestore.Firestore,
    private val projects: FirestoreProjectStore,
    private val attachments: AttachmentRepository,
    private val attachmentStore: se.soderbjorn.lunicle.store.AttachmentStore,
    private val now: () -> Long = System::currentTimeMillis,
) : ProjectProvisioning {
    /** One row the seed will write: which vocabulary, its name, its position, its closing flag. */
    private data class SeedRow(
        val kind: VocabularyKind,
        val name: String,
        val position: Long,
        val requiresResolution: Boolean,
        val isDone: Boolean = false,
    )

    override suspend fun create(
        name: String,
        namePrefix: String,
        isPublic: Boolean,
        visibleToAllSignedIn: Boolean,
    ): ProjectRecord {
        val cleanName = name.trim()
        val cleanPrefix = namePrefix.trim().uppercase()
        validate(cleanName, cleanPrefix, existingId = null)

        // Every default vocabulary row, in the same order, positions and closing-status
        // flag the SQLite seed writes — flattened to one list so the whole board is a
        // single contiguous block off the vocabulary counter.
        val seed = buildList {
            DEFAULT_LABELS.forEachIndexed { i, n -> add(SeedRow(VocabularyKind.LABEL, n, i.toLong(), false)) }
            DEFAULT_COMPONENTS.forEachIndexed { i, n -> add(SeedRow(VocabularyKind.COMPONENT, n, i.toLong(), false)) }
            DEFAULT_STATUSES.forEachIndexed { i, n -> add(SeedRow(VocabularyKind.STATUS, n, i.toLong(), n == CLOSING_STATUS)) }
            DEFAULT_PRIORITIES.forEachIndexed { i, n -> add(SeedRow(VocabularyKind.PRIORITY, n, i.toLong(), false)) }
            DEFAULT_RESOLUTIONS.forEachIndexed { i, n ->
                add(SeedRow(VocabularyKind.RESOLUTION, n, i.toLong(), false, isDone = n == DONE_RESOLUTION))
            }
        }
        val createdAt = now()

        val projectsCollection = firestore.collection(FirestoreProjectStore.COLLECTION)
        val projectCounter = firestore.collection(FirestoreCounters.COLLECTION).document(FirestoreProjectStore.COUNTER)
        val vocabCounter = firestore.collection(FirestoreCounters.COLLECTION).document(FirestoreVocabularyStore.COUNTER)

        return firestore.runTransaction { txn ->
            // ── Reads first — Firestore requires every read before any write ──
            // The project's append position (max + 1), the SQLite nextPosition.
            val existingProjects = txn.get(projectsCollection).get().documents
            val position = (existingProjects.mapNotNull { it.getLong(FirestoreProjectStore.POSITION) }.maxOrNull() ?: -1L) + 1L
            // The project id, and a contiguous block of vocabulary ids for the seed.
            val projectBase = txn.get(projectCounter).get().getLong(FirestoreCounters.VALUE) ?: 0L
            val vocabBase = txn.get(vocabCounter).get().getLong(FirestoreCounters.VALUE) ?: 0L

            // ── Writes — the counters bumped, then the project and its whole board ──
            val projectId = projectBase + 1L
            txn.set(projectCounter, mapOf(FirestoreCounters.VALUE to projectId))
            txn.set(vocabCounter, mapOf(FirestoreCounters.VALUE to vocabBase + seed.size))
            val project = projects.writeInTransaction(
                txn, projectId, cleanName, cleanPrefix, isPublic, visibleToAllSignedIn, position, createdAt,
            )
            seed.forEachIndexed { index, row ->
                seedVocabularyRow(
                    txn, firestore, vocabBase + 1L + index, row.kind, projectId, row.name, row.position,
                    row.requiresResolution, row.isDone,
                )
            }
            project
        }.await()
    }

    override suspend fun update(
        id: Long,
        name: String,
        namePrefix: String,
        isPublic: Boolean,
        visibleToAllSignedIn: Boolean,
    ): ProjectRecord {
        val cleanName = name.trim()
        val cleanPrefix = namePrefix.trim().uppercase()
        validate(cleanName, cleanPrefix, existingId = id)
        projects.update(id, cleanName, cleanPrefix, isPublic, visibleToAllSignedIn)
        return projects.findById(id) ?: throw ProjectConflict("That project no longer exists.")
    }

    /**
     * Delete a project, its rows (by the store's own cascade), and every file
     * behind any of it — the keys collected before the rows go, exactly as
     * [ProjectRepository.delete] does one level up.
     */
    override suspend fun delete(id: Long) {
        val doomed = attachmentStore.keysForProject(id)
        projects.delete(id)
        doomed.forEach { attachments.fileFor(it).delete() }
    }

    override suspend fun reorder(ids: List<Long>) {
        val current = projects.selectAll().map { it.id }
        if (ids.size != current.size || ids.toSet() != current.toSet()) {
            throw ProjectConflict("That order does not name this instance's projects.")
        }
        projects.setOrder(ids)
    }

    /**
     * The Unicode-aware name/prefix uniqueness check, mirroring
     * [ProjectRepository]'s `validate`. Replicated rather than shared because the
     * SQLite one is private and this must not reach for a database; the rule is the
     * same and both fold case with Kotlin's Unicode-aware `lowercase()`.
     */
    private suspend fun validate(name: String, namePrefix: String, existingId: Long?) {
        if (name.isBlank()) throw ProjectConflict("A project needs a name.")
        if (namePrefix.isBlank()) throw ProjectConflict("A project needs an issue prefix, like LMX.")

        val all = projects.selectAll()
        val nameClash = all.firstOrNull { it.id != existingId && it.name.lowercase() == name.lowercase() }
        if (nameClash != null) {
            throw ProjectConflict("There is already a project called \"${nameClash.name}\".")
        }
        val prefixClash = all.firstOrNull {
            it.id != existingId && it.namePrefix.lowercase() == namePrefix.lowercase()
        }
        if (prefixClash != null) {
            throw ProjectConflict("\"${prefixClash.namePrefix}\" is already used by \"${prefixClash.name}\".")
        }
    }
}
