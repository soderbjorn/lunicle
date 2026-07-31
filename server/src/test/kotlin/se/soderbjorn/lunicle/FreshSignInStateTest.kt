/**
 * What a completed sign-in tells the client about impersonation.
 *
 * ── The bug this file is here to keep fixed ─────────────────────────────────
 *
 * `isImpersonating` is about *doing* it; `canImpersonate` is about *being
 * entitled*, which a returning owner is from the instant the exchange finds their
 * row.
 *
 * The sign-in route used to answer with the plain session builder, which leaves
 * both at their defaults, on the reasoning that a session one request old is not
 * impersonating anybody. True of the first and wrong about the second: the effect
 * was that somebody who signed out and back in lost the "Impersonate…" item from
 * their account menu until they reloaded the page, since nothing else re-fetches
 * the session. That is LNL-42.
 *
 * The interesting property is therefore about a *state*, not about a route, and
 * that is what is tested here — the Google exchange around it needs a live token
 * endpoint to reach, and mocking one would test the mock.
 *
 * ── What LNL-197 changed here ───────────────────────────────────────────────
 *
 * The entitlement is **ownership**, not the administrator flag, so every fixture
 * below has to seat an owner — and the file gained the case that would otherwise
 * only have been caught in a browser: an *administrator who is not the owner* is
 * told no. The old fixtures could not distinguish the two, because on a fresh
 * instance the first account is both.
 *
 * ── And what the deploy gate changed ────────────────────────────────────────
 *
 * A second term. `canImpersonate` is now ownership **and** the deployment having
 * `LUNICLE_ENABLE_OWNER_IMPERSONATION` on, so the same owner is told yes on an
 * armed instance and no on an ordinary one. That is the state almost every
 * deployment is in, which is why it is asserted first here.
 *
 * The address list these tests used to check has gone with the menu that showed
 * it. Nothing ships a directory to the browser any more: the owner is signed out
 * and types whichever address they want at a genuine sign-in, so there is no list
 * to be right about.
 *
 * @see freshSignInState
 * @see authRoutes
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
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
     * The owner signs in and can immediately reach the impersonation item.
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
        seatInstanceOwner(users, instanceSettings)

        val state = freshSignInState(owner, config, instanceSettings, isImpersonationEnabled = true)

        assertTrue(state.canImpersonate, "The owner lost the impersonation item by signing back in.")
        assertFalse(state.isImpersonating, "A one-request-old session cannot be wearing anybody's face.")
    }

    /**
     * ...but not on a deployment that has not switched the facility on.
     *
     * The default state of every instance, and the term most easily lost: an owner
     * is an owner whatever the environment says, so an entitlement check that
     * forgot the gate would pass every other test in this file and put an item in
     * the menu whose route answers 403.
     */
    @Test
    fun `the same owner is told nothing where the deployment has it switched off`(): Unit = runBlocking {
        val owner = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-owner", "Owner", "owner@example.com"))
        seatInstanceOwner(users, instanceSettings)

        val state = freshSignInState(owner, config, instanceSettings, isImpersonationEnabled = false)

        assertFalse(state.canImpersonate, "An unarmed deployment offered its owner the impersonation item.")
    }

    /**
     * An **administrator who is not the owner** is told nothing (LNL-197).
     *
     * The case the old fixtures structurally could not reach, and the one most likely
     * to be left with a stale affordance: an administrator sees plenty of other
     * instance-wide surfaces, so the item appearing for them would look right. It is
     * not — impersonation signs you in as anybody, and that is the owner's alone.
     */
    @Test
    fun `an administrator who does not own the instance is refused the item`(): Unit = runBlocking {
        val owner = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-owner", "Owner", "owner@example.com"))
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", "admin@example.com"))
        users.setInstanceAdmin(admin.id, true)
        instanceSettings.setOwnerUserId(owner.id)
        val promoted = requireNotNull(users.findById(admin.id))
        assertTrue(promoted.isInstanceAdmin, "The fixture failed to promote the second account.")

        val state = freshSignInState(promoted, config, instanceSettings, isImpersonationEnabled = true)

        assertFalse(state.canImpersonate, "An administrator who does not own the instance was offered the item.")
    }

    /** An ordinary account is told nothing, on an armed instance or otherwise. */
    @Test
    fun `an ordinary account is told nothing about impersonation`(): Unit = runBlocking {
        users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-owner", "Owner", "owner@example.com"))
        val ordinary = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ada", "Ada", "ada@example.com"))
        seatInstanceOwner(users, instanceSettings)
        assertFalse(ordinary.isInstanceAdmin, "The fixture's second account is somehow an admin.")

        val state = freshSignInState(ordinary, config, instanceSettings, isImpersonationEnabled = true)

        assertFalse(state.canImpersonate)
    }
}
