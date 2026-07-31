/**
 * The guest-audience ceiling, over the **Firestore** stores on the emulator (LNL-202) —
 * the mirror of [SqlDelightGuestAudienceCeilingContractTest].
 *
 * Same assertions ([GuestAudienceCeilingContract]), different backend. That is the point
 * of running it twice: audience rows are three tables' worth of rows on SQLite and a map
 * on a project document on Firestore, so "a guest row above Viewer grants nothing extra"
 * is a claim about the rule and not about either storage shape. Skipped when no emulator
 * is configured.
 *
 * **Seeding is synthetic.** An audience row stores a project id as a plain field and
 * validates nothing — a document store has no foreign keys — so [newProject] is just a
 * fresh id, well clear of the other Firestore contract tests' ranges.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreInstanceSettingsStore
import se.soderbjorn.lunicle.FirestoreRoleStore
import se.soderbjorn.lunicle.UserRecord
import se.soderbjorn.lunicle.clientserver.AuthProvider

class FirestoreGuestAudienceCeilingContractTest : GuestAudienceCeilingContract() {
    private val fixture = FirestoreContractFixture()

    private var seq = 71_000L

    override val roles: RoleStore by lazy { FirestoreRoleStore(fixture.firestore) }
    override val instanceSettings: InstanceSettingsStore by lazy {
        FirestoreInstanceSettingsStore(fixture.firestore)
    }

    override suspend fun newProject(): Long = ++seq

    /**
     * A plain record, not a stored one. Nothing on this backend validates that a role row
     * names an account that exists, and nothing under test reads the account back — only
     * its id is compared, against role rows written under the same id.
     */
    override suspend fun newAccount(): UserRecord {
        val n = ++seq
        return UserRecord(
            id = n,
            provider = AuthProvider.GITHUB,
            providerId = "gh-$n",
            providerName = "User $n",
            displayNameOverride = null,
            email = "user$n@example.com",
            signedInAt = 1L,
        )
    }

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
