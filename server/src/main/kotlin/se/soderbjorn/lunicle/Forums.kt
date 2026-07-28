/**
 * The forums table, and the rules for writing to it.
 *
 * Two classes, split the way [Projects] splits: [ForumStore] is SQL in, data
 * class out, no decisions; [ForumRepository] owns trimming, uniqueness, position
 * assignment and the reorder transaction. A route never mentions a transaction,
 * and a store never mentions a rule.
 *
 * Nothing here answers a permission question — [AccessControl] does that, and
 * the routes ask it before they reach this file. See AccessControl's preamble
 * for why that split is absolute.
 *
 * @see forumRoutes
 * @see AccessControl.canAdministerProject
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * A discussion forum as this server knows it.
 *
 * @property description one line under the name in the forum list, or null when
 *   the forum does not need one. Blank is normalised to null by
 *   [ForumRepository], so "empty" has exactly one spelling.
 * @property position where it sits in its project's list, 0 first — the same
 *   convention the vocabularies use. See Forums.sq.
 */
data class ForumRecord(
    val id: Long,
    val projectId: Long,
    val name: String,
    val description: String?,
    val position: Long,
    val createdAt: Long,
)

/**
 * Why a forum write was refused, in words a user should see.
 *
 * The same shape as `VocabularyRefusal`, and for the same reason: a duplicate
 * name is a thing the person typing can fix, so it deserves a sentence rather
 * than a 500. Anything that is *not* one of these is a bug and is allowed to
 * propagate.
 */
class ForumRefusal(message: String) : RuntimeException(message)

/** Reads and writes `forums`. No rules; see [ForumRepository]. */
class ForumStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.ForumStore {
    override suspend fun forProject(projectId: Long): List<ForumRecord> =
        withContext(DatabaseDispatcher) {
            database.forumsQueries.forProject(projectId).executeAsList().map(::toRecord)
        }

    override suspend fun findByIdInProject(id: Long, projectId: Long): ForumRecord? =
        withContext(DatabaseDispatcher) {
            database.forumsQueries.findByIdInProject(id, projectId).executeAsOneOrNull()?.let(::toRecord)
        }

    /**
     * By id alone, for callers that start from a forum and must walk to its
     * project to ask whether they may see it.
     *
     * The project check does not disappear by using this — it moves to the
     * caller, which is why [ForumRecord.projectId] is on the way back. Anything
     * reachable by a URL uses [findByIdInProject] instead, so a mismatched pair
     * is a 404 rather than a cross-project edit.
     */
    override suspend fun findById(id: Long): ForumRecord? =
        withContext(DatabaseDispatcher) {
            database.forumsQueries.findById(id).executeAsOneOrNull()?.let(::toRecord)
        }

    override suspend fun insert(projectId: Long, name: String, description: String?): ForumRecord =
        withContext(DatabaseDispatcher) {
            val createdAt = now()
            // One transaction, because the position depends on a read: two
            // callers appending at the same moment would otherwise both be told
            // the list ends at the same place and both write there.
            val id = database.transactionWithResult {
                val position = database.forumsQueries.nextPosition(projectId).executeAsOne()
                database.forumsQueries.insert(projectId, name, description, position, createdAt).executeAsOne()
            }
            database.forumsQueries.findById(id).executeAsOne().let(::toRecord)
        }

    override suspend fun update(id: Long, name: String, description: String?): Unit =
        withContext(DatabaseDispatcher) {
            database.forumsQueries.update(name, description, id)
        }

    override suspend fun delete(id: Long): Unit =
        withContext(DatabaseDispatcher) {
            database.forumsQueries.delete(id)
        }

    /**
     * Rewrite a whole project's order in one transaction.
     *
     * The whole list rather than a pair of swaps, for `VocabularyRepository`'s
     * reason: every swap passes through a state where two rows share a position,
     * and a half-applied reorder is a list nobody asked for. The caller has
     * already checked that [ids] names exactly this project's forums.
     */
    override suspend fun setOrder(ids: List<Long>): Unit =
        withContext(DatabaseDispatcher) {
            database.transaction {
                ids.forEachIndexed { index, id ->
                    database.forumsQueries.setPosition(index.toLong(), id)
                }
            }
        }

    private fun toRecord(row: se.soderbjorn.lunicle.db.Forums) = ForumRecord(
        id = row.id,
        projectId = row.project_id,
        name = row.name,
        description = row.description,
        position = row.position,
        createdAt = row.created_at,
    )
}

/**
 * The rules: what a forum name may be, and what a reorder must name.
 *
 * Also the only thing the routes hold. The reads below are pass-throughs to
 * [ForumStore] and exist so that [BoardDependencies] carries one forum
 * collaborator rather than two — a route that held both would eventually read
 * through the store on a path that was supposed to go through a rule. The
 * vocabularies carry both because their stores are read by the board, the
 * issue editor and the settings dialog for reasons that have nothing to do with
 * writing; forums have no such second audience.
 *
 * @param forums the store.
 * @param attachments the volume, and [attachmentStore] the rows on it. Both here
 *   only for [delete]: since LNL-61 a forum holds posts, posts hold comments, and
 *   both can hold files, so deleting a forum is the one operation in this file
 *   that has to reach outside the table. See [IssueRepository.delete], which is
 *   the same pairing for the same reason.
 */
