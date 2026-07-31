/**
 * The Label contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.LabelStore]).
 *
 * A bare project row is created through the low-level [se.soderbjorn.lunicle.ProjectStore]
 * (not `ProjectRepository`, which would seed vocabulary) so the project starts empty
 * of labels — the parity the contract needs, matching the Firestore fixture's empty
 * synthetic project. The concrete gateway shares its simple name with the [LabelStore]
 * interface, so it is constructed by its fully-qualified name.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest

class SqlDelightLabelStoreContractTest : LabelStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = se.soderbjorn.lunicle.ProjectStore(db)
    private var seq = 0

    override val store: LabelStore = se.soderbjorn.lunicle.LabelStore(db)

    override suspend fun newProject(): Long =
        projects.insert("Project $seq", "LB${seq++}").id

    @AfterTest
    fun tearDown() = fixture.close()
}
