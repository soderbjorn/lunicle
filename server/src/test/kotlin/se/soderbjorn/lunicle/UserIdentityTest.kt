/**
 * One human, one row — now that a verified e-mail is what says so.
 *
 * LNL-73 moved the account key from `(provider, provider_id)` to the address, and
 * every failure that change can produce is silent. A find-or-create that misses
 * makes a second account, which looks like a working sign-in until the person
 * notices their issues are gone. A find-or-create that matches too eagerly puts
 * somebody in a stranger's row, which looks like a working sign-in until it
 * doesn't. Neither throws.
 *
 * So the assertions here are all about *identity of the row that comes back*, and
 * the interesting half is the negative space: an address Google would not confirm
 * must not become a key, and two accounts that both have no key must not collapse
 * into one.
 *
 * @see UserStore.upsert
 * @see normalizeEmail
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserIdentityTest {
    private val file: File = Files.createTempFile("lunicle-identity", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database
    private val users = UserStore(database)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── Normalization, which is the whole of the spelling policy ────────────

    @Test
    fun `trim and lowercase, and nothing else`() {
        assertEquals("alice@example.com", normalizeEmail("  Alice@Example.COM  "))
        assertNull(normalizeEmail("   "), "Blank is not an address.")
        assertNull(normalizeEmail(null))

        // The two canonicalizations deliberately NOT done. Gmail treats these as
        // one mailbox and plenty of hosts do not, and getting it wrong signs
        // somebody into a stranger's account — see normalizeEmail.
        assertNotEquals(
            normalizeEmail("a.b@example.com"),
            normalizeEmail("ab@example.com"),
            "Dots were canonicalized away, which is provider-specific and wrong for hosts that keep them.",
        )
        assertNotEquals(
            normalizeEmail("alice+tag@example.com"),
            normalizeEmail("alice@example.com"),
            "A +tag was stripped, which is provider-specific and wrong for hosts that keep it.",
        )
    }

    /**
     * Two spellings of one address are one account.
     *
     * The concrete bug this closes: nothing lowercased anywhere before LNL-73, and
     * the two write paths disagreed about trimming, so `Alice@X.com` and
     * `alice@x.com` were two rows — and would have been two accounts the moment
     * either was a key.
     */
    @Test
    fun `a differently-cased address resolves to the same row`(): Unit = runBlocking {
        val first = users.upsert(google("sub-1", "Alice", "Alice@Example.COM"))
        val second = users.upsert(google("sub-1", "Alice", "  alice@example.com "))
        assertEquals(first.id, second.id, "Two spellings of one address produced two accounts.")
        assertEquals("alice@example.com", second.email, "The address was not stored normalized.")
    }

    /**
     * The point of the re-key: a different sign-in method, the same row.
     *
     * A returning user arriving with a different `(provider, provider_id)` — which
     * is what the e-mail sign-in of LNL-74 does — must land in the account they
     * already have. Under the old key this created a second row, which is the
     * fragmentation this ticket exists to prevent.
     */
    @Test
    fun `a different provider with the same address reunites with the existing row`(): Unit = runBlocking {
        val original = users.upsert(google("sub-1", "Alice", "alice@example.com"))
        val returning = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-99", "Alice Elsewhere", "alice@example.com"),
        )
        assertEquals(original.id, returning.id, "The same human got a second account by signing in differently.")
    }

    /**
     * Finding a row by address does not rewrite how it was created.
     *
     * `provider` and `provider_id` stay as columns because they record provenance
     * and cross the wire as "Signed in via X". Overwriting them on every sign-in
     * would make that sentence describe the most recent method rather than the
     * account, and would quietly break the UNIQUE pair the unkeyed fallback needs.
     */
    @Test
    fun `a row found by address keeps its provenance and its own settings`(): Unit = runBlocking {
        val original = users.upsert(google("sub-1", "Alice", "alice@example.com"))
        users.setDisplayName(original.id, "Ali")
        users.setMcpEnabled(original.id, true)

        val returning = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-99", "Alice Elsewhere", "alice@example.com"),
        )
        assertEquals(AuthProvider.GOOGLE, returning.provider, "Provenance was rewritten by a later sign-in.")
        assertEquals("sub-1", returning.providerId, "The provider id was rewritten by a later sign-in.")
        assertEquals("Ali", returning.resolvedName, "The user's own display-name override was overwritten.")
        assertTrue(returning.isInstanceAdmin, "The admin bit was recomputed on a repeat sign-in.")
        assertTrue(returning.canUseMcp, "Signing in again reset the MCP switches.")
        // The provider's own name is refreshed, which is the one thing that should
        // follow — it is theirs to change.
        assertEquals("Alice Elsewhere", returning.providerName)
    }

    // ── The negative space: what must NOT become a key ──────────────────────

    /**
     * An address Google will not vouch for is not an identity.
     *
     * Google has always sent `email_verified` and nothing read it before LNL-73 —
     * `ignoreUnknownKeys` ate it silently. If it stays unread, typing a victim's
     * address into a fresh Google account is a way into their Lunicle row, and
     * nothing anywhere reports a problem.
     */
    @Test
    fun `an unverified provider address does not claim the identity`(): Unit = runBlocking {
        val real = users.upsert(google("sub-real", "Alice", "alice@example.com"))
        // The impostor's Google account says the same address and Google has not
        // confirmed it, so exchangeGoogleCode hands us a null e-mail.
        val impostor = users.upsert(
            ProviderIdentity(AuthProvider.GOOGLE, "sub-impostor", "Alice", email = null),
        )
        assertNotEquals(real.id, impostor.id, "An unconfirmed address was accepted as somebody else's identity.")
        assertNull(impostor.email, "An unconfirmed address was stored as if it were known.")
        assertFalse(impostor.isEmailVerified)
    }

    /**
     * A Google account with no usable address still works, and comes back to its
     * own row.
     *
     * It has no key, so the fallback to `(provider, provider_id)` is the only
     * thing that can find it. Two of them must coexist rather than colliding on
     * NULL — which is what `WHERE email IS NOT NULL` on the unique index is for.
     */
    @Test
    fun `unkeyed accounts coexist and each finds its own row again`(): Unit = runBlocking {
        val first = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "sub-a", "Google user", null))
        val second = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "sub-b", "Google user", null))
        assertNotEquals(first.id, second.id, "Two accounts with no address collapsed into one.")

        val firstAgain = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "sub-a", "Google user", null))
        assertEquals(first.id, firstAgain.id, "An unkeyed account could not find its own row on a repeat sign-in.")
    }

    /**
     * An account that later proves an address keeps its row and gains the key.
     *
     * The migration path for everybody who exists today: nothing was verified
     * before LNL-71, so every row starts unkeyed-in-spirit and is corrected the
     * first time its owner proves something.
     */
    @Test
    fun `an unkeyed account gains its key without changing rows`(): Unit = runBlocking {
        val before = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "sub-a", "Alice", null))
        assertNull(before.email)

        val after = users.upsert(google("sub-a", "Alice", "alice@example.com"))
        assertEquals(before.id, after.id, "Proving an address moved the user to a new account.")
        assertEquals("alice@example.com", after.email)
        assertTrue(after.isEmailVerified)
    }

    // ── First user wins ─────────────────────────────────────────────────────

    /**
     * The instance-admin rule survives the rewrite.
     *
     * It lives as a subquery inside the INSERT rather than as a `count` in Kotlin,
     * so that it stays atomic with the row it decides about. A find-or-create that
     * reimplemented it outside the statement would be the classic two-people-at-once
     * bug, and on a fresh instance it would hand out a second admin.
     */
    @Test
    fun `the first account is the admin and the second is not`(): Unit = runBlocking {
        val first = users.upsert(google("sub-1", "First", "first@example.com"))
        val second = users.upsert(google("sub-2", "Second", "second@example.com"))
        assertTrue(first.isInstanceAdmin, "The first account did not become the instance admin.")
        assertFalse(second.isInstanceAdmin, "A second account became an admin too.")

        // And signing in again does not recompute it in either direction.
        assertTrue(users.upsert(google("sub-1", "First", "first@example.com")).isInstanceAdmin)
        assertFalse(users.upsert(google("sub-2", "Second", "second@example.com")).isInstanceAdmin)
    }

    // ── Uniqueness is real, at the storage layer ────────────────────────────

    /**
     * Two accounts cannot hold one address, and the store says so by failing.
     *
     * The route that could hit this catches it and answers 409 — see the confirm
     * endpoint. What is pinned here is that the database is the thing enforcing
     * it, rather than a check somewhere that a later caller could route around.
     */
    @Test
    fun `a second account cannot take an address that is already spoken for`(): Unit = runBlocking {
        val alice = users.upsert(google("sub-1", "Alice", "alice@example.com"))
        val bob = users.upsert(google("sub-2", "Bob", "bob@example.com"))
        assertNotEquals(alice.id, bob.id)

        val stolen = runCatching { users.setEmail(bob.id, "alice@example.com", isVerified = true) }
        assertTrue(stolen.isFailure, "Two accounts were allowed to hold one address.")
        assertEquals("bob@example.com", users.findById(bob.id)?.email, "The refused write partially applied.")
    }

    /** Clearing an address gives up the key, and the flag goes with it. */
    @Test
    fun `clearing an address releases the key`(): Unit = runBlocking {
        val alice = users.upsert(google("sub-1", "Alice", "alice@example.com"))
        val bob = users.upsert(google("sub-2", "Bob", "bob@example.com"))

        users.setEmail(alice.id, null, isVerified = false)
        assertNull(users.findById(alice.id)?.email)
        assertFalse(users.findById(alice.id)?.isEmailVerified == true, "A null address is still flagged verified.")

        users.setEmail(bob.id, "alice@example.com", isVerified = true)
        assertEquals("alice@example.com", users.findById(bob.id)?.email, "A released address could not be taken.")
    }

    /** A verified address for a Google identity. */
    private fun google(sub: String, name: String, email: String?) =
        ProviderIdentity(AuthProvider.GOOGLE, sub, name, email)
}
