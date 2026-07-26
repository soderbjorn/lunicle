/**
 * The store graph, assembled once per boot by the selected [DatabaseBackend].
 *
 * This is the seam LNL-122 turns on. `Application.module` no longer constructs
 * thirty concrete SQLite stores directly; it asks for a [StoreGraph], and gets one
 * of two shapes:
 *
 *  - [sqliteStoreGraph] — exactly today's construction: [openDatabase], the concrete
 *    `*Store` classes over the one `LunicleDatabase`, a disk attachment blob store,
 *    and a WAL-checkpointing close. Byte-for-byte what the server has always run.
 *  - [firestoreStoreGraph] — a [FirestoreProvider] and one `Firestore*Store` per
 *    domain over `provider.firestore`, a GCS attachment blob store, and the injected
 *    seams (the joins a document backend cannot express) wired to the sibling
 *    Firestore stores. Reached only on `LUNICLE_DB_BACKEND=firestore`, so nothing
 *    GCP is touched on the Railway/SQLite path — the [FirestoreProvider] rule.
 *
 * Every field the module reads below is one of the `store.*` interfaces, so the
 * repositories, notifiers, routes and startup sweeps built on top are identical
 * whichever backend produced the graph. The two backend-specific *orchestrators*
 * that are not plain stores — project provisioning, the vocabulary editor, the
 * sprint lifecycle, the statistics compiler and the e-mail-code service — are built
 * inside each branch and exposed here through their interfaces too.
 *
 * @property migrate the schema bring-up to run in the startup coroutine before
 *   serving — [migrateFirestore] on the Firestore branch, a no-op on SQLite (whose
 *   schema walk already ran inside [openDatabase]).
 * @property close the shutdown hook — checkpoints and closes the SQLite driver, or
 *   closes the Firestore client's gRPC channels.
 *
 * @see DatabaseBackend
 * @see FirestoreProvider
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.store.AttachmentStore
import se.soderbjorn.lunicle.store.CommentStore
import se.soderbjorn.lunicle.store.ComponentStore
import se.soderbjorn.lunicle.store.ConversationStore
import se.soderbjorn.lunicle.store.ForumCommentStore
import se.soderbjorn.lunicle.store.ForumPostStore
import se.soderbjorn.lunicle.store.ForumStore
import se.soderbjorn.lunicle.store.InstanceSettingsStore
import se.soderbjorn.lunicle.store.IssueEventStore
import se.soderbjorn.lunicle.store.IssueStore
import se.soderbjorn.lunicle.store.LabelStore
import se.soderbjorn.lunicle.store.MessageStore
import se.soderbjorn.lunicle.store.NotificationStore
import se.soderbjorn.lunicle.store.OAuthClientStore
import se.soderbjorn.lunicle.store.OAuthCodeStore
import se.soderbjorn.lunicle.store.OAuthLoginStateStore
import se.soderbjorn.lunicle.store.OAuthTokenStore
import se.soderbjorn.lunicle.store.PriorityStore
import se.soderbjorn.lunicle.store.ProjectProvisioning
import se.soderbjorn.lunicle.store.ProjectStore
import se.soderbjorn.lunicle.store.ReadStore
import se.soderbjorn.lunicle.store.ResolutionStore
import se.soderbjorn.lunicle.store.RoleStore
import se.soderbjorn.lunicle.store.SessionStore
import se.soderbjorn.lunicle.store.StatisticsStore
import se.soderbjorn.lunicle.store.StatusStore
import se.soderbjorn.lunicle.store.SprintStore
import se.soderbjorn.lunicle.store.SubscriptionStore
import se.soderbjorn.lunicle.store.UiSettingsStore
import se.soderbjorn.lunicle.store.UserStore
import se.soderbjorn.lunicle.store.VersionStore

/**
 * Everything the module wires above the persistence layer, as interfaces, plus the
 * two lifecycle hooks. Built by [sqliteStoreGraph] or [firestoreStoreGraph].
 */
