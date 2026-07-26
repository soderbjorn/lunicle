/**
 * The Firestore analog of a numbered `.sqm` file: one versioned, ordered schema
 * step, plus the registry that lists them and the `_meta` conventions the runner
 * and lock share.
 *
 * ── Why Firestore needs its own framework at all ────────────────────────────
 *
 * SQLite carries its schema version in the database header (`user_version`) and
 * SQLDelight generates a numbered chain of `.sqm` files that
 * [se.soderbjorn.lunicle.openDatabase] walks at startup. Firestore has neither: a
 * document store has no header integer and no DDL, so a "migration" is not an
 * `ALTER TABLE` but a *data* pass — read every document of a collection and write
 * it back in a new shape. That makes three properties load-bearing that the SQLite
 * chain gets for free, and they are the whole reason this is a first-class
 * framework rather than a script:
 *
 *  - **Versioning.** There is no header, so the version lives in a document —
 *    `/_meta/schema → { version: N }`, read as 0 when absent. See
 *    [FirestoreMigrationRunner].
 *  - **Resumability.** A backfill over a collection is thousands of individual
 *    writes that can partially complete when an instance is recycled mid-pass; a
 *    re-run must resume, not double-apply. See [FirestoreBackfill].
 *  - **Single-writer.** SQLite on Railway is one container behind a volume;
 *    Firestore on Cloud Run is many instances booting at once, any of which might
 *    try to migrate. A [FirestoreMigrationLock] elects one.
 *
 * ── Writing a migration ─────────────────────────────────────────────────────
 *
 * Implement [FirestoreMigration] with the next unused [version] and register it in
 * [FirestoreMigrations.ALL] — the exact analog of dropping a new `N.sqm` into the
 * chain. `apply` is handed the live [Firestore] client and does whatever the step
 * needs: an additive field default, an expand/contract rename, a materialised
 * backfill. It **must be idempotent**, because the runner re-runs the whole step
 * from the last checkpointed version after a crash — reach for [FirestoreBackfill],
 * which skips already-migrated documents, rather than a bare collection sweep.
 *
 * @see FirestoreMigrationRunner the runner that reads the version and applies pending steps.
 * @see FirestoreBackfill the idempotent, resumable, batched backfill helper `apply` should use.
 * @see se.soderbjorn.lunicle.openDatabase the SQLite counterpart this mirrors — frozen, untouched.
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore

/**
 * One versioned schema step — the Firestore analog of a numbered `.sqm` file.
 *
 * @property version the step's position in the chain, a positive integer unique
 *   across [FirestoreMigrations.ALL]. The runner applies steps in ascending
 *   version order and checkpoints `/_meta/schema.version` to this value once the
 *   step completes, so a step numbered `N` runs exactly when the stored version is
 *   below `N`. Number from 1, densely, and never renumber a released step — the
 *   stored version on a live database refers to these numbers.
 */
interface FirestoreMigration {
    val version: Int

    /**
     * Apply this step against the live client.
     *
     * Runs *outside* any Firestore transaction — a backfill is far more than the
     * 500-write ceiling a transaction allows — so it must be **idempotent and
     * resumable**: the runner re-invokes `apply` from the last checkpointed
     * version after an interrupted run, so a step that has half-completed will be
     * called again and must no-op over the part it already did. [FirestoreBackfill]
     * gives that for a collection sweep; anything else must arrange it by hand
     * (write with `merge`, guard on a marker, skip already-shaped documents).
     */
    suspend fun apply(db: Firestore)
}

/**
 * The ordered chain of Firestore migrations — the analog of the `.sqm` directory.
 *
 * Empty today: the Firestore stores (LNL-111) write the current document shape
 * directly, so there is no prior shape to migrate yet. The first breaking change
 * to a Firestore collection appends its step here, and the runner picks it up on
 * the next boot of the Firestore backend. Appending is the *only* correct edit —
 * a released step's [FirestoreMigration.version] is frozen the moment a production
 * database has checkpointed past it, exactly as a released `.sqm` is frozen.
 */
object FirestoreMigrations {
    val ALL: List<FirestoreMigration> = emptyList()
}

/**
 * The `_meta` collection conventions the runner and the lock share.
 *
 * A single reserved collection holds the framework's own bookkeeping documents,
 * kept out of the way of the domain collections (`issues`, `_counters`, …) by the
 * `_` prefix the store layer already uses for infrastructure.
 */
internal object FirestoreMeta {
    /** The reserved collection for migration bookkeeping. */
    const val COLLECTION = "_meta"

    /** `/_meta/schema` — the version marker document. Absent means version 0. */
    const val SCHEMA_DOC = "schema"

    /** The `Long` field on [SCHEMA_DOC] holding the current schema version. */
    const val VERSION = "version"

    /** `/_meta/migrationLock` — the single-writer election document. */
    const val LOCK_DOC = "migrationLock"
}
