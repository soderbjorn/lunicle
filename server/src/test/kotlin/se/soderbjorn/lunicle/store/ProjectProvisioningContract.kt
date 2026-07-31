/**
 * The behaviour every [ProjectProvisioning] implementation must exhibit — the one
 * store-set seam that is an *orchestration*, not a row insert: a usable project is
 * its document **and** its five seeded vocabularies, made to exist together.
 *
 * These are the semantics a document backend is most likely to get subtly different
 * from SQL, so they are pinned explicitly: create seeds the exact default labels,
 * components, statuses, priorities and resolutions, in order, with only the closing
 * status demanding a resolution; a duplicate name or prefix is refused
 * case-insensitively; update renames; delete removes the project and its whole
 * board; and reorder puts the instance's projects in the order given (or refuses a
 * set that is not exactly them).
 *
 * The seeded vocabulary is read back through the *concrete* board stores, not the
 * provisioner — so this doubles as the proof that what `create` seeds is exactly
 * what the board reads, on either backend. A subclass per backend supplies the
 * provisioner and those read-backs.
 *
 * @see se.soderbjorn.lunicle.ProjectRepository the SQLite reference implementation.
 * @see se.soderbjorn.lunicle.FirestoreProjectRepository the Firestore implementation.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.CLOSING_STATUS
import se.soderbjorn.lunicle.DEFAULT_COMPONENTS
import se.soderbjorn.lunicle.DEFAULT_LABELS
import se.soderbjorn.lunicle.DEFAULT_PRIORITIES
import se.soderbjorn.lunicle.DEFAULT_RESOLUTIONS
import se.soderbjorn.lunicle.DEFAULT_STATUSES
import se.soderbjorn.lunicle.ProjectConflict
import se.soderbjorn.lunicle.ProjectRecord
import se.soderbjorn.lunicle.StatusRecord
import se.soderbjorn.lunicle.VocabularyRecord

abstract class ProjectProvisioningContract {
    protected abstract val provisioning: ProjectProvisioning

    // Read-backs through the concrete board stores — the seeded board as the board sees it.
    protected abstract suspend fun statusesOf(projectId: Long): List<StatusRecord>
    protected abstract suspend fun prioritiesOf(projectId: Long): List<StatusRecord>
    protected abstract suspend fun resolutionsOf(projectId: Long): List<StatusRecord>
    protected abstract suspend fun labelsOf(projectId: Long): List<VocabularyRecord>
    protected abstract suspend fun componentsOf(projectId: Long): List<VocabularyRecord>
    protected abstract suspend fun projectById(id: Long): ProjectRecord?
    protected abstract suspend fun allProjects(): List<ProjectRecord>

    // ── The delete cascade (LNL-177) ─────────────────────────────────────────
    //
    // Seed a project with one of everything that hangs off it, then count what is
    // left afterwards. The counts are read through the concrete stores, like the
    // vocabulary read-backs above, so "gone" means gone as the app would see it.

    /**
     * Fill [projectId] with one of everything a project owns, and return the ids.
     *
     * One issue carrying a comment, a history entry, a watcher and an attachment;
     * one forum carrying a post; and one role grant. Enough that every branch of the
     * cascade has something to take, and small enough to seed on either backend.
     */
    protected abstract suspend fun seedContents(projectId: Long): SeededContents

    /** What [seedContents] made, by id, so each can be looked for afterwards. */
    protected data class SeededContents(
        val issueId: Long,
        val forumId: Long,
    )

    protected abstract suspend fun issueExists(id: Long): Boolean
    protected abstract suspend fun commentCountOf(issueId: Long): Int
    protected abstract suspend fun historyCountOf(issueId: Long): Int
    protected abstract suspend fun issueWatcherCountOf(issueId: Long): Int
    protected abstract suspend fun forumExists(id: Long): Boolean
    protected abstract suspend fun postCountOf(forumId: Long): Int
    protected abstract suspend fun attachmentCountOf(projectId: Long): Int
    protected abstract suspend fun roleGrantCountOf(projectId: Long): Int
    protected abstract suspend fun projectWatcherCountOf(projectId: Long): Int

    @Test
    fun `create seeds the default vocabularies in order`(): Unit = runBlocking {
        val project = provisioning.create("Seeded", "SED")

        assertEquals(DEFAULT_LABELS, labelsOf(project.id).map { it.name }, "labels seeded in list order")
        assertEquals(DEFAULT_COMPONENTS, componentsOf(project.id).map { it.name }, "components seeded in list order")
        assertEquals(DEFAULT_STATUSES, statusesOf(project.id).map { it.name }, "statuses seeded in list order")
        assertEquals(DEFAULT_PRIORITIES, prioritiesOf(project.id).map { it.name }, "priorities seeded in list order")
        assertEquals(DEFAULT_RESOLUTIONS, resolutionsOf(project.id).map { it.name }, "resolutions seeded in list order")

        // Positions are contiguous 0..n-1 within each kind — the order is the data.
        val statuses = statusesOf(project.id)
        assertEquals((0 until statuses.size).map { it.toLong() }, statuses.map { it.position }, "status positions are 0..n-1")
    }

    @Test
    fun `create flags only the closing status as requiring a resolution`(): Unit = runBlocking {
        val project = provisioning.create("Closing", "CLO")
        val statuses = statusesOf(project.id)

        val closing = statuses.single { it.name == CLOSING_STATUS }
        assertTrue(closing.requiresResolution, "the closing status demands a resolution")
        assertTrue(
            statuses.filter { it.name != CLOSING_STATUS }.none { it.requiresResolution },
            "no other seeded status demands one",
        )
    }

    @Test
    fun `create refuses a duplicate name case-insensitively`(): Unit = runBlocking {
        provisioning.create("Duplicate", "DUP")
        assertFailsWith<ProjectConflict> { provisioning.create("duplicate", "OTHER") }
    }

    @Test
    fun `create refuses a duplicate prefix case-insensitively`(): Unit = runBlocking {
        provisioning.create("First", "PRE")
        assertFailsWith<ProjectConflict> { provisioning.create("Second", "pre") }
    }

    @Test
    fun `create refuses a blank name or prefix`(): Unit = runBlocking {
        assertFailsWith<ProjectConflict> { provisioning.create("   ", "OK") }
        assertFailsWith<ProjectConflict> { provisioning.create("Named", "   ") }
    }

    @Test
    fun `update renames a project`(): Unit = runBlocking {
        val project = provisioning.create("Before", "BEF")
        // No visibility here any more (LNL-191): who may see a project is its
        // audience rows, set through their own gesture rather than riding along on a
        // rename. See RoleStoreContract's audience section.
        val updated = provisioning.update(project.id, "After", "AFT")

        assertEquals("After", updated.name)
        assertEquals("AFT", updated.namePrefix)
        assertEquals("After", projectById(project.id)?.name, "the rename is persisted")
    }

    @Test
    fun `update refuses a name that is another project's`(): Unit = runBlocking {
        provisioning.create("Taken", "TAK")
        val other = provisioning.create("Free", "FRE")
        assertFailsWith<ProjectConflict> {
            provisioning.update(other.id, "taken", "FRE")
        }
    }

    @Test
    fun `delete removes the project`(): Unit = runBlocking {
        val project = provisioning.create("Doomed", "DOM")
        assertTrue(statusesOf(project.id).isNotEmpty(), "the board was seeded")

        provisioning.delete(project.id)

        assertNull(projectById(project.id), "the project is gone")
    }

    /**
     * Deleting a project takes everything in it — on **both** backends (LNL-177).
     *
     * This assertion used to be deliberately absent, and the comment where it should
     * have been said so: SQLite cascades every child table by foreign key, the
     * Firestore document stores had no cross-collection cascade at all, and pinning
     * "the board went too" would have asserted a SQLite-FK behaviour Firestore did
     * not have. That was an accurate description of a bug, not a reason not to test
     * for it. On the Cloud Run deploy a deleted project left its entire contents
     * behind — issues, comments, history, forums, posts, the whole vocabulary, role
     * grants, watches and attachment rows — as documents no query could ever reach
     * again: billable, permanent, and for a private project still holding its text.
     *
     * So it is pinned here, at the one seam both backends implement, and the
     * Firestore side performs by hand what SQLite declares. Every assertion below
     * names the collection that leaked if it fails.
     *
     * The vocabulary is checked through the same concrete-store read-backs the seed
     * assertions use, so "gone" means gone as the board itself would see it.
     */
    @Test
    fun `delete empties the project of everything it contained`(): Unit = runBlocking {
        val project = provisioning.create("Full", "FUL")
        val seeded = seedContents(project.id)

        // The seed is only meaningful if it actually put something there.
        assertTrue(issueExists(seeded.issueId), "the fixture seeded no issue")
        assertEquals(1, commentCountOf(seeded.issueId), "the fixture seeded no comment")
        assertTrue(forumExists(seeded.forumId), "the fixture seeded no forum")

        provisioning.delete(project.id)

        assertNull(projectById(project.id), "the project is gone")

        // The board vocabulary — all five seeded kinds.
        assertEquals(emptyList(), statusesOf(project.id), "the statuses outlived the project")
        assertEquals(emptyList(), prioritiesOf(project.id), "the priorities outlived the project")
        assertEquals(emptyList(), resolutionsOf(project.id), "the resolutions outlived the project")
        assertEquals(emptyList(), labelsOf(project.id), "the labels outlived the project")
        assertEquals(emptyList(), componentsOf(project.id), "the components outlived the project")

        // The issue and everything under it.
        assertFalse(issueExists(seeded.issueId), "the issue outlived the project")
        assertEquals(0, commentCountOf(seeded.issueId), "the comments outlived the issue")
        assertEquals(0, historyCountOf(seeded.issueId), "the history outlived the issue")
        assertEquals(0, issueWatcherCountOf(seeded.issueId), "the issue watches outlived the issue")

        // The forum and its posts.
        assertFalse(forumExists(seeded.forumId), "the forum outlived the project")
        assertEquals(0, postCountOf(seeded.forumId), "the posts outlived the forum")

        // And the three that hang off the project directly.
        assertEquals(0, attachmentCountOf(project.id), "the attachment rows outlived the project")
        assertEquals(0, roleGrantCountOf(project.id), "the role grants outlived the project")
        assertEquals(0, projectWatcherCountOf(project.id), "the project watches outlived the project")
    }

    /**
     * The other half, and the one a cascade keyed on nothing fails: deleting a
     * project leaves the *next* project's contents alone.
     *
     * Every assertion above is satisfied by a cascade that empties the whole
     * database, which is exactly the mistake a hand-written document-store walk
     * invites — one query missing its `whereEqualTo`, and everything matches.
     */
    @Test
    fun `delete spares another project's contents`(): Unit = runBlocking {
        val doomed = provisioning.create("Doomed", "DOO")
        val spared = provisioning.create("Spared", "SPA")
        val doomedContents = seedContents(doomed.id)
        val sparedContents = seedContents(spared.id)

        provisioning.delete(doomed.id)

        assertFalse(issueExists(doomedContents.issueId), "the doomed project's issue survived")
        assertTrue(issueExists(sparedContents.issueId), "deleting one project took another's issue")
        assertEquals(1, commentCountOf(sparedContents.issueId), "…and its comments")
        assertEquals(1, historyCountOf(sparedContents.issueId), "…and its history")
        assertEquals(1, issueWatcherCountOf(sparedContents.issueId), "…and its watches")
        assertTrue(forumExists(sparedContents.forumId), "…and its forum")
        assertEquals(1, postCountOf(sparedContents.forumId), "…and its posts")
        assertEquals(1, attachmentCountOf(spared.id), "…and its attachment rows")
        assertEquals(1, roleGrantCountOf(spared.id), "…and its role grants")
        assertEquals(1, projectWatcherCountOf(spared.id), "…and its watches")
        assertEquals(DEFAULT_STATUSES, statusesOf(spared.id).map { it.name }, "…and its board vocabulary")
    }

    @Test
    fun `reorder puts the instance's projects in the order given`(): Unit = runBlocking {
        val first = provisioning.create("One", "ONE")
        val second = provisioning.create("Two", "TWO")

        provisioning.reorder(listOf(second.id, first.id))
        assertEquals(listOf(second.id, first.id), allProjects().map { it.id }, "projects follow the requested order")
    }

    @Test
    fun `reorder refuses a set that is not exactly the projects`(): Unit = runBlocking {
        val only = provisioning.create("Solo", "SOL")
        assertFailsWith<ProjectConflict> { provisioning.reorder(listOf(only.id, only.id + 999)) }
    }
}
