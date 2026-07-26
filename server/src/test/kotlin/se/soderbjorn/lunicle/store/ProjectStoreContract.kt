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
    private suspend fun insertProject(public: Boolean = false) =
        store.insert("Project ${seq}", "PR${seq++}", public)

    @Test
    fun `an inserted project round-trips through findById`() = runBlocking {
        val created = store.insert("Lunamux", "LMX", isPublic = true)
        val read = store.findById(created.id)!!
        assertEquals("Lunamux", read.name)
        assertEquals("LMX", read.namePrefix)
        assertEquals(true, read.isPublic)
    }

    @Test
    fun `the signed-in-visibility flag round-trips and defaults off`() = runBlocking {
        // Default off, the tier a project has until its owner opts in (LNL-138).
        val default = store.insert("Members Only", "MEM", isPublic = false)
        assertEquals(false, store.findById(default.id)!!.visibleToAllSignedIn)
        // Set on insert, read back on and independent of is_public.
        val opened = store.insert("Browsable", "BRW", isPublic = false, visibleToAllSignedIn = true)
        val read = store.findById(opened.id)!!
        assertEquals(true, read.visibleToAllSignedIn)
        assertEquals(false, read.isPublic)
    }

    @Test
    fun `update writes the signed-in-visibility flag alongside is_public`() = runBlocking {
        val p = store.insert("Toggling", "TGL", isPublic = false, visibleToAllSignedIn = false)
        store.update(p.id, "Toggling", "TGL", isPublic = false, visibleToAllSignedIn = true)
        assertEquals(true, store.findById(p.id)!!.visibleToAllSignedIn)
        store.update(p.id, "Toggling", "TGL", isPublic = true, visibleToAllSignedIn = false)
        val read = store.findById(p.id)!!
        assertEquals(false, read.visibleToAllSignedIn)
        assertEquals(true, read.isPublic)
    }

    @Test
    fun `findByName finds a project and misses cleanly`() = runBlocking {
        val created = store.insert("Findable", "FND", isPublic = false)
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
        // Defaults: features on, requirements off.
        store.setFeatures(p.id, discussionsEnabled = false, messagesEnabled = false)
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
    fun `the show-issue-author display flag flips and defaults off`() = runBlocking {
        val p = insertProject()
        // Default off — the board hid the author before this flag existed (LNL-157).
        assertEquals(false, store.findById(p.id)!!.showIssueAuthor)
        store.setShowIssueAuthor(p.id, true)
        assertEquals(true, store.findById(p.id)!!.showIssueAuthor)
        store.setShowIssueAuthor(p.id, false)
        assertEquals(false, store.findById(p.id)!!.showIssueAuthor)
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