internal class StoreGraph(
    val sessions: SessionStore,
    val users: UserStore,
    val roles: RoleStore,
    val projects: ProjectStore,
    val labels: LabelStore,
    val components: ComponentStore,
    val statuses: StatusStore,
    val priorities: PriorityStore,
    val resolutions: ResolutionStore,
    val versions: VersionStore,
    val issues: IssueStore,
    val comments: CommentStore,
    val issueEvents: IssueEventStore,
    val attachments: AttachmentStore,
    val subscriptions: SubscriptionStore,
    val reads: ReadStore,
    val notificationStore: NotificationStore,
    val uiSettings: UiSettingsStore,
    val instanceSettings: InstanceSettingsStore,
    val oauthClients: OAuthClientStore,
    val oauthLoginStates: OAuthLoginStateStore,
    val oauthCodes: OAuthCodeStore,
    val oauthTokens: OAuthTokenStore,
    val forums: ForumStore,
    val forumPosts: ForumPostStore,
    val forumComments: ForumCommentStore,
    val conversations: ConversationStore,
    val messages: MessageStore,
    // The backend-specific orchestrators, each behind its interface.
    val attachmentRepository: AttachmentRepository,
    val projectRepository: ProjectProvisioning,
    val vocabularies: se.soderbjorn.lunicle.store.VocabularyStore,
    val sprints: SprintStore,
    val statistics: StatisticsStore,
    val emailCodes: EmailCodeService,
    val migrate: suspend () -> Unit,
    val close: () -> Unit,
)

/**
 * The SQLite graph — today's construction, extracted verbatim. Opens the database
 * (which creates or migrates the schema — see [openDatabase]), builds the concrete
 * `*Store` classes over it, a disk blob store under the attachments directory, and a
 * close that checkpoints the WAL back into the file.
 */
internal fun sqliteStoreGraph(
    location: DatabaseLocation,
    emailSender: EmailTransport?,
    emailBaseUrl: String,
): StoreGraph {
    val opened = openDatabase(location)
    val database = opened.database

    val sessions = SessionStore(database)
    val users = UserStore(database)
    val roles = RoleStore(database)
    val projects = ProjectStore(database)
    val labels = LabelStore(database)
    val components = ComponentStore(database)
    val statuses = StatusStore(database)
    val priorities = PriorityStore(database)
    val resolutions = ResolutionStore(database)
    val sprintGateway = SprintStore(database)
    val versions = VersionStore(database)
    val issues = IssueStore(database)
    val comments = CommentStore(database)
    val issueEvents = IssueEventStore(database)
    val attachments = AttachmentStore(database)
    val subscriptions = SubscriptionStore(database)
    val reads = ReadStore(database)
    val notificationStore = NotificationStore(database)
    val uiSettings = UiSettingsStore(database)
    val instanceSettings = InstanceSettingsStore(database)
    val oauthClients = OAuthClientStore(database)
    val oauthLoginStates = OAuthLoginStateStore(database)
    val oauthCodes = OAuthCodeStore(database)
    val oauthTokens = OAuthTokenStore(database)
    val forums = ForumStore(database)
    val forumPosts = ForumPostStore(database)
    val forumComments = ForumCommentStore(database)
    val conversations = ConversationStore(database)
    val messages = MessageStore(database)

    val attachmentRepository = AttachmentRepository(attachments, location.attachmentsDirectory)
    val projectRepository = ProjectRepository(database, projects, attachmentRepository, attachments)
    val vocabularies =
        VocabularyRepository(
            database, labels, components, statuses, priorities, resolutions, sprintGateway, versions, issues,
        )
    val sprints = SprintRepository(database, sprintGateway, projects, issues, statuses)
    val statistics = StatisticsRepository(
        projects = projects,
        snapshots = ProjectStatisticsStore(database),
        issueCounts = IssueStatisticsStore(database),
    )
    val emailCodes = EmailCodeService(database, emailSender, emailBaseUrl)

    return StoreGraph(
        sessions = sessions,
        users = users,
        roles = roles,
        projects = projects,
        labels = labels,
        components = components,
        statuses = statuses,
        priorities = priorities,
        resolutions = resolutions,
        versions = versions,
        issues = issues,
        comments = comments,
        issueEvents = issueEvents,
        attachments = attachments,
        subscriptions = subscriptions,
        reads = reads,
        notificationStore = notificationStore,
        uiSettings = uiSettings,
        instanceSettings = instanceSettings,
        oauthClients = oauthClients,
        oauthLoginStates = oauthLoginStates,
        oauthCodes = oauthCodes,
        oauthTokens = oauthTokens,
        forums = forums,
        forumPosts = forumPosts,
        forumComments = forumComments,
        conversations = conversations,
        messages = messages,
        attachmentRepository = attachmentRepository,
        projectRepository = projectRepository,
        vocabularies = vocabularies,
        sprints = sprints,
        statistics = statistics,
        emailCodes = emailCodes,
        // The SQLite schema walk already ran inside openDatabase; nothing to do here.
        migrate = {},
        close = { opened.close() },
    )
}

