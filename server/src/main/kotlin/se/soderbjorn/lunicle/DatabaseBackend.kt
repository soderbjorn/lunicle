/**
 * Which persistence backend the server runs on, chosen once at boot.
 *
 * The one switch behind LNL-111. Two backends sit behind the store interfaces in
 * [se.soderbjorn.lunicle.store]: today's SQLite (the reference implementation,
 * the only thing Railway has ever run) and a parallel Firestore backend for a
 * Cloud-Run-native deploy. Which one a process uses is decided here, from the
 * environment, before any store is constructed — and defaults to SQLite, so an
 * un-configured deploy behaves exactly as it always has.
 *
 * ── Why this is its own file, and read this early ───────────────────────────
 *
 * The whole clean-rollback guarantee of LNL-111 rests on one property: the
 * Firestore code is *inert* unless this resolves to [DatabaseBackend.FIRESTORE].
 * The GCP SDK jar ships in the one image, but nothing constructs a Firestore or
 * GCS client — and therefore nothing reaches for Application Default Credentials
 * or the metadata server, the one thing that would throw on Railway — unless the
 * backend was selected here. So this is deliberately a pure, side-effect-free
 * environment read: it decides *which* backend, and constructs *none* of it. The
 * construction is gated on the value it returns, elsewhere.
 *
 * @see resolveDatabaseLocation for where the SQLite file goes once SQLite is chosen.
 */
package se.soderbjorn.lunicle

/**
 * The persistence backends this build knows how to run on.
 *
 * @property id the value [resolveDatabaseBackend] matches against, and the name
 *   the deploy sets `LUNICLE_DB_BACKEND` to. Lower-case because that is what a
 *   human types into a deployment variable; matching is case-insensitive anyway.
 */
enum class DatabaseBackend(val id: String) {
    /**
     * SQLite on a mounted volume — Railway's backend, and the default. The
     * reference implementation every other backend is measured against by the
     * store contract suite. Selecting nothing selects this.
     */
    SQLITE("sqlite"),

    /**
     * Firestore, for a serverless Cloud-Run-native deploy (multi-writer,
     * scale-to-zero, no disk). Opt-in only, via `LUNICLE_DB_BACKEND=firestore`.
     */
    FIRESTORE("firestore"),
    ;

    companion object {
        /** The default when nothing is configured: SQLite, i.e. today's behaviour. */
        val DEFAULT = SQLITE
    }
}

/**
 * Decide which backend this process runs on.
 *
 * The precedence mirrors [resolveDatabaseLocation], [resolveAllowedFrameAncestors]
 * and [resolveOAuthConfig], for the reasons documented there — a system property
 * for local runs, because a Gradle `JavaExec` inherits the long-lived daemon's
 * environment and an environment variable would go stale; an environment variable
 * for the container, where the process is a plain `java -jar` and the environment
 * is exact.
 *
 * Blank is treated as absent throughout, as everywhere else in this server: an
 * empty override falls through to the default rather than being a third, unnamed
 * state.
 *
 * An *unrecognised* value is fatal rather than silently falling back to SQLite.
 * The failure this prevents is a `LUNICLE_DB_BACKEND=firstore` typo on a Cloud
 * Run deploy that was meant to run Firestore: falling back would quietly boot the
 * SQLite path on a container with no volume, which is the exact silent data-loss
 * shape [openDatabase] already shouts about. Refusing to start names the typo in
 * the log and lets the platform restart into the same loud failure.
 *
 * @return the selected backend, defaulting to [DatabaseBackend.DEFAULT].
 * @throws IllegalStateException if the configured value names no known backend.
 */
fun resolveDatabaseBackend(): DatabaseBackend {
    val configured = System.getProperty("lunicle.dbBackend")?.takeIf { it.isNotBlank() }
        ?: System.getenv("LUNICLE_DB_BACKEND")?.takeIf { it.isNotBlank() }
        ?: return DatabaseBackend.DEFAULT

    return DatabaseBackend.entries.firstOrNull { it.id.equals(configured, ignoreCase = true) }
        ?: error(
            "LUNICLE_DB_BACKEND is \"$configured\", which names no known backend. " +
                "Valid values: ${DatabaseBackend.entries.joinToString(", ") { it.id }}. Refusing to start.",
        )
}
