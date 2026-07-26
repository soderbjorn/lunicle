/**
 * The Project contract, run against the SQLite gateway reference implementation
 * ([se.soderbjorn.lunicle.ProjectStore]).
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest

class SqlDelightProjectStoreContractTest : ProjectStoreContract() {
    private val fixture = SqlDelightContractFixture()

    override val store: ProjectStore = se.soderbjorn.lunicle.ProjectStore(fixture.database)

    @AfterTest
    fun tearDown() = fixture.close()
}
