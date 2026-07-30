/**
 * What a completed sign-in tells the client about impersonation.
 *
 * ── The bug this file is here to keep fixed ─────────────────────────────────
 *
 * [SessionState] carries three impersonation fields, and only one of them —
 * `isImpersonating` — is about *doing* it. `canImpersonate` and
 * `impersonatableAddresses` are about *being entitled*, which a returning owner is
 * from the instant the exchange finds their row.
 *
 * The sign-in route used to answer with the plain session builder, which leaves
 * all three at their defaults, on the reasoning that a session one request old is
 * not impersonating anybody. True of the first field and wrong about the other
 * two: the effect was that somebody who signed out and back in lost the
 * "Impersonate" item from their account menu until they reloaded the page, since
 * nothing else re-fetches the session. That is LNL-42.
 *
 * The interesting property is therefore about a *state*, not about a route, and
 * that is what is tested here — the Google exchange around it needs a live token
 * endpoint to reach, and mocking one would test the mock. The three assertions
 * below are exactly the three fields, because the failure was one field being
 * right and the other two wrong.
 *
 * ── What LNL-197 changed here ───────────────────────────────────────────────
 *
 * The entitlement is **ownership**, not the administrator flag, so every fixture
 * below has to seat an owner — and the file gained the case that would otherwise
 * only have been caught in a browser: an *administrator who is not the owner* is
 * told no. The old fixtures could not distinguish the two, because on a fresh
 * instance the first account is both.
 *
 * @see freshSignInState
 * @see authRoutes
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AddressStanding
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreshSignInStateTest {
    private val file: File = Files.createTempFile("lunicle-signin", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val users = UserStore(opened.database)
    private val instanceSettings = InstanceSettingsStore(opened.database)

    /** No provider configured; nothing here asks about one. */
    private val config = OAuthConfig(google = null)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    /**
     * The owner signs in and can immediately reach the impersonation menu.
     *
     * `isImpersonating` stays false — that part of the old reasoning was always
     * right, and a sign-in that claimed otherwise would put "Stop impersonating"
     * in front of somebody who is plainly themselves.
     */
    @Test
    fun `a returning owner is told they may impersonate`(): Unit = runBlocking {
        // Whoever signs in first is the instance admin, and the boot seat makes them
        // the owner. See Users.sq's upsert and seatInstanceOwner.
        val owner = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-owner", "Owner", "owner@example.com"))
        users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ada", "Ada", "ada@example.com"))
        seatInstanceOwner(users, instanceSettings)

        val state = freshSignInState(owner, users, config, instanceSettings)

        assertTrue(state.canImpersonate, "The owner lost the impersonation menu by signing back in.")
        assertFalse(state.isImpersonating, "A one-request-old session cannot be wearing anybody's face.")
        // By address, sorted, because that is what the menu lists now — the model keys
        // on addresses and a list of display names would be a list of the wrong thing.
        assertEquals(
            listOf("ada@example.com", "owner@example.com"),
            state.impersonatableAddresses.map { it.email },
            "The menu came back empty, so there is nobody to become.",
        )
    }

    /**
     * The owner's own address is marked, rather than left out.
     *
     * The view renders it disabled — see SignInView — so the list matches the account
     * directory somebody is looking at. [AddressStanding.SELF] is what carries that,
     * and it is computed against the account that just signed in, which is the one
     * place a fresh sign-in could plausibly get it wrong: there is no impersonation in
     * flight, so "the real user" and "the effective user" are the same person and a
     * mix-up would not show up anywhere else.
     */
    @Test
    fun `the signed-in owner's own address is marked as theirs`(): Unit = runBlocking {
        val owner = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-owner", "Owner", "owner@example.com"))
        users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ada", "Ada", "ada@example.com"))
        seatInstanceOwner(users, instanceSettings)

        val state = freshSignInState(owner, users, config, instanceSettings)

        assertEquals(
            // Ada is a member, not never-arrived: `upsert` *is* a sign-in, so her row has
            // turned up — and this install has no domain, so nobody is staff.
            mapOf("owner@example.com" to AddressStanding.SELF, "ada@example.com" to AddressStanding.MEMBER),
            state.impersonatableAddresses.associate { it.email to it.standing },
        )
    }

    /**
     * An account that never arrived reads as such, and one that did reads as its tier.
     *
     * The two facts the row is *for*: `signed_in_at` is null for somebody who was added
     * and has not turned up, and null wins over the tier because the tier is derivable
     * from the address on screen and this is not. The domain makes the split real — an
     * unbranded install has no staff tier at all, so a test without one could not tell
     * a correct staff answer from a hardcoded member.
     */
    @Test
    fun `the list reports staff, member and never-arrived apart`(): Unit = runBlocking {
        val owner = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-owner", "Owner", "owner@example.com"))
        seatInstanceOwner(users, instanceSettings)
        // Signed in, at the deployment's own domain.
        users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-staff", "Staff", "staff@acme.com"))
        // Signed in, from outside it.
        users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-out", "Outsider", "outsider@example.com"))
        // Added and never arrived, at the staff domain — the row that proves "not signed
        // in" is reported instead of "staff", not merely instead of nothing.
        users.addByEmail("pending@acme.com", UserKind.STAFF)

        val state = freshSignInState(owner, users, config, instanceSettings, domain = "acme.com")

        assertEquals(
            mapOf(
                "outsider@example.com" to AddressStanding.MEMBER,
                "owner@example.com" to AddressStanding.SELF,
                "pending@acme.com" to AddressStanding.NOT_SIGNED_IN,
                "staff@acme.com" to AddressStanding.STAFF,
            ),
            state.impersonatableAddresses.associate { it.email to it.standing },
        )
    }

    /**
     * An **administrator who is not the owner** is told nothing (LNL-197).
     *
     * The case the old fixtures structurally could not reach, and the one most likely
     * to be left with a stale affordance: an administrator sees plenty of other
     * instance-wide surfaces, so the menu appearing for them would look right. It is
     * not — impersonation carries writes, and that is the owner's alone.
     */
    @Test
    fun `an administrator who does not own the instance is refused the menu`(): Unit = runBlocking {
        val owner = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-owner", "Owner", "owner@example.com"))
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", "admin@example.com"))
        users.setInstanceAdmin(admin.id, true)
        instanceSettings.setOwnerUserId(owner.id)
        val promoted = requireNotNull(users.findById(admin.id))
        assertTrue(promoted.isInstanceAdmin, "The fixture failed to promote the second account.")

        val state = freshSignInState(promoted, users, config, instanceSettings)

        assertFalse(state.canImpersonate, "An administrator who does not own the instance was offered the menu.")
        assertTrue(state.impersonatableAddresses.isEmpty(), "An administrator was handed the address list.")
    }

    /**
     * An ordinary account is told nothing, and is not handed the roster.
     *
     * The list is every address on the instance. That is a directory, and it goes to
     * one person — the owner, who can read the account directory anyway. The route that
     * would act on it refuses everybody else regardless, so sending it would only be an
     * affordance for a refusal.
     */
    @Test
    fun `an ordinary account is told nothing about impersonation`(): Unit = runBlocking {
        users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-owner", "Owner", "owner@example.com"))
        val ordinary = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ada", "Ada", "ada@example.com"))
        seatInstanceOwner(users, instanceSettings)
        assertFalse(ordinary.isInstanceAdmin, "The fixture's second account is somehow an admin.")

        val state = freshSignInState(ordinary, users, config, instanceSettings)

        assertFalse(state.canImpersonate)
        assertTrue(state.impersonatableAddresses.isEmpty(), "A non-owner was handed the account roster.")
    }
}
