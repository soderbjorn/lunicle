/**
 * The Priority contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.PriorityStore]).
 *
 * A bare project row through the low-level [se.soderbjorn.lunicle.ProjectStore] (no
 * vocabulary seeding), so the project starts empty of priorities — which is what
 * lets the [PriorityStore.defaultForProject] "middle of the scale" test control the
 * whole scale. The concrete gateway is constructed by its fully-qualified name,
 * sharing its simple name with the [PriorityStore] interface.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest

class SqlDelightPriorityStoreContractTest : PriorityStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = se.soderbjorn.lunicle.ProjectStore(db)
    private var seq = 0

    override val store: PriorityStore = se.soderbjorn.lunicle.PriorityStore(db)

    override suspend fun newProject(): Long =
        projects.insert("Project $seq", "PR${seq++}").id

    @AfterTest
    fun tearDown() = fixture.close()
}
