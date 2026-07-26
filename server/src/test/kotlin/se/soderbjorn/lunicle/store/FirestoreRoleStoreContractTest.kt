/**
 * The Role contract, run against the **Firestore** implementation on the emulator —
 * the mirror of [SqlDelightRoleStoreContractTest]. Same assertions
 * ([RoleStoreContract]), different backend; skipped when no emulator is configured.
 *
 * **Seeding is synthetic.** A grant document stores a user id and a project id as
 * plain fields and validates neither (a document store has no foreign keys), so
 * [newUser] and [newProject] are just fresh ids. They start well clear of one
 * another and are never confused, since a grant is keyed by the (user, project,
 * role) triple in full.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreRoleStore

class FirestoreRoleStoreContractTest : RoleStoreContract() {
    private val fixture = FirestoreContractFixture()

    private var seq = 9_000L
    private fun nextId(): Long = ++seq

    override val store: RoleStore by lazy { FirestoreRoleStore(fixture.firestore) }

    override suspend fun newUser(): Long = nextId()

    override suspend fun newProject(): Long = nextId()

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
