/**
 * The Version contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.VersionStore]) — the mirror of
 * [SqlDelightLabelStoreContractTest] (LNL-134).
 *
 * A bare project row is created through the low-level [se.soderbjorn.lunicle.ProjectStore]
 * (not `ProjectRepository`, which would seed vocabulary) so the project starts empty
 * of versions. The concrete gateway shares its simple name with the [VersionStore]
 * interface, so it is constructed by its fully-qualified name.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest

class SqlDelightVersionStoreContractTest : VersionStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = se.soderbjorn.lunicle.ProjectStore(db)
    private var seq = 0

    override val store: VersionStore = se.soderbjorn.lunicle.VersionStore(db)

    override suspend fun newProject(): Long =
        projects.insert("Project $seq", "VR${seq++}").id

    @AfterTest
    fun tearDown() = fixture.close()
}
