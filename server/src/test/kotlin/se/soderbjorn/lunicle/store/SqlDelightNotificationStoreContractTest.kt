/**
 * The Notification contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.NotificationStore]).
 *
 * The assertions live in [NotificationStoreContract]; this file wires SQLite — a
 * temp database and the real store over it, with users minted through the real
 * [UserStore] so the `user_id` foreign key every notification carries is satisfied
 * exactly as it is on the volume.
 *
 * The concrete gateway shares its simple name with the [NotificationStore]
 * interface, so it is constructed by its fully-qualified name; `store` and every
 * other reference in this package is the interface.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.UserStore
import se.soderbjorn.lunicle.clientserver.AuthProvider

class SqlDelightNotificationStoreContractTest : NotificationStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val users = UserStore(db)
    private var seq = 0

    override val store: NotificationStore = se.soderbjorn.lunicle.NotificationStore(db)

    override suspend fun newUser(): Long {
        val n = seq++
        return users.upsert(ProviderIdentity(AuthProvider.GITHUB, "sub-$n", "User $n", "user$n@example.com")).id
    }

    @AfterTest
    fun tearDown() = fixture.close()
}
