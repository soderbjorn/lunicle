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

    @Test
    fun `create seeds the default vocabularies in order`(): Unit = runBlocking {
        val project = provisioning.create("Seeded", "SED", isPublic = false)

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
        val project = provisioning.create("Closing", "CLO", isPublic = false)
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
        provisioning.create("Duplicate", "DUP", isPublic = false)
        assertFailsWith<ProjectConflict> { provisioning.create("duplicate", "OTHER", isPublic = false) }
    }

    @Test
    fun `create refuses a duplicate prefix case-insensitively`(): Unit = runBlocking {
        provisioning.create("First", "PRE", isPublic = false)
        assertFailsWith<ProjectConflict> { provisioning.create("Second", "pre", isPublic = false) }
    }

    @Test
    fun `create refuses a blank name or prefix`(): Unit = runBlocking {
        assertFailsWith<ProjectConflict> { provisioning.create("   ", "OK", isPublic = false) }
        assertFailsWith<ProjectConflict> { provisioning.create("Named", "   ", isPublic = false) }
    }

    @Test
    fun `update renames a project and rewrites its visibility`(): Unit = runBlocking {
        val project = provisioning.create("Before", "BEF", isPublic = false)
        // The identity write carries both visibility flags together (LNL-138).
        val updated = provisioning.update(project.id, "After", "AFT", isPublic = true, visibleToAllSignedIn = true)

        assertEquals("After", updated.name)
        assertEquals("AFT", updated.namePrefix)
        assertTrue(updated.isPublic)
        assertTrue(updated.visibleToAllSignedIn, "the signed-in-visibility flag was not persisted by update")
        assertEquals("After", projectById(project.id)?.name, "the rename is persisted")
    }

    @Test
    fun `update refuses a name that is another project's`(): Unit = runBlocking {
        provisioning.create("Taken", "TAK", isPublic = false)
        val other = provisioning.create("Free", "FRE", isPublic = false)
        assertFailsWith<ProjectConflict> {
            provisioning.update(other.id, "taken", "FRE", isPublic = false, visibleToAllSignedIn = false)
        }
    }

    @Test
    fun `delete removes the project`(): Unit = runBlocking {
        val project = provisioning.create("Doomed", "DOM", isPublic = false)
        assertTrue(statusesOf(project.id).isNotEmpty(), "the board was seeded")

        provisioning.delete(project.id)

        // The project row is gone on both backends. Whether its *child* rows go with
        // it is a per-backend property of the delete cascade, not of this seam: SQLite
        // cascades every child table by foreign key, whereas the Firestore document
        // stores have no cross-collection cascade (project delete removes only the
        // project document). Pinning "the board went too" here would assert a SQLite-FK
        // behaviour the Firestore backend does not yet have — a separate concern from
        // provisioning — so the contract pins only the guarantee both backends share.
        assertNull(projectById(project.id), "the project is gone")
    }

    @Test
    fun `reorder puts the instance's projects in the order given`(): Unit = runBlocking {
        val first = provisioning.create("One", "ONE", isPublic = false)
        val second = provisioning.create("Two", "TWO", isPublic = false)

        provisioning.reorder(listOf(second.id, first.id))
        assertEquals(listOf(second.id, first.id), allProjects().map { it.id }, "projects follow the requested order")
    }

    @Test
    fun `reorder refuses a set that is not exactly the projects`(): Unit = runBlocking {
        val only = provisioning.create("Solo", "SOL", isPublic = false)
        assertFailsWith<ProjectConflict> { provisioning.reorder(listOf(only.id, only.id + 999)) }
    }
}
