/**
 * The Statistics contract, run against the **Firestore** implementation on the
 * emulator — the mirror of [SqlDelightStatisticsStoreContractTest].
 *
 * Same assertions ([StatisticsStoreContract]), different backend; skipped when no
 * emulator is configured.
 *
 * **Seeding.** Issues are filed through [se.soderbjorn.lunicle.FirestoreIssueStore]
 * (insertDraft + publish) into the same `issues` collection the statistics store
 * counts, so `issuesCreated.allTime` reflects them exactly as in production. The
 * store is built with no repository-config source (the default), so commit counting
 * short-circuits to Unavailable and no network is touched — the same shape as the
 * SQLite test's unlinked project.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.FirestoreIssueStore
import se.soderbjorn.lunicle.FirestoreStatisticsStore

class FirestoreStatisticsStoreContractTest : StatisticsStoreContract() {
    private val fixture = FirestoreContractFixture()
    private var seq = 5_000L

    private val issues by lazy { FirestoreIssueStore(fixture.firestore) }

    override val store: StatisticsStore by lazy { FirestoreStatisticsStore(fixture.firestore) }

    override suspend fun newProject(): Long = ++seq

    override suspend fun fileIssue(projectId: Long) {
        val statusId = ++seq
        val priorityId = ++seq
        val (id, _) = issues.insertDraft(projectId, "Issue", statusId, priorityId, Author.Nobody)
        issues.publish(
            id, "Issue $id", "", statusId, priorityId, null,
            assigneeId = null, assigneeIsAgent = false,
            sprintId = null, plannedVersionId = null, fixedVersionId = null, estimate = null,
        )
    }

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
