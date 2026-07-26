/**
 * The UiSettings contract, run against the SQLite reference implementation.
 *
 * All the assertions live in [UiSettingsStoreContract]; this file only wires the
 * SQLite backend to them — a temp database, the real
 * [se.soderbjorn.lunicle.UiSettingsStore] over it, and a user seeded through the
 * real [UserStore] so the `user_ui_settings → users` foreign key is satisfied the
 * way it is in production.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.UserStore
import se.soderbjorn.lunicle.clientserver.AuthProvider

class SqlDelightUiSettingsStoreContractTest : UiSettingsStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val users = UserStore(fixture.database)
    private var seq = 0

    // The concrete SQLite class, held at the interface type the contract sees.
    // Named by its fully-qualified name because this test lives in the `store`
    // package, where the bare name is the interface.
    override val store: UiSettingsStore = se.soderbjorn.lunicle.UiSettingsStore(fixture.database)

    override suspend fun newUser(): Long =
        users.upsert(ProviderIdentity(AuthProvider.GITHUB, "contract-ui-${seq++}", "U", null)).id

    @AfterTest
    fun tearDown() = fixture.close()
}
