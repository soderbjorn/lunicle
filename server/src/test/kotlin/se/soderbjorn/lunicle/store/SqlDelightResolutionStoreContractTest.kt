/**
 * The Resolution contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.ResolutionStore]).
 *
 * A bare project row through the low-level [se.soderbjorn.lunicle.ProjectStore] (no
 * vocabulary seeding), so the project starts empty of resolutions. The concrete
 * gateway is constructed by its fully-qualified name, sharing its simple name with
 * the [ResolutionStore] interface.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest

class SqlDelightResolutionStoreContractTest : ResolutionStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = se.soderbjorn.lunicle.ProjectStore(db)
    private var seq = 0

    override val store: ResolutionStore = se.soderbjorn.lunicle.ResolutionStore(db)

    override suspend fun newProject(): Long =
        projects.insert("Project $seq", "RS${seq++}", isPublic = false).id

    @AfterTest
    fun tearDown() = fixture.close()
}
