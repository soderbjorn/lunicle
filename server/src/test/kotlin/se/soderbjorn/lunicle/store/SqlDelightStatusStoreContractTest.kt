/**
 * The Status contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.StatusStore]).
 *
 * A bare project row through the low-level [se.soderbjorn.lunicle.ProjectStore] (no
 * vocabulary seeding), so the project starts empty of statuses — the parity the
 * contract needs to control the whole column order and the `requiresResolution`
 * flags. The concrete gateway is constructed by its fully-qualified name, sharing
 * its simple name with the [StatusStore] interface.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest

class SqlDelightStatusStoreContractTest : StatusStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = se.soderbjorn.lunicle.ProjectStore(db)
    private var seq = 0

    override val store: StatusStore = se.soderbjorn.lunicle.StatusStore(db)

    override suspend fun newProject(): Long =
        projects.insert("Project $seq", "ST${seq++}").id

    @AfterTest
    fun tearDown() = fixture.close()
}
