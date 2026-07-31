/**
 * The behaviour every [IssueRelationStore] implementation must exhibit — the links
 * between issues (LNL-215).
 *
 * This is a seam where the two backends are built from genuinely different materials,
 * so the parity is worth pinning line by line. SQLite answers "every link touching
 * this issue" with `WHERE from_issue_id = ? OR to_issue_id = ?` and gets both
 * cascades — the issue's and the kind's — free from `ON DELETE CASCADE`. Firestore
 * can express neither: the disjunction is two queries merged in Kotlin, and the two
 * cascades are sweeps this interface performs by hand. Every one of those hand-written
 * halves is a place a document backend can quietly do half the job, and the ones that
 * bite are the *reverse* directions — the link an issue never added but is named by.
 *
 * The semantics pinned here: a link round-trips through insert → findById;
 * [IssueRelationStore.forIssue] returns both directions oldest-first;
 * [IssueRelationStore.forProject] is scoped to its project;
 * [IssueRelationStore.usageByKind] counts what the delete confirmation quotes;
 * [IssueRelationStore.delete] removes one link by id; and the two sweeps —
 * [IssueRelationStore.deleteForIssue] and [IssueRelationStore.deleteForKind] — take
 * everything they are meant to and nothing they are not.
 *
 * **Not pinned here, deliberately:** the rules. Same project for both issues and the
 * kind, no self-relation, no duplicate pair in either direction, both issues
 * published — all of those live in `IssueRepository`, are backend-agnostic, and are
 * covered where they live. A store writes what it is told. See IssueRelations.sq.
 *
 * A subclass per backend supplies the store and three minting hooks, because a link
 * names three things that have to exist first — a project, the issues at each end,
 * and the kind. The SQLite backend mints real rows so its foreign keys are satisfied
 * the way production satisfies them; the Firestore backend mints synthetic `Long`s,
 * because a document store has no foreign keys to satisfy and the store validates
 * none of the three.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

abstract class IssueRelationStoreContract {
    protected abstract val store: IssueRelationStore

    /** A fresh project with no links of its own. */
    protected abstract suspend fun newProject(): Long

    /** An issue in [projectId], for a link to name. */
    protected abstract suspend fun newIssue(projectId: Long): Long

    /** Another relation kind in [projectId], for a link to be filed under. */
    protected abstract suspend fun newKind(projectId: Long): Long

    @Test
    fun `insert round-trips a link through findById`(): Unit = runBlocking {
        val project = newProject()
        val from = newIssue(project)
        val to = newIssue(project)
        val kind = newKind(project)

        val id = store.insert(project, from, to, kind, createdAt = 1_000)
        val found = store.findById(id)
        assertNotNull(found, "a written link is found by its id")
        assertEquals(project, found.projectId)
        assertEquals(from, found.fromIssueId, "the stated direction is preserved")
        assertEquals(to, found.toIssueId)
        assertEquals(kind, found.kindId)
        assertEquals(1_000, found.createdAt, "the caller's timestamp is stored, not a fresh one")
        assertNull(store.findById(id + 9_999), "an absent id is null")
    }

    /**
     * The reverse read — the one this whole interface exists to make cheap.
     *
     * A link is stored **once**, from → to, and an issue's window shows both the links
     * it added and the links that name it. On SQLite that is one `OR`; on Firestore it
     * is two queries merged, which is exactly the shape that ships with one half
     * missing. So the assertion is not "forIssue returns something" but "forIssue
     * returns the same link from either end, and the record still knows which end is
     * which" — a store that silently normalised the direction would pass a weaker test
     * and break every "Blocked by" / "Blocks" label in the product.
     */
    @Test
    fun `forIssue returns links in both directions, oldest first`(): Unit = runBlocking {
        val project = newProject()
        val subject = newIssue(project)
        val other = newIssue(project)
        val third = newIssue(project)
        val kind = newKind(project)

        val outgoing = store.insert(project, subject, other, kind, createdAt = 1_000)
        val incoming = store.insert(project, third, subject, kind, createdAt = 2_000)
        // A link between two issues that are not the subject, to prove the read is not
        // simply "every link in the project".
        store.insert(project, other, third, kind, createdAt = 3_000)

        val links = store.forIssue(subject)
        assertEquals(
            listOf(outgoing, incoming),
            links.map { it.id },
            "both the link this issue added and the link that names it, in id order",
        )
        assertEquals(subject, links.first().fromIssueId, "the outgoing link still reads from the subject")
        assertEquals(subject, links.last().toIssueId, "and the incoming one still reads to it")
        assertEquals(other, links.first().otherThan(subject), "otherThan resolves the far end from either side")
        assertEquals(third, links.last().otherThan(subject))
    }

    @Test
    fun `forProject returns this project's links and no other's`(): Unit = runBlocking {
        val project = newProject()
        val kind = newKind(project)
        val mine = store.insert(project, newIssue(project), newIssue(project), kind, createdAt = 1_000)

        val elsewhere = newProject()
        store.insert(elsewhere, newIssue(elsewhere), newIssue(elsewhere), newKind(elsewhere), createdAt = 1_000)

        assertEquals(listOf(mine), store.forProject(project).map { it.id })
    }

    /**
     * The number the delete confirmation quotes before a kind's links are cascaded.
     *
     * Shown, never enforced — a relation kind is not `restrictsOnUse` — so what this
     * owes is an accurate count per kind and no rows at all for a kind nobody has
     * used. A backend that returned a zero row would put "0 relations use that kind"
     * in a sentence written to be read as "3 relations use that kind".
     */
    @Test
    fun `usageByKind counts the links under each kind and omits the unused`(): Unit = runBlocking {
        val project = newProject()
        val blocking = newKind(project)
        val duplicate = newKind(project)
        val unused = newKind(project)
        val a = newIssue(project)
        val b = newIssue(project)
        val c = newIssue(project)

        store.insert(project, a, b, blocking, createdAt = 1_000)
        store.insert(project, a, c, blocking, createdAt = 2_000)
        store.insert(project, b, c, duplicate, createdAt = 3_000)

        val uses = store.usageByKind(project)
        assertEquals(2L, uses[blocking], "both links under the blocking kind are counted")
        assertEquals(1L, uses[duplicate])
        assertNull(uses[unused], "a kind nobody has used is absent rather than zero")
    }

    @Test
    fun `delete removes one link by id and leaves the rest`(): Unit = runBlocking {
        val project = newProject()
        val kind = newKind(project)
        val a = newIssue(project)
        val b = newIssue(project)
        val c = newIssue(project)
        val doomed = store.insert(project, a, b, kind, createdAt = 1_000)
        val spared = store.insert(project, a, c, kind, createdAt = 2_000)

        store.delete(doomed)

        assertNull(store.findById(doomed))
        assertEquals(listOf(spared), store.forIssue(a).map { it.id }, "the other link is untouched")
    }

    /**
     * The issue-delete cascade — free on SQLite, entirely hand-written on Firestore.
     *
     * Both directions are asserted, and the incoming one is the load-bearing half: it
     * is the link the deleted issue never added, so a sweep written from the deleting
     * issue's point of view misses it, and what is left behind is a relation document
     * naming an id that resolves to nothing — invisible until somebody opens the issue
     * at the other end and sees a link to a ticket that is not there.
     *
     * The spared link is the other half of the usual pair: a sweep keyed on nothing
     * empties the collection and passes every assertion that only looks at what went.
     */
    @Test
    fun `deleteForIssue takes both directions and spares another issue's links`(): Unit = runBlocking {
        val project = newProject()
        val kind = newKind(project)
        val doomed = newIssue(project)
        val other = newIssue(project)
        val third = newIssue(project)

        val outgoing = store.insert(project, doomed, other, kind, createdAt = 1_000)
        val incoming = store.insert(project, third, doomed, kind, createdAt = 2_000)
        val unrelated = store.insert(project, other, third, kind, createdAt = 3_000)

        store.deleteForIssue(doomed)

        assertNull(store.findById(outgoing), "the link it added went")
        assertNull(store.findById(incoming), "and so did the link that named it")
        assertNotNull(store.findById(unrelated), "a link between two other issues is not this delete's business")
        assertTrue(store.forIssue(doomed).isEmpty())
    }

    @Test
    fun `deleteForIssue on an issue with no links is a no-op`(): Unit = runBlocking {
        val project = newProject()
        val kind = newKind(project)
        val untouched = newIssue(project)
        val kept = store.insert(project, newIssue(project), newIssue(project), kind, createdAt = 1_000)

        store.deleteForIssue(untouched)

        assertNotNull(store.findById(kept), "an empty cascade deleted something")
    }

    /**
     * The kind-delete cascade, the second one Firestore has to perform by hand.
     *
     * A relation row without its kind is not a weakened statement, it is no statement
     * at all — two issue ids and no word between them — which is why deleting a kind
     * cascades rather than being refused or releasing its rows the way a version does.
     * The second kind's link is what proves the sweep is keyed on the kind and not on
     * the project.
     */
    @Test
    fun `deleteForKind takes that kind's links and spares another kind's`(): Unit = runBlocking {
        val project = newProject()
        val doomedKind = newKind(project)
        val sparedKind = newKind(project)
        val a = newIssue(project)
        val b = newIssue(project)

        val doomed = store.insert(project, a, b, doomedKind, createdAt = 1_000)
        val spared = store.insert(project, a, b, sparedKind, createdAt = 2_000)

        store.deleteForKind(doomedKind)

        assertNull(store.findById(doomed))
        assertNotNull(store.findById(spared), "the same pair under another kind is a different fact")
        assertNull(store.usageByKind(project)[doomedKind], "and the kind now counts for nothing")
    }
}
