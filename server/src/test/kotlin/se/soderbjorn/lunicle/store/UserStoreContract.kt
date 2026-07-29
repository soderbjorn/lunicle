/**
 * The behaviour every [UserStore] implementation must exhibit.
 *
 * The centrepiece is identity keying — the ticket's "legacy record missing a new
 * field" parity case. A returning user is found by **verified e-mail** first, so
 * signing in a different way but proving the same address reunites them with their
 * existing row; an account with no verified address falls back to its
 * `(provider, provider_id)` pair. Both are pinned here, plus the first-user-is-
 * admin rule and the round-trips of the mutable fields.
 *
 * [UserStore.findExisting] is the same keying asked as a question (LNL-192), and it is
 * pinned beside `upsert` rather than on its own, because the two disagreeing is the
 * only way it can be wrong: admission consults it to learn whether a sign-in would
 * create a row, and an answer that differed from what `upsert` then did would refuse
 * exactly the returning people the keying exists to reunite.
 *
 * No backend seeding hook is needed: [UserStore.upsert] is itself the way a user
 * comes to exist. Each test starts from an empty backend (a fresh fixture), so the
 * first upsert in each is the instance's first account.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.clientserver.AuthProvider

abstract class UserStoreContract {
    protected abstract val store: UserStore

    private fun identity(providerId: String, email: String?, provider: AuthProvider = AuthProvider.GITHUB) =
        ProviderIdentity(provider, providerId, "User $providerId", email)

    @Test
    fun `the first account created is the instance admin, later ones are not`() = runBlocking {
        val first = store.upsert(identity("gh-1", null))
        val second = store.upsert(identity("gh-2", null))
        assertTrue(first.isInstanceAdmin, "the first user to sign in is the instance admin")
        assertFalse(second.isInstanceAdmin)
    }

    @Test
    fun `the same provider identity resolves to the same account`() = runBlocking {
        val a = store.upsert(identity("gh-1", null))
        val b = store.upsert(identity("gh-1", null))
        assertEquals(a.id, b.id)
    }

    @Test
    fun `a returning user is reunited by verified e-mail across a different provider`() = runBlocking {
        val original = store.upsert(identity("gh-1", "alice@example.com", AuthProvider.GITHUB))
        val returning = store.upsert(identity("goog-1", "alice@example.com", AuthProvider.GOOGLE))
        assertEquals(original.id, returning.id, "one address must not produce two accounts")
    }

    @Test
    fun `e-mail keying folds case and whitespace`() = runBlocking {
        val a = store.upsert(identity("gh-1", "Alice@Example.com "))
        val b = store.upsert(identity("goog-1", "alice@example.com", AuthProvider.GOOGLE))
        assertEquals(a.id, b.id)
    }

    @Test
    fun `unkeyed accounts fall back to the provider pair`() = runBlocking {
        val a = store.upsert(identity("sub-a", null, AuthProvider.GOOGLE))
        val b = store.upsert(identity("sub-b", null, AuthProvider.GOOGLE))
        assertNotEquals(a.id, b.id, "two addressless accounts are distinct")
        val aAgain = store.upsert(identity("sub-a", null, AuthProvider.GOOGLE))
        assertEquals(a.id, aAgain.id, "an addressless account still finds its own row")
    }

    @Test
    fun `findById and selectAll reflect what was created`() = runBlocking {
        val a = store.upsert(identity("gh-1", null))
        val b = store.upsert(identity("gh-2", null))
        assertEquals(a.id, store.findById(a.id)?.id)
        assertEquals(setOf(a.id, b.id), store.selectAll().map { it.id }.toSet())
    }

    // ── findExisting: the same keying, asked rather than acted on (LNL-192) ──

    @Test
    fun `findExisting answers null for an identity that would create an account`() = runBlocking {
        store.upsert(identity("gh-1", "alice@example.com"))
        assertEquals(null, store.findExisting(identity("gh-2", "bob@example.com")))
    }

    /**
     * Both keys, in `upsert`'s order — and the assertion is against what `upsert`
     * itself then does, so the two cannot drift apart on either backend.
     */
    @Test
    fun `findExisting reaches an account by verified e-mail and by the provider pair`() = runBlocking {
        val keyed = store.upsert(identity("gh-1", "alice@example.com", AuthProvider.GITHUB))
        val unkeyed = store.upsert(identity("gh-2", null))

        val byEmail = identity("goog-9", "alice@example.com", AuthProvider.GOOGLE)
        assertEquals(keyed.id, store.findExisting(byEmail)?.id, "a different provider proving the address")
        assertEquals(store.upsert(byEmail).id, store.findExisting(byEmail)?.id, "findExisting disagreed with upsert")

        val byPair = identity("gh-2", null)
        assertEquals(unkeyed.id, store.findExisting(byPair)?.id, "an addressless account by its provider pair")
        assertEquals(store.upsert(byPair).id, store.findExisting(byPair)?.id, "findExisting disagreed with upsert")
    }

    @Test
    fun `findExisting folds case and whitespace like the keying does`() = runBlocking {
        val user = store.upsert(identity("gh-1", "alice@example.com"))
        assertEquals(user.id, store.findExisting(identity("gh-9", "  Alice@Example.COM  "))?.id)
    }

    @Test
    fun `the mutable fields round-trip`() = runBlocking {
        val user = store.upsert(identity("gh-1", null))
        store.setDisplayName(user.id, "Ada")
        store.setEmail(user.id, "ada@example.com", isVerified = true)
        store.setMcpEnabled(user.id, true)

        val reread = store.findById(user.id)!!
        assertEquals("Ada", reread.displayNameOverride)
        assertEquals("ada@example.com", reread.email)
        assertTrue(reread.isEmailVerified)
        assertTrue(reread.isMcpEnabled)

        store.setDisplayName(user.id, "   ")
        assertEquals(null, store.findById(user.id)?.displayNameOverride, "a blank override clears it")
    }
}
