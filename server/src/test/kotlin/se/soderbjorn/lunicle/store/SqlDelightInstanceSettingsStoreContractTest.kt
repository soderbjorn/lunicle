/**
 * The InstanceSettings contract, run against the SQLite reference implementation.
 *
 * All the assertions live in [InstanceSettingsStoreContract]; this file only wires
 * the SQLite backend to them — a temp database and the real
 * [se.soderbjorn.lunicle.InstanceSettingsStore] over it. No user seeding, unlike
 * the UiSettings test, because `instance_settings` has no foreign key to satisfy.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest

class SqlDelightInstanceSettingsStoreContractTest : InstanceSettingsStoreContract() {
    private val fixture = SqlDelightContractFixture()

    // The concrete SQLite class, held at the interface type the contract sees.
    // Named by its fully-qualified name because this test lives in the `store`
    // package, where the bare name is the interface.
    override val store: InstanceSettingsStore = se.soderbjorn.lunicle.InstanceSettingsStore(fixture.database)

    @AfterTest
    fun tearDown() = fixture.close()
}
