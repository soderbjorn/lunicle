/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.VocabularyStore] —
 * the richest parity surface in the suite, and the one a document backend is most
 * likely to get subtly wrong: ordering, case-insensitive uniqueness, and the two
 * delete refusals.
 *
 * The SQLite reference is `VocabularyRepository`, which composes six low-level
 * table stores and the issue store. There is no six-store equivalent here: this
 * one class holds the same rules over one collection.
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * One document per row in `vocabulary/{id}`, `{id}` the `Long` id from
 * `_counters/vocabulary` (see [FirestoreCounters]). A `kind` field
 * ([VocabularyKind.name]) discriminates the six vocabularies that lived in six
 * SQLite tables, and a `projectId` field scopes each row — so one project's rows
 * of one kind are a single equality query on `projectId`, filtered to the kind and
 * ordered by `position` in memory. Filtering the kind in memory rather than adding
 * a second `whereEqualTo` keeps this to the automatic single-field index and off
 * LNL-122's composite-index list.
 *
 * A `completedAt` field rides on sprint rows (null while open) — the one column a
 * sprint has that no other vocabulary does. This store never reads it (a
 * [VocabularyRow] does not carry completion), but [FirestoreSprintStore] does, and
 * both address the sprint through this collection's constants, which is why the
 * document constants below are `internal` rather than private. `inverseName` and
 * `marksBlocked` ride on relation-kind rows the same way (LNL-215), and this store
 * *does* read those, because the settings editor is where a kind's opposite label and
 * its blocking flag are typed. See [FirestoreIssueRelationKindStore] for why relation
 * kinds share this collection rather than owning one.
 *
 * ── Uniqueness and the refusals, reproduced ─────────────────────────────────
 *
 *  - **Case-insensitive uniqueness** has no unique index here. It is enforced the
 *    way the SQLite *repository* enforced it above its `COLLATE NOCASE` index — a
 *    Unicode-aware `lowercase()` comparison against the project's existing rows,
 *    which folds more than the ASCII the index would. The check runs *before* the
 *    write transaction, not inside it, so a [VocabularyConflict] surfaces as itself
 *    rather than wrapped in a transaction failure.
 *  - **The delete refusals** — the last load-bearing row, and a row still in use —
 *    are the same two checks `VocabularyRepository.delete` makes, reading the same
 *    [VocabularyKind.isLoadBearing] / [VocabularyKind.restrictsOnUse] the SQLite
 *    side reads. Usage counts come from an injected [IssueStore], exactly as the
 *    repository takes one.
 *
 * @see FirestoreProvider
 * @see FirestoreCounters
 * @see FirestoreSprintStore
 * @see se.soderbjorn.lunicle.store.VocabularyStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.store.IssueRelationStore
import se.soderbjorn.lunicle.store.IssueStore
import se.soderbjorn.lunicle.store.VocabularyStore

