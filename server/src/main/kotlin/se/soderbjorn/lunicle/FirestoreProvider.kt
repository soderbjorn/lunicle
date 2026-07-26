/**
 * Building the Firestore client — lazily, and only when the Firestore backend is
 * actually selected.
 *
 * This is the file the Railway-safety guarantee of LNL-111 rests on. The GCP SDK
 * jar ships in the one image, but a `Firestore` client reaches for Application
 * Default Credentials the moment it is constructed — and on Railway there are no
 * credentials and no metadata server to answer, so constructing one eagerly would
 * throw on every SQLite deploy. So nothing here runs at class-load or at boot:
 * [FirestoreProvider] is built only on the `LUNICLE_DB_BACKEND=firestore` branch
 * of `Application.module`, and even then the client itself is behind a `lazy` so
 * the first *store operation* is what actually opens it.
 *
 * How it authenticates, in the three environments the ticket names:
 *  - **Cloud Run** — the runtime service account plus the metadata server supply
 *    ADC automatically; no key file, nothing set here.
 *  - **Off-GCP, real Firestore** — `GOOGLE_APPLICATION_CREDENTIALS` or a
 *    `gcloud auth application-default login`, both of which ADC finds on its own.
 *  - **Dev / tests** — the Firestore emulator, detected by the client library
 *    from `FIRESTORE_EMULATOR_HOST`; no credentials are used at all.
 *
 * @see resolveDatabaseBackend
 * @see DatabaseBackend.FIRESTORE
 */
package se.soderbjorn.lunicle

import com.google.api.core.ApiFuture
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("FirestoreProvider")

/**
 * The GCP project the Firestore (and GCS) clients talk to.
 *
 * Precedence mirrors every other resolver in this server — a system property for
 * local runs (a Gradle `JavaExec` inherits the daemon's environment, so an env
 * var would go stale), then the environment variable the container actually has.
 * `GOOGLE_CLOUD_PROJECT` is the name the GCP libraries themselves use, so a Cloud
 * Run service already carries it.
 *
 * Fatal when unset *and reached*, which only happens under the Firestore backend:
 * a Firestore deploy with no project is a misconfiguration that should refuse to
 * start rather than fail its first request.
 */
internal fun resolveGcpProject(): String =
    (System.getProperty("lunicle.gcpProject")?.takeIf { it.isNotBlank() }
        ?: System.getenv("GOOGLE_CLOUD_PROJECT")?.takeIf { it.isNotBlank() })
        ?: error(
            "LUNICLE_DB_BACKEND=firestore but GOOGLE_CLOUD_PROJECT is not set. The Firestore " +
                "client needs a project id. Refusing to start.",
        )

/**
 * Await a Google-cloud [ApiFuture] on the IO dispatcher.
 *
 * The Firestore client is blocking under a future-shaped surface: every call
 * returns an `ApiFuture` whose `get()` blocks. This confines that block to
 * [Dispatchers.IO] — the same place the SQLite path puts its blocking JDBC — so a
 * store's `suspend` method never stalls a Netty event-loop thread. Unlike the
 * SQLite dispatcher this is not `limitedParallelism(1)`: Firestore is a
 * multi-writer service and its own transactions handle concurrency, so serialising
 * here would only throw away throughput the backend is built to give.
 */
internal suspend fun <T> ApiFuture<T>.await(): T = withContext(Dispatchers.IO) { get() }

/**
 * Holds the one Firestore client the stores share, opened on first use.
 *
 * @property firestore the client. `lazy`, so merely constructing this provider —
 *   which happens inside the firestore branch of module wiring — still does not
 *   open a connection or touch credentials; the first store call does.
 */
class FirestoreProvider(
    private val projectId: String = resolveGcpProject(),
) : AutoCloseable {
    val firestore: Firestore by lazy { open() }

    private fun open(): Firestore {
        // Explicit project id rather than ADC's discovered default: it is required
        // anyway, and setting it means the emulator path (which has no ADC to
        // discover one from) needs nothing more. The client library reads
        // FIRESTORE_EMULATOR_HOST itself and, when it is set, talks to the emulator
        // with no credentials — which is why tests need no key.
        val emulator = System.getenv("FIRESTORE_EMULATOR_HOST")?.takeIf { it.isNotBlank() }
        logger.info(
            "Firestore: project=$projectId, target=${emulator?.let { "emulator $it" } ?: "Google (ADC)"}",
        )
        return FirestoreOptions.newBuilder()
            .setProjectId(projectId)
            .build()
            .service
    }

    /** Close the client so its gRPC channels shut down cleanly. Called when Ktor stops. */
    override fun close() {
        firestore.close()
    }
}