/**
 * The Firestore graph. Builds the [FirestoreProvider] (whose client object is
 * created here, once, but does no network until the first store operation), one
 * `Firestore*Store` per domain, a GCS blob store, and the injected seams — the joins
 * a document backend cannot express — wired to the sibling Firestore stores.
 *
 * The seams, one per LNL-122 injection point:
 *  1. session `resolveUser` → the user store's find-by-id.
 *  2. read `publishedMessagesFor` / `publishedPostsIn` → the conversation/message and
 *     forum/forum-post stores.
 *  3. subscription contact lookup → the user store (addressless subscribers kept —
 *     LNL-109).
 *  4. OAuth-token `canUseMcp` → the user store's [UserRecord.canUseMcp], the same gate
 *     the SQLite path applies.
 *  5. attachment scope resolver → the issue/comment/forum/message stores, denormalising
 *     each attachment's ancestry so the cascade `keysFor…` reads stay one equality each.
 *  6. statistics `repositoryConfigFor` → the project store, as the SQLite repository reads it.
 *  7. e-mail codes → [FirestoreEmailCodeStore] behind the store-injecting [EmailCodeService] ctor.
 */
internal fun firestoreStoreGraph(
    emailSender: EmailTransport?,
    emailBaseUrl: String,
): StoreGraph {
    val provider = FirestoreProvider()
    // Creates the client object once (no network until the first RPC). Reached only
    // on the firestore backend, so the Railway/SQLite path never touches GCP.
    return firestoreStoreGraph(provider.firestore, emailSender, emailBaseUrl, close = { provider.close() })
}

/**
 * The Firestore graph over an already-open client — the seam wiring itself, shared
 * by the production entry point above (which opens a [FirestoreProvider]) and the
 * boot smoke test (which passes an emulator client in an isolated namespace, so it
 * exercises the exact production wiring). [close] is the provider's close in
 * production and a no-op in the test, which owns the client's lifetime.
 */
