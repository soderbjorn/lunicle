/**
 * The email-code contract, run against the SQLite reference implementation.
 *
 * The assertions live in [EmailCodeStoreContract]; this file wires SQLite to them — a
 * temp database, a real [se.soderbjorn.lunicle.EmailCodeStore] on a controllable clock,
 * and a user seeded through the same [se.soderbjorn.lunicle.UserStore] production uses,
 * because `email_codes.user_id` is a foreign key into `users` and an address-change code
 * names a real account.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.UserStore
import se.soderbjorn.lunicle.clientserver.AuthProvider

class SqlDelightEmailCodeStoreContractTest : EmailCodeStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private var clockMillis = 0L
    private var seq = 0

    private val users = UserStore(db)

    override val store: EmailCodeStore =
        se.soderbjorn.lunicle.EmailCodeStore(db, now = { clockMillis })

    override fun advanceTime(millis: Long) {
        clockMillis += millis
    }

    override fun currentTime(): Long = clockMillis

    override suspend fun userId(): Long {
        val n = seq++
        return users.upsert(ProviderIdentity(AuthProvider.GITHUB, "ec-$n", "User $n", null)).id
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }
}