class FirestoreVocabularyStore(
    private val firestore: Firestore,
    private val issues: IssueStore,
    /**
     * The links between issues (LNL-215) — for a relation kind's usage count, and for
     * the cascade its delete performs.
     *
     * The eighth kind is the only one whose usage is **not** a fact about `issues`:
     * nothing on an issue document points at a relation kind, so the count in the
     * delete confirmation has to come from the relations themselves. The SQLite
     * `VocabularyRepository` takes exactly the same second store for exactly the same
     * two reasons.
     */
    private val relations: IssueRelationStore,
) : VocabularyStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    override suspend fun rows(projectId: Long, kind: VocabularyKind): List<VocabularyRow> {
        val uses = usage(projectId, kind)
        return docsOfKind(projectId, kind).map { it.toRow(uses) }
    }

    override suspend fun find(projectId: Long, kind: VocabularyKind, id: Long): VocabularyRow? {
        val snap = doc(id).get().await()
        // Project-scoped on purpose: a row of the wrong project or the wrong kind is
        // as good as absent, so a route cannot rename another project's column.
        if (!snap.exists() || snap.getLong(PROJECT_ID) != projectId || snap.getString(KIND) != kind.name) {
            return null
        }
        return snap.toRow(usage(projectId, kind))
    }

    /**
     * Add a row at the end of the order.
     *
     * The name is validated *before* the write transaction so a [VocabularyConflict]
     * is thrown as itself. The position is `max + 1` (never `count`, which would
     * collide with a row left after a middle delete — see `VocabularyRepository.add`),
     * and the id is allocated inside the transaction that writes the row.
     *
     * A relation kind takes its two extras here rather than only on the rename that
     * follows, unlike a status's closing flag, for the reason
     * `VocabularyRepository.add` gives: the MCP tool creates a kind in one call, and a
     * kind whose opposite could only be named by a second write would be briefly and
     * visibly symmetric when it is not. Both still default to the safe state —
     * symmetric, and not blocking — so nothing is armed by accident.
     */
    override suspend fun add(
        projectId: Long,
        kind: VocabularyKind,
        name: String,
        inverseName: String?,
        marksBlocked: Boolean,
    ): VocabularyRow {
        val clean = name.trim()
        // Blank normalises to null rather than being stored, so "I cleared the field"
        // and "I ticked same-in-both-directions" cannot become two stored states that
        // render identically. Null IS symmetry; see IssueRelationKinds.sq.
        val cleanInverse = inverseName?.trim()?.takeIf { it.isNotBlank() }
        val existing = docsOfKind(projectId, kind)
        validateName(kind, clean, cleanInverse, existing, renamingId = null)

        val position = (existing.mapNotNull { it.getLong(POSITION) }.maxOrNull() ?: -1L) + 1L
        return firestore.runTransaction { txn ->
            val id = counters.next(txn, COUNTER).getValue(COUNTER)
            // A relation kind is written through [relationKindFields], the single home
            // of that document's shape, so a kind added here is byte-identical to one
            // added through FirestoreIssueRelationKindStore.insert or seeded with the
            // project. The other seven keep the uniform map below, which carries their
            // three per-kind flags whether or not the kind can mean anything by them.
            val fields = if (kind == VocabularyKind.RELATION_KIND) {
                relationKindFields(id, projectId, clean, position, cleanInverse, marksBlocked)
            } else {
                mapOf(
                    ID to id,
                    PROJECT_ID to projectId,
                    KIND to kind.name,
                    NAME to clean,
                    POSITION to position,
                    // A new column never demands a resolution; a new resolution is
                    // never born done; a sprint is never born completed. See
                    // VocabularyRepository.add.
                    REQUIRES_RESOLUTION to false,
                    IS_DONE to false,
                    COMPLETED_AT to null,
                )
            }
            txn.set(doc(id), fields)
            VocabularyRow(
                id = id,
                projectId = projectId,
                name = clean,
                position = position,
                requiresResolution = false,
                usageCount = 0,
                inverseName = cleanInverse.takeIf { kind == VocabularyKind.RELATION_KIND },
                marksBlocked = marksBlocked && kind == VocabularyKind.RELATION_KIND,
            )
        }.await()
    }

    /**
     * Rename a row, and set its per-kind extras: a status's closing flag, a
     * resolution's done flag ([isDone]), or a relation kind's opposite label and
     * blocking flag. Each is written only for the kind it belongs to; the other kinds
     * get a name-only update, mirroring VocabularyRepository.rename.
     *
     * A relation kind's three fields go in one `update`, for
     * [FirestoreStatusStore.update]'s reason turned up a notch: they are one decision,
     * and two writes could leave a kind renamed but still marking cards blocked, or
     * carrying an opposite label for a direction it no longer has.
     */
    override suspend fun rename(
        projectId: Long,
        kind: VocabularyKind,
        row: VocabularyRow,
        name: String,
        requiresResolution: Boolean,
        isDone: Boolean,
        inverseName: String?,
        marksBlocked: Boolean,
    ) {
        val clean = name.trim()
        // Blank is not a to-side label, it is the absence of one. See [add].
        val cleanInverse = inverseName?.trim()?.takeIf { it.isNotBlank() }
        validateName(kind, clean, cleanInverse, docsOfKind(projectId, kind), renamingId = row.id)
        val updates = when (kind) {
            VocabularyKind.STATUS -> mapOf(NAME to clean, REQUIRES_RESOLUTION to requiresResolution)
            VocabularyKind.RESOLUTION -> mapOf(NAME to clean, IS_DONE to isDone)
            VocabularyKind.RELATION_KIND ->
                mapOf(NAME to clean, INVERSE_NAME to cleanInverse, MARKS_BLOCKED to marksBlocked)
            else -> mapOf(NAME to clean)
        }
        doc(row.id).update(updates).await()
    }

    /**
     * Delete a row, or refuse — the two checks in `VocabularyRepository.delete`'s
     * order: the last load-bearing row first (true even of an empty project), then
     * a row still in use.
     *
     * And, like the repository, the drafts sitting in the row go with it rather
     * than blocking it (LNL-183). There is no foreign key on this backend to
     * refuse the delete, so what a leftover draft would produce here is not a
     * violation but an issue document pointing at a status that no longer exists —
     * which is worse, and invisible until someone opens it.
     */
    override suspend fun delete(projectId: Long, kind: VocabularyKind, row: VocabularyRow) {
        val siblings = docsOfKind(projectId, kind)
        if (siblings.size <= 1 && kind.isLoadBearing) {
            throw VocabularyRefusal(
                "A project needs at least one ${kind.noun}, and \"${row.name}\" is the only one " +
                    "left. Add another before deleting this one.",
            )
        }
        if (kind.restrictsOnUse && row.usageCount > 0) {
            throw VocabularyRefusal(inUseMessage(kind, row))
        }
        when (kind) {
            VocabularyKind.STATUS -> issues.deleteDraftsWithStatus(projectId, row.id)
            VocabularyKind.PRIORITY -> issues.deleteDraftsWithPriority(projectId, row.id)
            // A relation kind takes its links with it (LNL-215), rather than being
            // refused over them — `restrictsOnUse` is false for exactly that reason: a
            // relation row without its kind would be two issue ids and no statement
            // about them. On SQLite the composite foreign key cascades; here the sweep
            // IS the cascade, and it runs before the row goes so an interruption leaves
            // a kind whose links are gone rather than links naming a kind that is.
            VocabularyKind.RELATION_KIND -> relations.deleteForKind(row.id)
            // Nothing else can be holding a draft; see VocabularyRepository.clearDrafts.
            else -> Unit
        }
        doc(row.id).delete().await()
    }

    /**
     * Put a whole vocabulary in the order given, positions rewritten 0..n-1 in one
     * batch (which repairs the gap a deleted middle row leaves).
     *
     * [ids] must be exactly this kind's rows in this project — proved before a
     * single write, since a partial list would leave the omitted rows colliding
     * with the ones it named and there is no unique position to catch it.
     */
    override suspend fun reorder(projectId: Long, kind: VocabularyKind, ids: List<Long>) {
        val current = docsOfKind(projectId, kind).mapNotNull { it.getLong(ID) }
        if (ids.size != current.size || ids.toSet() != current.toSet()) {
            throw VocabularyRefusal("That order does not name this project's ${kind.plural}.")
        }
        val batch = firestore.batch()
        ids.forEachIndexed { index, id -> batch.update(doc(id), POSITION, index.toLong()) }
        batch.commit().await()
    }

    /** One project's rows of one kind, in render order — one equality query, sorted in memory. */
    private suspend fun docsOfKind(projectId: Long, kind: VocabularyKind): List<DocumentSnapshot> =
        collection().whereEqualTo(PROJECT_ID, projectId).get().await()
            .documents
            .filter { it.getString(KIND) == kind.name }
            .sortedBy { it.getLong(POSITION) ?: 0L }

    /** Usage counts for one kind, from the issue store — see VocabularyRepository.usage. */
    private suspend fun usage(projectId: Long, kind: VocabularyKind): Map<Long, Long> = when (kind) {
        VocabularyKind.LABEL -> issues.usageByLabel(projectId)
        VocabularyKind.COMPONENT -> issues.usageByComponent(projectId)
        VocabularyKind.STATUS -> issues.usageByStatus(projectId)
        VocabularyKind.PRIORITY -> issues.usageByPriority(projectId)
        VocabularyKind.RESOLUTION -> issues.usageByResolution(projectId)
        VocabularyKind.SPRINT -> issues.usageBySprint(projectId)
        VocabularyKind.VERSION -> issues.usageByVersion(projectId)
        // Counted off the relations rather than off `issues`, alone among the eight:
        // nothing on an issue document points at a relation kind (LNL-215). See the
        // `relations` constructor parameter.
        VocabularyKind.RELATION_KIND -> relations.usageByKind(projectId)
    }

    /**
     * Blank, or already another row's? A Unicode-aware `lowercase()` fold, the same
     * one the SQLite repository and the dialog use, so the three agree on what a
     * duplicate is beyond the ASCII a `COLLATE NOCASE` index would fold.
     *
     * ── A relation kind occupies BOTH of its labels (LNL-215) ─────────────
     *
     * Every other kind owns one word. A relation kind owns two — "Blocked by" and
     * "Blocks" — and both appear in the same picker, so uniqueness has to be over the
     * union of the two fields across every row rather than over the name alone.
     * Without it, "Blocks" could be one kind's opposite and another kind's name, and
     * the picker would offer the same word twice meaning two different things.
     *
     * This is a straight copy of `VocabularyRepository.validateName`, which is how it
     * has to be: there is no unique index on either backend for this rule to backstop,
     * so the two implementations *are* the rule and must refuse the same things. The
     * contract suite is what proves they do.
     */
    private fun validateName(
        kind: VocabularyKind,
        name: String,
        inverseName: String?,
        existing: List<DocumentSnapshot>,
        renamingId: Long?,
    ) {
        if (name.isBlank()) throw VocabularyConflict("A ${kind.noun} needs a name.")
        // A kind may not clash with ITSELF either: "Blocked by" / "Blocked by" is a
        // kind that says one thing twice, which is what the symmetric case (a null
        // inverse) is for and is not how to spell it.
        if (inverseName != null && inverseName.lowercase() == name.lowercase()) {
            throw VocabularyConflict(
                "\"$name\" cannot also be its own opposite. Tick \"same in both directions\" instead.",
            )
        }
        val taken = existing.filter { it.getLong(ID) != renamingId }
            .flatMap { listOfNotNull(it.getString(NAME), it.getString(INVERSE_NAME)) }
        val wanted = listOfNotNull(name, inverseName)
        val clash = taken.firstOrNull { existingName -> wanted.any { it.lowercase() == existingName.lowercase() } }
        if (clash != null) {
            throw VocabularyConflict("There is already a ${kind.noun} called \"$clash\".")
        }
    }

    /** "3 issues are still in that status." — mirrors VocabularyRepository.inUseMessage. */
    private fun inUseMessage(kind: VocabularyKind, row: VocabularyRow): String {
        val isOne = row.usageCount == 1L
        val subject = if (isOne) "1 issue" else "${row.usageCount} issues"
        val verb = when {
            kind == VocabularyKind.STATUS && isOne -> "is still in"
            kind == VocabularyKind.STATUS -> "are still in"
            isOne -> "still has"
            else -> "still have"
        }
        val andThen = if (isOne) "Move it somewhere else first" else "Move them somewhere else first"
        return "$subject $verb that ${kind.noun}. $andThen, and then delete it."
    }

    private fun DocumentSnapshot.toRow(uses: Map<Long, Long>): VocabularyRow = VocabularyRow(
        id = getLong(ID)!!,
        projectId = getLong(PROJECT_ID)!!,
        name = getString(NAME).orEmpty(),
        position = getLong(POSITION) ?: 0L,
        requiresResolution = getBoolean(REQUIRES_RESOLUTION) ?: false,
        isDone = getBoolean(IS_DONE) ?: false,
        usageCount = uses[getLong(ID)] ?: 0L,
        // Read unconditionally rather than behind a `kind ==` test, because a document
        // of any other kind simply has no such fields and answers null/false — the
        // same way `requiresResolution` above is read off a label. Absent `inverseName`
        // is not a fallback for a relation kind, it is the meaning: symmetric.
        inverseName = getString(INVERSE_NAME),
        marksBlocked = getBoolean(MARKS_BLOCKED) ?: false,
    )

    /**
     * The `vocabulary` collection's shape. `internal` — not private — because
     * [FirestoreSprintStore] reads sprint and status rows from the same collection
     * and must agree with this store on every field name. Deliberately kept here,
     * on the store that owns the write path, rather than in a shared helper file.
     */
    internal companion object {
        const val COLLECTION = "vocabulary"
        const val COUNTER = "vocabulary"

        const val ID = "id"
        const val PROJECT_ID = "projectId"
        const val KIND = "kind"
        const val NAME = "name"
        const val POSITION = "position"
        const val REQUIRES_RESOLUTION = "requiresResolution"

        /**
         * A resolution's "the work was done" flag (LNL-134) — the mirror of
         * [REQUIRES_RESOLUTION], meaningful only on resolution rows and false on
         * every other kind. Read back by [toStatusRecord] and [toRow], written by
         * [add]/[rename] here and the shared seed helpers, so the board and the
         * settings editor agree on it. See Resolutions.sq.
         */
        const val IS_DONE = "isDone"
        const val COMPLETED_AT = "completedAt"

        /**
         * A relation kind's **to**-side label — "Blocks" beside a `name` of "Blocked
         * by" (LNL-215) — meaningful only on `kind == RELATION_KIND` rows and absent
         * from every other kind's document.
         *
         * **Absent (or explicitly null) is the whole encoding of symmetry**, not a
         * missing value: a kind with no opposite label reads the same from both ends.
         * There is deliberately no `isSymmetric` companion that could disagree with it,
         * in the same idiom as a null `resolutionId` meaning "not in a closing column".
         * Written by [relationKindFields], read here and by
         * [DocumentSnapshot.toRelationKindRecord], so the settings editor and the board
         * agree on it. See IssueRelationKinds.sq's inverse_name.
         */
        const val INVERSE_NAME = "inverseName"

        /**
         * A relation kind's "issues on the *from* side of one of these are blocked"
         * flag (LNL-215) — the relation-kind analogue of [REQUIRES_RESOLUTION], read
         * from data rather than from the row's name so a renamed kind keeps its
         * meaning. False on every other kind, and false when absent, which is the
         * safe direction to be wrong in: an un-dimmed card, never a board greyed by a
         * flag nobody set.
         */
        const val MARKS_BLOCKED = "marksBlocked"
    }
}