internal fun firestoreStoreGraph(
    firestore: Firestore,
    emailSender: EmailTransport?,
    emailBaseUrl: String,
    close: () -> Unit = {},
): StoreGraph {
    // ── Leaf domain stores (no seams) ─────────────────────────────────────────
    val users = FirestoreUserStore(firestore)
    val roles = FirestoreRoleStore(firestore)
    val projects = FirestoreProjectStore(firestore)
    val labels = FirestoreLabelStore(firestore)
    val components = FirestoreComponentStore(firestore)
    val statuses = FirestoreStatusStore(firestore)
    val priorities = FirestorePriorityStore(firestore)
    val resolutions = FirestoreResolutionStore(firestore)
    val versions = FirestoreVersionStore(firestore)
    val issues = FirestoreIssueStore(firestore)
    val comments = FirestoreCommentStore(firestore)
    val issueEvents = FirestoreIssueEventStore(firestore)
    val forums = FirestoreForumStore(firestore)
    val forumPosts = FirestoreForumPostStore(firestore)
    val forumComments = FirestoreForumCommentStore(firestore)
    val conversations = FirestoreConversationStore(firestore)
    val messages = FirestoreMessageStore(firestore)
    val notificationStore = FirestoreNotificationStore(firestore)
    val uiSettings = FirestoreUiSettingsStore(firestore)
    val instanceSettings = FirestoreInstanceSettingsStore(firestore)
    val oauthClients = FirestoreOAuthClientStore(firestore)
    val oauthLoginStates = FirestoreOAuthLoginStateStore(firestore)
    val oauthCodes = FirestoreOAuthCodeStore(firestore)

    // ── Seam 1: session → user find-by-id ─────────────────────────────────────
    val sessions = FirestoreSessionStore(firestore, resolveUser = { userId -> users.findById(userId) })

    // ── Seam 2: read marks → published messages / posts across sibling stores ──
    val reads = FirestoreReadStore(
        firestore,
        publishedMessagesFor = { userId ->
            // Every published message in every conversation the user is in — the SQLite
            // join's `conversation_participants` × `messages (is_draft = 0)`.
            conversations.participantsForUser(userId).keys.flatMap { conversationId ->
                messages.forConversation(conversationId).map {
                    FirestoreReadStore.UnreadMessage(conversationId, it.id, it.author.accountId)
                }
            }
        },
        publishedPostsIn = { projectIds ->
            // Every published post in the forums of those projects — the join's
            // `forums` × `forum_posts (is_draft = 0)`.
            projectIds.flatMap { projectId ->
                forums.forProject(projectId).flatMap { forum ->
                    forumPosts.forForum(forum.id)
                        .filterNot { it.post.isDraft }
                        .map { FirestoreReadStore.UnreadPost(forum.id, it.post.createdAt, it.post.author.accountId) }
                }
            }
        },
    )

    // ── Seam 3: subscription audience → user name/address (addressless kept) ───
    val subscriptions = FirestoreSubscriptionStore(
        firestore,
        resolveContacts = { ids ->
            ids.mapNotNull { users.findById(it) }
                .associate { it.id to FirestoreSubscriptionStore.Contact(it.resolvedName, it.email) }
        },
    )

    // ── Seam 4: OAuth-token MCP gate → the user's canUseMcp ────────────────────
    val oauthTokens = FirestoreOAuthTokenStore(
        firestore,
        canUseMcp = { userId -> users.findById(userId)?.canUseMcp == true },
    )

    // ── Seam 5: attachment scope resolver → ancestry across sibling stores ─────
    val scopeResolver = object : AttachmentScopeResolver {
        override suspend fun forIssue(issueId: Long): AttachmentScope {
            val issue = issues.findById(issueId)
            return AttachmentScope(projectId = issue?.projectId, issueId = issueId)
        }

        override suspend fun forComment(commentId: Long): AttachmentScope {
            val comment = comments.findById(commentId) ?: return AttachmentScope()
            val issue = issues.findById(comment.issueId)
            return AttachmentScope(projectId = issue?.projectId, issueId = comment.issueId)
        }

        override suspend fun forForumPost(forumPostId: Long): AttachmentScope {
            val post = forumPosts.findById(forumPostId) ?: return AttachmentScope()
            val forum = forums.findById(post.forumId)
            return AttachmentScope(projectId = forum?.projectId, forumId = post.forumId, postId = forumPostId)
        }

        override suspend fun forForumComment(forumCommentId: Long): AttachmentScope {
            val comment = forumComments.findById(forumCommentId) ?: return AttachmentScope()
            val post = forumPosts.findById(comment.postId)
            val forum = post?.let { forums.findById(it.forumId) }
            return AttachmentScope(projectId = forum?.projectId, forumId = post?.forumId, postId = comment.postId)
        }

        override suspend fun forMessage(messageId: Long): AttachmentScope {
            val message = messages.findById(messageId) ?: return AttachmentScope()
            return AttachmentScope(conversationId = message.conversationId)
        }
    }
    val attachments = FirestoreAttachmentStore(firestore, scopeResolver)

    // ── Attachment bytes live in GCS on this backend ───────────────────────────
    val attachmentRepository = AttachmentRepository(attachments, GcsAttachmentBlobStore())

    // ── Backend-specific orchestrators over the Firestore stores ───────────────
    val projectRepository = FirestoreProjectRepository(
        firestore = firestore,
        projects = projects,
        attachments = attachmentRepository,
        attachmentStore = attachments,
    )
    // The settings editor's vocabulary path. It writes to the *same* `vocabulary`
    // collection, `_counters/vocabulary` counter and field constants the five
    // concrete board stores (statuses/priorities/… above) read — including
    // `requiresResolution` on status rows — so a column added or renamed in settings
    // is read back unchanged by the board, and vice versa. That editor↔board interop
    // is pinned by FirestoreVocabularyInteropTest (LNL-132).
    val vocabularies = FirestoreVocabularyStore(firestore, issues)
    val sprints = FirestoreSprintStore(firestore, projects, issues)
    // versions is a leaf vocabulary store like labels; its rows live in the same
    // `vocabulary` collection the board reads, so the editor↔board interop holds.
    // Referenced here so it is wired into the graph below and pinned by the interop test.
    // ── Seam 6: statistics → project repository config ─────────────────────────
    val statistics = FirestoreStatisticsStore(
        firestore,
        repositoryConfigFor = { projectId -> projects.repositoryConfig(projectId) },
    )
    // ── Seam 7: e-mail codes over the Firestore store ──────────────────────────
    val emailCodes = EmailCodeService(FirestoreEmailCodeStore(firestore), emailSender, emailBaseUrl)

    return StoreGraph(
        sessions = sessions,
        users = users,
        roles = roles,
        projects = projects,
        labels = labels,
        components = components,
        statuses = statuses,
        priorities = priorities,
        resolutions = resolutions,
        versions = versions,
        issues = issues,
        comments = comments,
        issueEvents = issueEvents,
        attachments = attachments,
        subscriptions = subscriptions,
        reads = reads,
        notificationStore = notificationStore,
        uiSettings = uiSettings,
        instanceSettings = instanceSettings,
        oauthClients = oauthClients,
        oauthLoginStates = oauthLoginStates,
        oauthCodes = oauthCodes,
        oauthTokens = oauthTokens,
        forums = forums,
        forumPosts = forumPosts,
        forumComments = forumComments,
        conversations = conversations,
        messages = messages,
        attachmentRepository = attachmentRepository,
        projectRepository = projectRepository,
        vocabularies = vocabularies,
        sprints = sprints,
        statistics = statistics,
        emailCodes = emailCodes,
        migrate = { migrateFirestore(firestore) },
        close = close,
    )
}
