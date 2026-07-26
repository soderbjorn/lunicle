/**
 * The Firestore side of the store contract suite: a client pointed at the
 * emulator, in an isolated per-fixture namespace.
 *
 * The parallel of [SqlDelightContractFixture]. It feeds the *same* abstract
 * `*StoreContract` classes the SQLite fixtures feed — that is the whole point of
 * the suite: one set of behaviours, proven on both backends.
 *
 * **Isolation without a cleanup step.** The Firestore emulator namespaces data by
 * project id, so each fixture invents a unique one and gets an empty, private
 * datastore that vanishes when the emulator stops — no "delete everything between
 * tests" dance, and safe to run in parallel.
 *
 * **The client is created lazily**, on first access, so a fixture built by a test
 * that then skips (because the emulator is not configured — see [FirestoreEmulator])
 * never constructs a client and never reaches for credentials. That is what lets
 * these tests be skipped rather than errored when no emulator is around.
 */
package se.soderbjorn.lunicle.store

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import java.util.UUID

/** Whether a Firestore emulator is configured for this test run. */
object FirestoreEmulator {
    val host: String? = System.getenv("FIRESTORE_EMULATOR_HOST")?.takeIf { it.isNotBlank() }
    val isAvailable: Boolean get() = host != null
}

class FirestoreContractFixture : AutoCloseable {
    private val projectId = "lunicle-contract-${UUID.randomUUID()}"
    private var client: Firestore? = null

    /**
     * The Firestore client, built on first use. The library reads
     * FIRESTORE_EMULATOR_HOST itself and, when it is set, talks to the emulator
     * with no credentials — so a unique project id is all this needs.
     */
    val firestore: Firestore
        get() = client ?: FirestoreOptions.newBuilder()
            .setProjectId(projectId)
            .build()
            .service
            .also { client = it }

    override fun close() {
        client?.close()
    }
}
