/**
 * What [FirestorePermissionModelMigration] does to the documents — the Firestore
 * twin of [se.soderbjorn.lunicle.PermissionMigrationTest].
 *
 * The two files assert the same promise against the same fixtures, deliberately, so
 * that "granting nothing" cannot come to mean one thing on a relational volume and
 * another on a document one. Where they differ is in what they can be *given*: SQL
 * has a version-33 snapshot to seed against, and Firestore has no schema at all, so
 * the old shape is written here by hand — which is also the only place in the
 * codebase where the pre-LNL-191 document shape is still written down.
 *
 * ── The resumability test is the one worth reading ──────────────────────────
 *
 * `apply` runs outside any transaction, so the runner re-invokes it from the last
 * checkpointed version after an interrupted boot. Running it twice therefore has to
 * be indistinguishable from running it once — and the failure mode if it is not is
 * not a crash, it is a project quietly re-seated to a different owner. See
 * [`running it twice changes nothing`].
 *
 * Skipped, not failed, when no emulator is configured — see [FirestoreEmulator].
 */
package se.soderbjorn.lunicle.store

import com.google.cloud.firestore.Firestore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.AccessControl
import se.soderbjorn.lunicle.Audience
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.CommentRecord
import se.soderbjorn.lunicle.FirestoreInstanceSettingsStore
import se.soderbjorn.lunicle.FirestoreMigrations
import se.soderbjorn.lunicle.FirestoreRoleStore
import se.soderbjorn.lunicle.FirestoreUserStore
import se.soderbjorn.lunicle.InstanceRole
import se.soderbjorn.lunicle.ProjectRole
import se.soderbjorn.lunicle.UserKind
import se.soderbjorn.lunicle.await

class FirestorePermissionMigrationTest {
    private val fixture = FirestoreContractFixture()
    private val db: Firestore get() = fixture.firestore

    /**
     * The registered chain, rather than a hand-built instance.
     *
     * Deliberate: this is also the test that the step is *wired up*. A migration
     * that is written and not appended to [FirestoreMigrations.ALL] is a migration
     * that never runs, and nothing else would notice.
     */
    private val migration get() = FirestoreMigrations.ALL.single()

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()

    @Test
    fun `every account carries over and nobody carries a privilege`() = runBlocking {
        oldUser(1, "sys", isSysAdmin = true)
        oldUser(2, "ada")
        oldProject(1, "Alpha", isPublic = true)
        oldGrant(2, 1, "create_issue")
        oldGrant(2, 1, "change_unowned_issues")

        migration.apply(db)

        val ada = db.collection("users").document("2").get().await()
        assertEquals("ada", ada.getString("providerName"), "an account was not carried over")
        assertEquals(UserKind.MEMBER.key, ada.getString("kind"), "kind was not stamped to the lesser answer")
        assertNull(ada.getString("instanceRole"), "an ordinary account arrived on the instance ladder")
        assertFalse(ada.contains("isSysAdmin"), "the retired flag survived")
        assertFalse(ada.contains("mcpAllowed"), "the retired MCP permission survived")
        assertEquals(0L, ada.getLong("addedAt"), "createdAt did not become addedAt")
        assertFalse(ada.contains("createdAt"), "the old timestamp field survived beside the new one")

        val roles = FirestoreRoleStore(db)
        assertNull(roles.roleFor(2, 1), "an ordinary account carried a privilege across")
        assertTrue(roles.audienceRoles(1).isEmpty(), "a public project was translated into an audience row")

        val project = db.collection("projects").document("1").get().await()
        assertFalse(project.contains("isPublic"), "project visibility survived as a second answer to who can read")
        assertFalse(project.contains("visibleToAllSignedIn"))
    }

    @Test
    fun `the system administrator becomes the instance owner and nothing else`() = runBlocking {
        oldUser(1, "sys", isSysAdmin = true)
        oldUser(2, "ada")

        migration.apply(db)

        assertEquals(1L, ownerUserId(), "the system administrator did not become the instance owner")
        assertNull(
            db.collection("users").document("1").get().await().getString("instanceRole"),
            "ownership was stated twice — once as a setting and once on the row",
        )
    }

