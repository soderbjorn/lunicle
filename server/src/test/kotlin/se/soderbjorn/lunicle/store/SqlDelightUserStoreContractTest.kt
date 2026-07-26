/**
 * The User contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.UserStore]).
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest

class SqlDelightUserStoreContractTest : UserStoreContract() {
    private val fixture = SqlDelightContractFixture()

    override val store: UserStore = se.soderbjorn.lunicle.UserStore(fixture.database)

    @AfterTest
    fun tearDown() = fixture.close()
}
