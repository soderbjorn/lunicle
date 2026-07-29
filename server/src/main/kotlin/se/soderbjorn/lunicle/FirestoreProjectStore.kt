/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.ProjectStore] — the
 * low-level project row, its feature/requirement switches, its order, its active
 * sprint, and its GitHub repository config.
 *
 * This is the persistence gateway, not the orchestration: validation, vocabulary
 * seeding and cascade-on-delete live in `ProjectRepository`, a backend-agnostic
 * layer above, so this store only has to make the same *values* round-trip that
 * the SQLite [se.soderbjorn.lunicle.ProjectStore] does.
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * One document per project in `projects/{id}`, `{id}` the global `Long` id from
 * `_counters/projects` (see [FirestoreCounters]). The four booleans are stored as
 * booleans (not the 0/1 the SQLite columns hold) and default the same way a fresh
 * insert defaults them: discussions and messages enabled, the two requirement
 * flags off. The single [TokenSource] flattens onto two mutually-exclusive fields
 * exactly as it flattens onto two SQLite columns, so a config round-trips.
 *
 * **Case-insensitive [findByName].** SQLite got this from the column's
 * `COLLATE NOCASE`. Here a denormalised `nameFold` field holds the lowered name
 * and the query keys on it — one equality lookup, no index beyond the single-field
 * one the emulator (and production) provide automatically.
 *
 * **[activeSprintId] is shared storage with [FirestoreSprintStore].** Both the
 * project store and the sprint store expose the project's active sprint, and in
 * SQLite both read `projects.active_sprint_id`; here both read/write the
 * `activeSprintId` field on this document. [setActiveSprint] therefore uses a
 * merge-write, so the sprint store can point a project at a sprint even for a
 * project document the sprint contract seeded only by id.
 *
 * @see FirestoreProvider
 * @see FirestoreCounters
 * @see se.soderbjorn.lunicle.store.ProjectStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import com.google.cloud.firestore.Transaction
import se.soderbjorn.lunicle.store.ProjectStore

class FirestoreProjectStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : ProjectStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    /**
     * Insert a project row and return it — no vocabulary, that is the repository's
     * job.
     *
     * The id and the append position are read and allocated in one transaction, so
     * a crash cannot advance the counter past a project that was never written. The
     * position is `max + 1` (0 on an empty instance), matching Projects.sq's
     * `nextPosition`, so a new project sorts last until a reorder moves it.
     */
    override suspend fun insert(
        name: String,
        namePrefix: String,
        isPublic: Boolean,
        visibleToAllSignedIn: Boolean,
    ): ProjectRecord {
        val createdAt = now()
        return firestore.runTransaction { txn ->
            val existing = txn.get(collection()).get().documents
            val position = (existing.mapNotNull { it.getLong(POSITION) }.maxOrNull() ?: -1L) + 1L
            val id = counters.next(txn, COUNTER).getValue(COUNTER)
            writeInTransaction(txn, id, name, namePrefix, isPublic, visibleToAllSignedIn, position, createdAt)
        }.await()
    }

    /**
     * Write one project document at a pre-allocated [id] and [position] inside a
     * transaction the caller already owns, returning the record it becomes.
     *
     * The single home of the project document's shape, shared by [insert] (which
     * allocates the id and position within its own transaction) and
     * [FirestoreProjectRepository.create] (which allocates them alongside a block of
     * vocabulary ids so the whole board seeds atomically). Pure write — the caller
     * has already done every read the transaction needs.
     */
    internal fun writeInTransaction(
        txn: Transaction,
        id: Long,
        name: String,
        namePrefix: String,
        isPublic: Boolean,
        visibleToAllSignedIn: Boolean,
        position: Long,
        createdAt: Long,
    ): ProjectRecord {
        txn.set(
            doc(id),
            mapOf(
                ID to id,
                NAME to name,
                NAME_FOLD to name.lowercase(),
                NAME_PREFIX to namePrefix,
                IS_PUBLIC to isPublic,
                VISIBLE_TO_ALL_SIGNED_IN to visibleToAllSignedIn,
                // Defaults a fresh row carries — see the class preamble. The two
                // forum flags are written off and read off (LNL-190); the fields
                // stay so a re-enable has somewhere to put the answer.
                DISCUSSIONS to PROJECT_FORUM_FEATURES_ENABLED,
                MESSAGES to PROJECT_FORUM_FEATURES_ENABLED,
                REQUIRE_LABEL to false,
                REQUIRE_COMPONENT to false,
                REQUIRE_FIXED_VERSION to false,
                SHOW_ISSUE_AUTHOR to false,
                POSITION to position,
                CREATED_AT to createdAt,
                ACTIVE_SPRINT to null,
                REPO_OWNER to null,
                REPO_NAME to null,
                TOKEN_ENV to null,
                TOKEN_LITERAL to null,
            ),
        )
        return ProjectRecord(
            id = id,
            name = name,
            namePrefix = namePrefix,
            isPublic = isPublic,
            visibleToAllSignedIn = visibleToAllSignedIn,
            discussionsEnabled = PROJECT_FORUM_FEATURES_ENABLED,
            messagesEnabled = PROJECT_FORUM_FEATURES_ENABLED,
            requireLabel = false,
            requireComponent = false,
            requireFixedVersionOnResolve = false,
            showIssueAuthor = false,
            createdAt = createdAt,
        )
    }

    override suspend fun update(id: Long, name: String, namePrefix: String, isPublic: Boolean, visibleToAllSignedIn: Boolean) {
        doc(id).update(
            mapOf(
                NAME to name,
                NAME_FOLD to name.lowercase(),
                NAME_PREFIX to namePrefix,
                IS_PUBLIC to isPublic,
                VISIBLE_TO_ALL_SIGNED_IN to visibleToAllSignedIn,
            ),
        ).await()
    }

    override suspend fun setFeatures(id: Long, discussionsEnabled: Boolean, messagesEnabled: Boolean) {
        doc(id).update(mapOf(DISCUSSIONS to discussionsEnabled, MESSAGES to messagesEnabled)).await()
    }

    override suspend fun setRequirements(
        id: Long,
        requireLabel: Boolean,
        requireComponent: Boolean,
        requireFixedVersionOnResolve: Boolean,
    ) {
        doc(id).update(
            mapOf(
                REQUIRE_LABEL to requireLabel,
                REQUIRE_COMPONENT to requireComponent,
                REQUIRE_FIXED_VERSION to requireFixedVersionOnResolve,
            ),
        ).await()
    }

    override suspend fun setShowIssueAuthor(id: Long, showIssueAuthor: Boolean) {
        doc(id).update(mapOf(SHOW_ISSUE_AUTHOR to showIssueAuthor)).await()
    }

    override suspend fun delete(id: Long) {
        doc(id).delete().await()
    }

    /**
     * Rewrite the whole instance's project order in one batch — like the SQLite
     * store's transaction, so no reader ever sees two projects sharing a position.
     */
    override suspend fun setOrder(ids: List<Long>) {
        val batch = firestore.batch()
        ids.forEachIndexed { index, id -> batch.update(doc(id), POSITION, index.toLong()) }
        batch.commit().await()
    }

    override suspend fun findById(id: Long): ProjectRecord? =
        doc(id).get().await().takeIf { it.exists() }?.toRecord()

    /** Case-insensitive, via the denormalised `nameFold` — see the class preamble. */
    override suspend fun findByName(name: String): ProjectRecord? =
        collection().whereEqualTo(NAME_FOLD, name.lowercase()).limit(1).get().await()
            .documents.firstOrNull()?.toRecord()

    /** Every project, in the order a system administrator arranged (position, 0 first). */
    override suspend fun selectAll(): List<ProjectRecord> =
        collection().get().await().documents
            .sortedBy { it.getLong(POSITION) ?: 0L }
            .map { it.toRecord() }

    override suspend fun activeSprintId(id: Long): Long? =
        doc(id).get().await().getLong(ACTIVE_SPRINT)

    /**
     * Point the project at a sprint, or null for none.
     *
     * A merge-write rather than an update, so it can create the `activeSprintId`
     * field on a project document that has none — including one the sprint contract
     * seeded by id alone. The rest of the document is left untouched.
     */
    override suspend fun setActiveSprint(id: Long, sprintId: Long?) {
        doc(id).set(mapOf(ACTIVE_SPRINT to sprintId), SetOptions.merge()).await()
    }

    /**
     * The linked repository and token source, or null when the project row itself
     * is gone. A configured-but-empty project (freshly inserted) reads back as a
     * non-null config with no repository and [TokenSource.None] — the SQLite
     * behaviour the contract pins.
     */
    override suspend fun repositoryConfig(id: Long): RepositoryConfig? {
        val snap = doc(id).get().await()
        if (!snap.exists()) return null
        val owner = snap.getString(REPO_OWNER)
        val repoName = snap.getString(REPO_NAME)
        val literal = snap.getString(TOKEN_LITERAL)
        val env = snap.getString(TOKEN_ENV)
        return RepositoryConfig(
            repository = owner?.let { o -> repoName?.let { n -> RepositoryRef(o, n) } },
            // The two token fields are mutually exclusive by construction, exactly as
            // in the SQLite store: a literal wins only because an env name cannot be
            // set alongside it. See setRepositoryConfig and TokenSource.
            token = when {
                literal != null -> TokenSource.Literal(literal)
                env != null -> TokenSource.Env(env)
                else -> TokenSource.None
            },
        )
    }

    /** Link a repository, flattening the one [TokenSource] into two exclusive fields. */
    override suspend fun setRepositoryConfig(id: Long, config: RepositoryConfig) {
        doc(id).update(
            mapOf(
                REPO_OWNER to config.repository?.owner,
                REPO_NAME to config.repository?.name,
                TOKEN_ENV to (config.token as? TokenSource.Env)?.variableName,
                TOKEN_LITERAL to (config.token as? TokenSource.Literal)?.token,
            ),
        ).await()
    }

    /**
     * The `projects` collection's shape. `internal` — not private — because
     * [FirestoreProjectRepository] reads the counter, collection and position field
     * to allocate a project id alongside its vocabulary block in one transaction.
     */
    internal companion object {
        const val COLLECTION = "projects"
        const val COUNTER = "projects"

        const val ID = "id"
        const val NAME = "name"
        const val NAME_FOLD = "nameFold"
        const val NAME_PREFIX = "namePrefix"
        const val IS_PUBLIC = "isPublic"
        const val VISIBLE_TO_ALL_SIGNED_IN = "visibleToAllSignedIn"
        const val DISCUSSIONS = "discussionsEnabled"
        const val MESSAGES = "messagesEnabled"
        const val REQUIRE_LABEL = "requireLabel"
        const val REQUIRE_COMPONENT = "requireComponent"
        const val REQUIRE_FIXED_VERSION = "requireFixedVersionOnResolve"
        const val SHOW_ISSUE_AUTHOR = "showIssueAuthor"
        const val POSITION = "position"
        const val CREATED_AT = "createdAt"
        const val ACTIVE_SPRINT = "activeSprintId"
        const val REPO_OWNER = "repositoryOwner"
        const val REPO_NAME = "repositoryName"
        const val TOKEN_ENV = "githubTokenEnv"
        const val TOKEN_LITERAL = "githubTokenLiteral"
    }
}