    @Test
    fun `the unambiguous old owner keeps the board, and an ambiguous one falls to the instance owner`() =
        runBlocking {
            oldUser(1, "sys", isSysAdmin = true)
            oldUser(2, "ada")
            oldUser(3, "bo")
            oldProject(1, "Alpha")
            oldProject(2, "Beta")
            oldGrant(2, 1, "project_owner")
            oldGrant(2, 2, "project_owner")
            oldGrant(3, 2, "project_owner")

            migration.apply(db)

            val roles = FirestoreRoleStore(db)
            assertEquals(ProjectRole.OWNER, roles.roleFor(2, 1), "the sole old owner did not keep their board")
            assertEquals(mapOf(2L to ProjectRole.OWNER), roles.rolesForProject(1), "somebody else was seated too")
            assertEquals(
                mapOf(1L to ProjectRole.OWNER),
                roles.rolesForProject(2),
                "an ambiguous board did not fall to the instance owner",
            )
        }

    @Test
    fun `an unowned board falls to the instance owner`() = runBlocking {
        oldUser(1, "sys", isSysAdmin = true)
        oldProject(1, "Alpha")

        migration.apply(db)

        assertEquals(mapOf(1L to ProjectRole.OWNER), FirestoreRoleStore(db).rolesForProject(1))
    }

    @Test
    fun `an instance with no administrator seats nobody and does not fail`() = runBlocking {
        oldUser(1, "ada")
        oldProject(1, "Alpha")

        migration.apply(db)

        assertNull(ownerUserId())
        assertTrue(FirestoreRoleStore(db).rolesForProject(1).isEmpty())
    }

    /**
     * Idempotence, and the reason it is not merely tidiness.
     *
     * The second run finds the old grant documents gone — its own first run deleted
     * them — so a naive re-run would recompute "who owned this board?" from nothing
     * and re-seat the instance owner over the person who actually owns it. The skip
     * on an already-seated board is what stops that, and this is the test that would
     * catch its removal.
     */
    @Test
    fun `running it twice changes nothing`() = runBlocking {
        oldUser(1, "sys", isSysAdmin = true)
        oldUser(2, "ada")
        oldProject(1, "Alpha")
        oldGrant(2, 1, "project_owner")

        migration.apply(db)
        val afterFirst = FirestoreRoleStore(db).rolesForProject(1)
        migration.apply(db)

        assertEquals(afterFirst, FirestoreRoleStore(db).rolesForProject(1), "a second run re-seated the board")
        assertEquals(mapOf(2L to ProjectRole.OWNER), afterFirst)
        assertEquals(1L, ownerUserId())
    }

    /** An owner deliberately transferred after the migration is not undone by a re-run. */
    @Test
    fun `a re-run does not undo an ownership transfer`() = runBlocking {
        oldUser(1, "sys", isSysAdmin = true)
        oldProject(1, "Alpha")
        migration.apply(db)

        val roles = FirestoreRoleStore(db)
        roles.setRole(1, 1, null)
        roles.setRole(99, 1, ProjectRole.OWNER)
        roles.setAudienceRole(1, Audience.MEMBER, ProjectRole.CONTRIBUTOR)

        migration.apply(db)

        assertEquals(mapOf(99L to ProjectRole.OWNER), roles.rolesForProject(1))
        assertEquals(mapOf(Audience.MEMBER to ProjectRole.CONTRIBUTOR), roles.audienceRoles(1))
    }

