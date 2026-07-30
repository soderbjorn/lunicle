/**
 * The public-projects veto, over the **Firestore** stores on the emulator (LNL-203) — the
 * mirror of [SqlDelightPublicProjectVetoContractTest].
 *
 * Same assertions ([PublicProjectVetoContract]), different backend, and running it twice is
 * the point: the guest row is a table row on SQLite and a map entry on a project document
 * on Firestore, and the switch itself is a settings row on one and a document field on the
 * other. "A vetoed guest row grants nothing, and lifting the veto restores it untouched" is
 * a claim about the rule, not about either storage shape. Skipped when no emulator is
 * configured.
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

class FirestorePublicProjectVetoContractTest : PublicProjectVetoContract() {
    private val fixture = FirestoreContractFixture()

    private var seq = 73_000L

    override val roles: RoleStore by lazy { FirestoreRoleStore(fixture.firestore) }
    override val instanceSettings: InstanceSettingsStore by lazy {
        FirestoreInstanceSettingsStore(fixture.firestore)
    }

    override suspend fun newProject(): Long = ++seq

    /**
     * A plain record, not a stored one. Nothing on this backend validates that a role row
     * names an account that exists, and nothing under test reads the account back — only its
     * id is compared, against role rows written under the same id.
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
