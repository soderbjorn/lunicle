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
        assertTrue(first.isSysAdmin, "the first user to sign in is the instance admin")
        assertFalse(second.isSysAdmin)
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

    @Test
    fun `the mutable fields round-trip`() = runBlocking {
        val user = store.upsert(identity("gh-1", null))
        store.setDisplayName(user.id, "Ada")
        store.setEmail(user.id, "ada@example.com", isVerified = true)
        store.setMcpEnabled(user.id, true)
        store.setMcpAllowed(user.id, true)

        val reread = store.findById(user.id)!!
        assertEquals("Ada", reread.displayNameOverride)
        assertEquals("ada@example.com", reread.email)
        assertTrue(reread.isEmailVerified)
        assertTrue(reread.isMcpEnabled)
        assertTrue(reread.isMcpAllowed)

        store.setDisplayName(user.id, "   ")
        assertEquals(null, store.findById(user.id)?.displayNameOverride, "a blank override clears it")
    }
}