private fun DocumentSnapshot.toRecord(): ProjectRecord = ProjectRecord(
    id = getLong("id")!!,
    name = getString("name").orEmpty(),
    namePrefix = getString("namePrefix").orEmpty(),
    isPublic = getBoolean("isPublic") ?: false,
    // Absent on a doc written before the middle read tier existed — defaults false,
    // the members-only/public behaviour it had then, exactly like the SQLite column's
    // DEFAULT 0. See LNL-138 and the class preamble on why absent = default here.
    visibleToAllSignedIn = getBoolean("visibleToAllSignedIn") ?: false,
    // Not read from the document at all: discussions and private messages are
    // retired, so a doc written while the switches still existed reads off (LNL-190).
    discussionsEnabled = PROJECT_FORUM_FEATURES_ENABLED,
    messagesEnabled = PROJECT_FORUM_FEATURES_ENABLED,
    requireLabel = getBoolean("requireLabel") ?: false,
    requireComponent = getBoolean("requireComponent") ?: false,
    requireFixedVersionOnResolve = getBoolean("requireFixedVersionOnResolve") ?: false,
    // Absent on a doc written before this display flag existed — defaults false, the
    // hidden-author behaviour it had then, exactly like the SQLite column's DEFAULT 0.
    showIssueAuthor = getBoolean("showIssueAuthor") ?: false,
    createdAt = getLong("createdAt") ?: 0L,
)
