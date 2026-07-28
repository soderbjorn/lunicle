/**
 * The Subscription contract, run against the **Firestore** implementation on the
 * emulator — the mirror of [SqlDelightSubscriptionStoreContractTest].
 *
 * Same assertions ([SubscriptionStoreContract]), different backend; skipped when no
 * emulator is configured.
 *
 * **Seeding.** A subscription stores a user id and a target id as plain fields and
 * validates neither, so users, projects and issues are synthetic ids. The name and
 * address the audience queries would join from `users` are supplied through the
 * store's [FirestoreSubscriptionStore.resolveContacts] seam, backed here by the map
 * `newUser` fills in — the addressless case (a null email) included, since LNL-109
 * keeps such a subscriber in the audience with no e-mail view.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreSubscriptionStore

class FirestoreSubscriptionStoreContractTest : SubscriptionStoreContract() {
    private val fixture = FirestoreContractFixture()
    private var seq = 5_000L
    private val contacts = mutableMapOf<Long, FirestoreSubscriptionStore.Contact>()

    override val store: SubscriptionStore by lazy {
        FirestoreSubscriptionStore(
            fixture.firestore,
            resolveContacts = { ids -> ids.mapNotNull { id -> contacts[id]?.let { id to it } }.toMap() },
        )
    }

    override suspend fun newUser(email: String?): Long {
        val id = ++seq
        contacts[id] = FirestoreSubscriptionStore.Contact(name = "User $id", email = email)
        return id
    }

    override suspend fun newProject(): Long = ++seq

    override suspend fun newPublishedIssue(projectId: Long): Long = ++seq

    // Synthetic here for the same reason as everything above: this store validates no
    // referent. The contract insists on real rows only because SQLite's foreign keys do.
    override suspend fun newForum(projectId: Long): Long = ++seq

    override suspend fun newForumPost(forumId: Long): Long = ++seq

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