class ForumRepository(
    private val forums: se.soderbjorn.lunicle.store.ForumStore,
    private val attachments: AttachmentRepository,
    private val attachmentStore: se.soderbjorn.lunicle.store.AttachmentStore,
) {
    /** This project's forums, in the administrator's chosen order. */
    suspend fun forProject(projectId: Long): List<ForumRecord> = forums.forProject(projectId)

    /** One forum, proving it is this project's. See Forums.sq's `findByIdInProject`. */
    suspend fun findByIdInProject(id: Long, projectId: Long): ForumRecord? =
        forums.findByIdInProject(id, projectId)

    /** One forum by id alone, leaving the project check to the caller. See [ForumStore.findById]. */
    suspend fun findById(id: Long): ForumRecord? = forums.findById(id)

    /**
     * Create a forum at the end of the project's list.
     *
     * @throws ForumRefusal if the name is blank, too long, or already taken in
     *   this project. All three are things the person typing can fix.
     */
    suspend fun create(projectId: Long, name: String, description: String?): ForumRecord {
        val cleanName = validName(name)
        val existing = forums.forProject(projectId)
        if (existing.any { it.name.equals(cleanName, ignoreCase = true) }) {
            throw ForumRefusal("This project already has a forum called \"$cleanName\".")
        }
        return forums.insert(projectId, cleanName, cleanDescription(description))
    }

    /**
     * Rename and re-describe a forum.
     *
     * The uniqueness check excludes the forum itself, so saving a forum without
     * touching its name is not a refusal — which it would be if the row were
     * compared against the whole list including itself.
     */
    suspend fun edit(forum: ForumRecord, name: String, description: String?): ForumRecord {
        val cleanName = validName(name)
        val clash = forums.forProject(forum.projectId)
            .any { it.id != forum.id && it.name.equals(cleanName, ignoreCase = true) }
        if (clash) throw ForumRefusal("This project already has a forum called \"$cleanName\".")
        forums.update(forum.id, cleanName, cleanDescription(description))
        return forum.copy(name = cleanName, description = cleanDescription(description))
    }

    /**
     * Delete a forum, everything posted in it, and every file any of it owned.
     *
     * The keys are read *first*, and that ordering is the whole of why this
     * method has two collaborators it otherwise would not: one `DELETE` here
     * cascades through `forum_posts` and `forum_comments` and takes every
     * attachment *row* with it, and SQLite cannot reach the filesystem. Read them
     * afterwards and nothing on this instance can ever name those files again —
     * they sit on the volume for good, growing, unattributable. This is the same
     * failure [IssueRepository.delete] guards against, one container up.
     */
    suspend fun delete(forum: ForumRecord) {
        val doomed = attachmentStore.keysForForum(forum.id)
        forums.delete(forum.id)
        doomed.forEach { attachments.deleteBlob(it) }
    }

    /**
     * Put this project's forums in the given order.
     *
     * @throws ForumRefusal if [ids] is not exactly this project's forums.
     *   Checked as a *set*, so a caller that omits one, repeats one, or slips in
     *   a forum from another project is refused rather than partially applied —
     *   the last of those being how one project's administrator would otherwise
     *   reach another project's rows. `VocabularyRepository.reorder` draws the
     *   same line for the same reason.
     */
    suspend fun reorder(projectId: Long, ids: List<Long>) {
        val current = forums.forProject(projectId).map { it.id }
        if (ids.size != current.size || ids.toSet() != current.toSet()) {
            throw ForumRefusal("That order does not name this project's forums.")
        }
        forums.setOrder(ids)
    }

    private fun validName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw ForumRefusal("A forum needs a name.")
        if (trimmed.length > MAX_FORUM_NAME_LENGTH) {
            throw ForumRefusal("A forum name may be at most $MAX_FORUM_NAME_LENGTH characters.")
        }
        return trimmed
    }

    /**
     * Blank becomes null, so "no description" has one spelling rather than two
     * that render differently.
     */
    private fun cleanDescription(description: String?): String? =
        description?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_FORUM_DESCRIPTION_LENGTH)
}

/**
 * How long a forum name may be.
 *
 * Bounded for the same reason a vocabulary name is: this is rendered in a
 * dropdown and a pane header, so a caller sending a kilobyte of it would not
 * break the server — it would make the Discussion tab unreadable for everyone in
 * the project.
 */
const val MAX_FORUM_NAME_LENGTH: Int = 60

/** How long a forum's one-line description may be. Truncated rather than refused. */
const val MAX_FORUM_DESCRIPTION_LENGTH: Int = 200
