/**
 * The Session contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.SessionStore]).
 *
 * The assertions live in [SessionStoreContract]; this file wires SQLite — a temp
 * database, the real session store over it, and real users through [UserStore] so
 * that [se.soderbjorn.lunicle.SessionStore.lookup]'s JOIN back to `users` resolves
 * exactly as it does on the volume.
 *
 * The store is built over a mutable clock so the sweep test can age a session a
 * month without waiting one; [advancePastSessionLifetime] moves it past the store's
 * thirty-day lifetime. The concrete gateway shares its simple name with the
 * [SessionStore] interface, so it is constructed by its fully-qualified name.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.UserStore
import se.soderbjorn.lunicle.clientserver.AuthProvider

class SqlDelightSessionStoreContractTest : SessionStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val users = UserStore(db)

    // A clock the test controls, so the sweep can age a session past its lifetime.
    private var clock = 1_700_000_000_000L
    private var seq = 0

    override val store: SessionStore = se.soderbjorn.lunicle.SessionStore(db, now = { clock })

    override suspend fun newUser(): Long {
        val n = seq++
        return users.upsert(ProviderIdentity(AuthProvider.GITHUB, "sub-$n", "User $n", "user$n@example.com")).id
    }

    // Thirty-day lifetime plus a day; SESSION_LIFETIME_MILLIS is private to the store.
    override fun advancePastSessionLifetime() {
        clock += 31L * 24 * 60 * 60 * 1000
    }

    @AfterTest
    fun tearDown() = fixture.close()
}