    /**
     * The migrated owner clears the comment gate on a document volume too (LNL-201).
     *
     * The one test in this file that asks a *rule* rather than reading a document back,
     * and it is here rather than beside its SQLite twin because "on both backends" is a
     * claim about the two stores `AccessControl` reads through, not about the rule: the
     * gate resolves ownership out of `instanceSettings`, and the whole bug was a gate
     * that read the account's own row instead — a row this migration leaves NULL, on
     * either backend, deliberately.
     *
     * So this runs the real gate over [FirestoreRoleStore] and
     * [FirestoreInstanceSettingsStore], against exactly the documents the migration
     * produced, with `instanceRole` unset for the owner — asserted, because an owner who
     * also carried the administrator row would pass against the broken code.
     *
     * The [CommentRecord] is built rather than stored: the gate reads its `author` and
     * nothing else, so a comment store here would be plumbing that proves nothing.
     */
    @Test
    fun `the migrated instance owner may edit somebody elses comment`() = runBlocking {
        oldUser(1, "sys", isSysAdmin = true)
        oldUser(2, "ada")

        migration.apply(db)

        assertNull(
            db.collection("users").document("1").get().await().getString("instanceRole"),
            "Precondition: the seated owner's row must say nothing, which is the state this " +
                "migration leaves and the state the bug needed.",
        )

        val users = FirestoreUserStore(db)
        val access = AccessControl(FirestoreRoleStore(db), FirestoreInstanceSettingsStore(db))
        val owner = users.findById(1)!!
        val ada = users.findById(2)!!
        assertEquals(InstanceRole.OWNER, access.instanceRole(owner), "the migration did not seat the owner")

        val adasComment = CommentRecord(
            id = 1,
            issueId = 1,
            body = "not the owner's words",
            createdAt = 0,
            author = Author.Account(ada.id),
            agentName = null,
            isDraft = false,
        )
        assertTrue(
            access.canEditComment(owner, adasComment),
            "The instance owner of a migrated Firestore volume could not edit another " +
                "account's comment.",
        )
        assertFalse(
            access.canEditComment(ada, adasComment.copy(author = Author.Account(owner.id))),
            "An ordinary migrated account reached the owner's words; the fix is the ladder, " +
                "not a gate that stopped asking.",
        )
        assertTrue(access.canEditComment(ada, adasComment), "the author lost their own comment")
    }

    // ── The old document shape, written by hand ──────────────────────────────

    private suspend fun oldUser(id: Long, name: String, isSysAdmin: Boolean = false) {
        db.collection("users").document(id.toString()).set(
            mapOf(
                "id" to id,
                "provider" to "GOOGLE",
                "providerId" to name,
                "providerName" to name,
                "displayName" to null,
                "email" to "$name@example.com",
                "emailVerified" to true,
                "isSysAdmin" to isSysAdmin,
                "mcpEnabled" to false,
                "mcpAllowed" to false,
                "createdAt" to 0L,
            ),
        ).await()
    }

    private suspend fun oldProject(id: Long, name: String, isPublic: Boolean = false) {
        db.collection("projects").document(id.toString()).set(
            mapOf(
                "id" to id,
                "name" to name,
                "nameFold" to name.lowercase(),
                "namePrefix" to name.take(3).uppercase(),
                "isPublic" to isPublic,
                "visibleToAllSignedIn" to false,
                "position" to id,
                "createdAt" to 0L,
            ),
        ).await()
    }

    /** A grant in the old shape: one document per (user, project, role) key. */
    private suspend fun oldGrant(userId: Long, projectId: Long, roleKey: String) {
        db.collection(FirestoreRoleStore.GRANTS).document("${userId}_${projectId}_$roleKey").set(
            mapOf(
                "userId" to userId,
                "projectId" to projectId,
                "roleKey" to roleKey,
                "userProjectId" to "${userId}_$projectId",
            ),
        ).await()
        db.collection("roles").document(roleKey).set(mapOf("roleKey" to roleKey, "description" to roleKey)).await()
    }

    private suspend fun ownerUserId(): Long? {
        val snapshot = db.collection("instanceSettings").document("singleton").get().await()
        @Suppress("UNCHECKED_CAST")
        val values = (snapshot.get("values") as? Map<String, Any?>).orEmpty()
        return (values["owner_user_id"] as? Number)?.toLong()
    }
}
