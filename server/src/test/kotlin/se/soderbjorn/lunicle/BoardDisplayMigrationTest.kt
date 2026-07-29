/**
 * Moving "hide issue numbers" from every person to every project (LNL-194).
 *
 * What is worth pinning here is not the copy — that is four lines — but the three
 * properties the design leans on, each of which fails silently:
 *
 *  - **The owner's answer wins.** Arbitrary and stated, rather than a majority nobody
 *    can see. A pass that took the first preference it found would pass a
 *    single-account test and get it wrong on every real volume.
 *  - **It runs once.** The nullable column is the marker, so an administrator who
 *    flips the switch off must not have it flipped back by the next boot. This is the
 *    failure that would take a deploy to notice and a week to believe.
 *  - **It is interruptible.** A half-finished pass is finished by the next one, and a
 *    finished one does nothing. Asserted by simply running it twice.
 *
 * @see copyBoardDisplayFromOwners
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.UiSettingKeys
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardDisplayMigrationTest {
    private val file: File = Files.createTempFile("lunicle-board-display", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val roles = RoleStore(database)
    private val projects = ProjectStore(database)
    private val uiSettings = UiSettingsStore(database)
    private val instanceSettings = InstanceSettingsStore(database)
    private val attachmentStore = AttachmentStore(database)
    private val attachments =
        AttachmentRepository(attachmentStore, File(file.parentFile, "attachments-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    /**
     * The owner's stored preference lands on the project — and the other reader's does
     * not.
     *
     * Two accounts with **opposite** preferences, so a pass that took "the first one" or
     * "anybody who had it on" fails. That is the whole of what "the owner's, not a
     * majority" means, and it is exactly the assertion a one-account fixture cannot make.
     */
    @Test
    fun `the owner's preference wins over another reader's`(): Unit = runBlocking {
        val owner = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-own", "Ona", "ona@example.com"))
        val other = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-oth", "Oth", "oth@example.com"))
        val project = projectRepository.create("Lunamux", "LMX")
        roles.setRole(owner.id, project.id, ProjectRole.OWNER)
        unsettle(project.id)

        storeLegacyPreference(owner.id, project.id, hide = true)
        storeLegacyPreference(other.id, project.id, hide = false)

        assertEquals(1, copy(), "The undecided project was not settled.")
        assertTrue(
            projects.findById(project.id)!!.hideIssueNumbers,
            "The project took somebody other than its owner's answer.",
        )
    }

    /**
     * With several owners, the lowest id — a tie-break, not a decision, and the point is
     * only that a re-run gives the same answer.
     */
    @Test
    fun `two owners break the tie by id, so a re-run agrees with itself`(): Unit = runBlocking {
        val first = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-a", "A", "a@example.com"))
        val second = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-b", "B", "b@example.com"))
        val project = projectRepository.create("Lunamux", "LMX")
        roles.setRole(first.id, project.id, ProjectRole.OWNER)
        roles.setRole(second.id, project.id, ProjectRole.OWNER)
        unsettle(project.id)

        storeLegacyPreference(first.id, project.id, hide = true)
        storeLegacyPreference(second.id, project.id, hide = false)

        copy()
        assertTrue(projects.findById(project.id)!!.hideIssueNumbers)
    }

    /**
     * No owner, no preference, no blob at all — the project lands on the default, which
     * is numbers shown: the state every board had before anybody chose.
     */
    @Test
    fun `a project with nobody to ask lands on the default`(): Unit = runBlocking {
        val project = projectRepository.create("Lunamux", "LMX")
        unsettle(project.id)
        copy()
        val settled = projects.findById(project.id)!!
        assertFalse(settled.hideIssueNumbers)
        assertEquals(false, settled.hideIssueNumbersStored, "The row was left undecided, so it will be revisited.")
    }

    /**
     * THE test: an administrator's later change survives the next boot.
     *
     * The nullable column is the only thing standing between "copy the old preference
     * once" and "copy it on every restart", and a pass that re-read the blob would revert
     * a deliberate change every time the process came up. The second run must also report
     * having done nothing, which is what makes the startup log honest.
     */
    @Test
    fun `a second run changes nothing, including a decision made since`(): Unit = runBlocking {
        val owner = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-own", "Ona", "ona@example.com"))
        val project = projectRepository.create("Lunamux", "LMX")
        roles.setRole(owner.id, project.id, ProjectRole.OWNER)
        unsettle(project.id)
        storeLegacyPreference(owner.id, project.id, hide = true)

        assertEquals(1, copy())
        assertTrue(projects.findById(project.id)!!.hideIssueNumbers)

        // The administrator turns it off, deliberately, months later.
        projects.setBoardDisplay(project.id, showIssueAuthor = false, hideIssueNumbers = false)

        assertEquals(0, copy(), "The second pass claimed to settle a project that was already settled.")
        assertFalse(
            projects.findById(project.id)!!.hideIssueNumbers,
            "The next boot reverted a change an administrator made on purpose.",
        )
    }

    /** A project created after the move is already settled, so the pass never visits it. */
    @Test
    fun `a freshly created project is never visited`(): Unit = runBlocking {
        projectRepository.create("Lunamux", "LMX")
        assertEquals(0, copy(), "A brand new project was treated as one waiting to be migrated.")
    }

    /**
     * The instance owner's preference is the fallback for a board with no owner rung.
     *
     * That is why the startup pass runs *after* the owner seat, and the ordering is what
     * this asserts: on a freshly migrated volume nobody holds an owner rung on anything.
     */
    @Test
    fun `a project with no owner rung falls back to the instance owner`(): Unit = runBlocking {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-sys", "Sys", "sys@example.com"))
        assertTrue(admin.isInstanceAdmin, "The first account is meant to be the system administrator.")
        val project = projectRepository.create("Lunamux", "LMX")
        unsettle(project.id)
        storeLegacyPreference(admin.id, project.id, hide = true)

        seatInstanceOwner(users, instanceSettings)
        copy()
        assertTrue(projects.findById(project.id)!!.hideIssueNumbers)
    }

    /** A blob this code cannot read is no preference at all, never a failed boot. */
    @Test
    fun `an unreadable blob leaves the project on the default`(): Unit = runBlocking {
        val owner = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-own", "Ona", "ona@example.com"))
        val project = projectRepository.create("Lunamux", "LMX")
        roles.setRole(owner.id, project.id, ProjectRole.OWNER)
        unsettle(project.id)
        uiSettings.put(owner.id, UiSettingKeys.PROJECT_PREFS, "{ not json at all")

        copy()
        assertFalse(projects.findById(project.id)!!.hideIssueNumbers)
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private suspend fun copy(): Int =
        copyBoardDisplayFromOwners(projects, roles, users, uiSettings, instanceSettings)

    /**
     * Put a project back into the "nobody has decided" state 34.sqm leaves a migrated row
     * in.
     *
     * `insert` settles it deliberately — a new board has no old preference to copy — so
     * every test here has to undo that to be testing the migration at all. Written
     * through the raw query because nothing in the store API can produce a null: that
     * state exists only for the migration, and giving it a public setter would be giving
     * somebody a way to reach it on purpose.
     */
    private suspend fun unsettle(projectId: Long) {
        database.projectsQueries.setBoardDisplay(0L, null, projectId)
        assertNull(
            projects.findById(projectId)!!.hideIssueNumbersStored,
            "The fixture could not reproduce the pre-migration state.",
        )
    }

    /**
     * Write the blob shape the old per-user preference used, by hand.
     *
     * By hand because `UserProjectPrefs` no longer has the field — removing it is what
     * retires the old rows — so there is no type left that can produce this JSON. It is
     * the same reason the reader in the migration walks a [kotlinx.serialization.json.JsonObject].
     */
    private suspend fun storeLegacyPreference(userId: Long, projectId: Long, hide: Boolean) {
        uiSettings.put(
            userId,
            UiSettingKeys.PROJECT_PREFS,
            """{"byProject":{"$projectId":{"hiddenColumnIds":[],"hideIssueNumbers":$hide}}}""",
        )
    }
}
