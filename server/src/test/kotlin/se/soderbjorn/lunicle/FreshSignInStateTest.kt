/**
 * What a completed sign-in tells the client about impersonation.
 *
 * ── The bug this file is here to keep fixed ─────────────────────────────────
 *
 * [SessionState] carries three impersonation fields, and only one of them —
 * `isImpersonating` — is about *doing* it. `canImpersonate` and
 * `impersonatableUsers` are about *being an admin*, which a returning admin is
 * from the instant the exchange finds their row.
 *
 * The sign-in route used to answer with the plain session builder, which leaves
 * all three at their defaults, on the reasoning that a session one request old is
 * not impersonating anybody. True of the first field and wrong about the other
 * two: the effect was that an admin who signed out and back in lost the
 * "Impersonate" item from their account menu until they reloaded the page, since
 * nothing else re-fetches the session. That is LNL-42.
 *
 * The interesting property is therefore about a *state*, not about a route, and
 * that is what is tested here — the Google exchange around it needs a live token
 * endpoint to reach, and mocking one would test the mock. The three assertions
 * below are exactly the three fields, because the failure was one field being
 * right and the other two wrong.
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreshSignInStateTest {
    private val file: File = Files.createTempFile("lunicle-signin", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val users = UserStore(opened.database)

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
     * The admin signs in and can immediately reach the impersonation menu.
     *
     * `isImpersonating` stays false — that part of the old reasoning was always
     * right, and a sign-in that claimed otherwise would put "Stop impersonating"
     * in front of somebody who is plainly themselves.
     */
    @Test
    fun `a returning admin is told they may impersonate`(): Unit = runBlocking {
        // Whoever signs in first is the instance admin. See Users.sq's upsert.
        val admin = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-admin", "Admin", null))
        users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ada", "Ada", null))

        val state = freshSignInState(admin, users, config)

        assertTrue(state.canImpersonate, "The admin lost the impersonation menu by signing back in.")
        assertFalse(state.isImpersonating, "A one-request-old session cannot be wearing anybody's face.")
        // By name, which is `selectAll`'s order — the menu is a list a human reads.
        assertEquals(
            listOf("Ada", "Admin"),
            state.impersonatableUsers.map { it.name },
            "The menu came back empty, so there is nobody to become.",
        )
    }

    /**
     * The admin's own row is marked, rather than left out.
     *
     * The view renders it disabled — see SignInView — so the list matches the user
     * table the admin is looking at. `isSelf` is what carries that, and it is
     * computed against the account that just signed in, which is the one place a
     * fresh sign-in could plausibly get it wrong: there is no impersonation in
     * flight, so "the real user" and "the effective user" are the same person and
     * a mix-up would not show up anywhere else.
     */
    @Test
    fun `the signed-in admin's own row is marked as theirs`(): Unit = runBlocking {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-admin", "Admin", null))
        users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ada", "Ada", null))

        val state = freshSignInState(admin, users, config)

        assertEquals(
            mapOf("Admin" to true, "Ada" to false),
            state.impersonatableUsers.associate { it.name to it.isSelf },
        )
    }

    /**
     * An ordinary account is told nothing, and is not handed the roster.
     *
     * The list is names and ids of every account on the instance. It is not a
     * secret the way the admin directory's e-mail addresses are, but it is not
     * something a signed-in stranger has any use for either — and the route that
     * would act on it refuses them regardless, so sending it would only be an
     * affordance for a refusal.
     */
    @Test
    fun `an ordinary account is told nothing about impersonation`(): Unit = runBlocking {
        users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-admin", "Admin", null))
        val ordinary = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ada", "Ada", null))
        assertFalse(ordinary.isInstanceAdmin, "The fixture's second account is somehow an admin.")

        val state = freshSignInState(ordinary, users, config)

        assertFalse(state.canImpersonate)
        assertTrue(state.impersonatableUsers.isEmpty(), "A non-admin was handed the account roster.")
    }
}
