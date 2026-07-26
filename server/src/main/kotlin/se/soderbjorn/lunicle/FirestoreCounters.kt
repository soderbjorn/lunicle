/**
 * Monotonic `Long` id allocation for the document backend — the shared convention
 * the Firestore stores use where the SQLite schema leaned on `AUTOINCREMENT`.
 *
 * The whole system addresses rows by `Long` id ([IssueRecord.id], `findById(id:
 * Long)`, every foreign key), and a document store hands out opaque string ids
 * instead. So each store that needs stable numeric ids keeps a counter document in
 * the `_counters` collection — `_counters/issues` for the global issue id,
 * `_counters/issueNumber-<projectId>` for a project's FOO-<n> sequence — and
 * allocates from it inside a transaction. The counter is monotonic and never
 * rewinds, which is exactly the SQLite guarantee that a number is never *reused*
 * after a delete: a cancelled draft burns its number here just as MAX+1 burned it
 * there.
 *
 * **Why [next] takes the transaction rather than opening its own.** An allocation
 * is only correct if it is atomic with the write that consumes it — two callers
 * must never read the same "next" value and both use it. So the id is allocated in
 * the *same* transaction that writes the new document, and this helper is handed
 * that transaction. It bumps several counters in one call (the issue store needs
 * two — a global id and a per-project number — at once) and does every read before
 * any write, because a Firestore transaction requires all of its reads to precede
 * all of its writes; a caller that then writes its own documents after this returns
 * stays on the right side of that rule.
 *
 * @see FirestoreIssueStore
 * @see FirestoreProvider
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Transaction

internal class FirestoreCounters(private val firestore: Firestore) {
    private fun ref(name: String) = firestore.collection(COLLECTION).document(name)

    /**
     * Allocate the next value of each named counter, atomically within [txn], and
     * return them keyed by name.
     *
     * Every counter is read first and only then written, so any number of them can
     * be advanced in a single call without violating Firestore's reads-before-writes
     * rule — and the caller may still write its own documents afterwards, since
     * those writes also fall after all the reads. A counter that has never been
     * touched reads as absent and starts at 1.
     */
    fun next(txn: Transaction, vararg names: String): Map<String, Long> {
        val refs = names.associateWith { ref(it) }
        // Reads first — all of them, before a single write — so the caller can go on
        // to write the documents these ids belong to within the same transaction.
        val allocated = refs.mapValues { (_, r) -> (txn.get(r).get().getLong(VALUE) ?: 0L) + 1 }
        allocated.forEach { (name, value) -> txn.set(refs.getValue(name), mapOf(VALUE to value)) }
        return allocated
    }

    companion object {
        const val COLLECTION = "_counters"
        const val VALUE = "value"
    }
}
