/**
 * The behaviour every [ProjectStore] implementation must exhibit.
 *
 * Persistence-level, since project validation and vocabulary seeding are
 * backend-agnostic orchestration a layer up: a row round-trips through insert →
 * findById, findByName finds it (and misses cleanly), selectAll follows the order
 * setOrder writes, the feature and requirement flags flip, the repository config
 * is stored and read back as the same value, and delete removes the row.
 *
 * No backend seeding hook is needed — [ProjectStore.insert] is the seed.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.RepositoryConfig
import se.soderbjorn.lunicle.RepositoryRef
import se.soderbjorn.lunicle.TokenSource

abstract class ProjectStoreContract {
    protected abstract val store: ProjectStore

    private var seq = 0
    private suspend fun insertProject() = store.insert("Project ${seq}", "PR${seq++}")

    @Test
    fun `an inserted project round-trips through findById`() = runBlocking {
        val created = store.insert("Lunamux", "LMX")
        val read = store.findById(created.id)!!
        assertEquals("Lunamux", read.name)
        assertEquals("LMX", read.namePrefix)
    }

    /**
     * The two visibility tests that stood here are gone with the columns (LNL-191).
     *
     * `is_public` and `visible_to_all_signed_in` were the only fields this store
     * carried that were *about permissions* rather than about a project, and they
     * are now audience rows in the role store — where they are covered by
     * [RoleStoreContract]'s audience section. There is nothing to assert here in
     * their place: a project row no longer has an opinion about who may read it,
     * which is the whole change.
     */
    @Test
    fun `update rewrites the name and the prefix`() = runBlocking {
        val p = store.insert("Before", "BEF")
        store.update(p.id, "After", "AFT")
        val read = store.findById(p.id)!!
        assertEquals("After", read.name)
        assertEquals("AFT", read.namePrefix)
    }

    @Test
    fun `findByName finds a project and misses cleanly`() = runBlocking {
        val created = store.insert("Findable", "FND")
        assertEquals(created.id, store.findByName("Findable")?.id)
        assertNull(store.findByName("Nonexistent"))
    }

    @Test
    fun `selectAll follows the order setOrder writes`() = runBlocking {
        val a = insertProject()
        val b = insertProject()
        val c = insertProject()
        // Inserted in a,b,c order; ask for the reverse.
        store.setOrder(listOf(c.id, b.id, a.id))
        assertEquals(listOf(c.id, b.id, a.id), store.selectAll().map { it.id })
    }

    @Test
    fun `the feature and requirement flags flip`() = runBlocking {
        val p = insertProject()
        // Requirements default off. The two feature flags no longer round-trip at
        // all — every backend reads them as false since LNL-190 retired discussions
        // and private messages — so what is pinned here is that both stores agree
        // they are off, whatever setFeatures was asked for.
        store.setFeatures(p.id, discussionsEnabled = true, messagesEnabled = true)
        // All three requirement flags together, each flipped off its default, so the
        // fix-version flag (LNL-134) is pinned to round-trip like the other two.
        store.setRequirements(p.id, requireLabel = true, requireComponent = true, requireFixedVersionOnResolve = true)
        val read = store.findById(p.id)!!
        assertEquals(false, read.discussionsEnabled)
        assertEquals(false, read.messagesEnabled)
        assertEquals(true, read.requireLabel)
        assertEquals(true, read.requireComponent)
        assertEquals(true, read.requireFixedVersionOnResolve)
    }

    @Test
    fun `the two board-display flags flip together and default off`() = runBlocking {
        val p = insertProject()
        // Default off — the board hid the author and showed the number before either
        // flag existed (LNL-157, LNL-194).
        assertEquals(false, store.findById(p.id)!!.showIssueAuthor)
        assertEquals(false, store.findById(p.id)!!.hideIssueNumbers)
        store.setBoardDisplay(p.id, showIssueAuthor = true, hideIssueNumbers = true)
        assertEquals(true, store.findById(p.id)!!.showIssueAuthor)
        assertEquals(true, store.findById(p.id)!!.hideIssueNumbers)
        // Independently, so a write of the pair cannot be passing one value twice.
        store.setBoardDisplay(p.id, showIssueAuthor = false, hideIssueNumbers = true)
        assertEquals(false, store.findById(p.id)!!.showIssueAuthor)
        assertEquals(true, store.findById(p.id)!!.hideIssueNumbers)
    }

    /**
     * A **fresh** project has already decided its board display; only a migrated one
     * has not.
     *
     * The three-valued column is the startup copy's marker — see
     * copyBoardDisplayFromOwners — and a new board has no old per-user preference to
     * copy, so `insert` must settle it rather than leaving a row the copy would visit.
     * Pinned in the contract because the two backends express "not yet decided"
     * differently: a NULL column and an absent document field.
     */
    @Test
    fun `a freshly inserted project has already decided its board display`() = runBlocking {
        val p = insertProject()
        assertEquals(false, store.findById(p.id)!!.hideIssueNumbersStored)
        assertEquals(false, p.hideIssueNumbersStored, "the record `insert` returns must agree with the row")
    }

    @Test
    fun `the repository config is stored and read back, and is empty by default`() = runBlocking {
        val p = insertProject()
        // Default: a config with nothing linked (null repository, no token), not null.
        assertNull(store.repositoryConfig(p.id)?.repository, "no repository is linked by default")
        val config = RepositoryConfig(RepositoryRef("soderbjorn", "lunicle"), TokenSource.Env("LUNICLE_GITHUB_TOKEN_X"))
        store.setRepositoryConfig(p.id, config)
        assertEquals(config, store.repositoryConfig(p.id))
    }

    @Test
    fun `delete removes the project`() = runBlocking {
        val p = insertProject()
        store.delete(p.id)
        assertNull(store.findById(p.id))
    }
}
