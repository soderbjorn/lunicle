/**
 * The Component contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.ComponentStore]).
 *
 * A bare project row through the low-level [se.soderbjorn.lunicle.ProjectStore] (no
 * vocabulary seeding), so the project starts empty of components — the parity the
 * contract needs. The concrete gateway is constructed by its fully-qualified name,
 * sharing its simple name with the [ComponentStore] interface.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest

class SqlDelightComponentStoreContractTest : ComponentStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = se.soderbjorn.lunicle.ProjectStore(db)
    private var seq = 0

    override val store: ComponentStore = se.soderbjorn.lunicle.ComponentStore(db)

    override suspend fun newProject(): Long =
        projects.insert("Project $seq", "CM${seq++}").id

    @AfterTest
    fun tearDown() = fixture.close()
}
