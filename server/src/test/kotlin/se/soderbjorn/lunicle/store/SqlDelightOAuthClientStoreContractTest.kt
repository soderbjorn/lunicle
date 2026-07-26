/**
 * The OAuth client contract, run against the SQLite reference implementation.
 *
 * The assertions live in [OAuthClientStoreContract]; this file wires SQLite to
 * them — a temp database and a real [se.soderbjorn.lunicle.OAuthClientStore] over
 * it, built on a controllable clock so the stale-sweep can be driven past its
 * seven-day cutoff without waiting.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest

class SqlDelightOAuthClientStoreContractTest : OAuthClientStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private var clockMillis = 0L

    override val store: OAuthClientStore =
        se.soderbjorn.lunicle.OAuthClientStore(db, now = { clockMillis })

    override fun advanceTime(millis: Long) {
        clockMillis += millis
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }
}
