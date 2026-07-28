/**
 * The two statistics routes: read the last snapshot, and ask for a new one.
 *
 * Its own file for the reason [SprintRoutes] has one — a property worth reading
 * off a file rather than inferring from handlers scattered among forty. Here that
 * property is the *split gate*: the numbers are readable by anyone who may read
 * the project, while the repository that feeds them is configured by an admin
 * through a different file entirely. Two permissions, in one feature, drawn in
 * two places.
 *
 * ── The consequence of the read gate, stated ───────────────────────────────
 *
 * A commit count is a fact about a repository, served to everyone who may read
 * the *project*. For a private repository that tells every signed-in reader that
 * it exists and roughly how busy it is. That is an accepted trade rather than an
 * oversight: the alternative — admin-only statistics — makes the feature useless
 * to the team it was built for. It is why the token is read-only and scoped to
 * one repository; see GitHubStatistics.
 *
 * @see StatisticsRepository
 * @see StatisticsState
 */
package se.soderbjorn.lunicle

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.ProjectStatistics
import se.soderbjorn.lunicle.clientserver.StatisticWindow
import se.soderbjorn.lunicle.clientserver.StatisticsState
import se.soderbjorn.lunicle.clientserver.TokenModes

/** Mount the statistics routes. Called by [boardRoutes]. */
fun Route.statisticsRoutes(deps: BoardDependencies) {
    /**
     * The last snapshot, compiling nothing.
     *
     * Answers from SQLite and returns at once, so the dialog paints before any
     * call to github.com is attempted. Whether those numbers have aged out rides
     * along as [StatisticsState.isStale], and the client acts on it by posting to
     * the route below.
     */
    get("${ApiRoutes.PROJECTS}/{id}/statistics") {
        val (statistics, projectId) = call.statisticsScope(deps) ?: return@get
        val snapshot = statistics.cached(projectId)
        call.respond(
            StatisticsState(
                statistics = snapshot?.toWire(),
                isStale = statistics.isStale(snapshot),
            ),
        )
    }

    /**
     * Recompile, unless somebody already did inside the window.
     *
     * Not admin-gated, and it does make outbound network calls — which is only
     * safe because the refusal to recompile lives in [StatisticsRepository] and
     * cannot be bypassed from here. A caller hammering this route gets the same
     * cached numbers back at no cost to GitHub's rate limit; see `refresh`.
     *
     * It may take as long as GitHub does. Nothing here imposes a deadline: the
     * client is showing progress and a truncated answer would be written to the
     * cache as though it were a whole one.
     */
    post("${ApiRoutes.PROJECTS}/{id}/statistics/refresh") {
        val (statistics, projectId) = call.statisticsScope(deps) ?: return@post
        val snapshot = statistics.refresh(projectId)
        call.respond(StatisticsState(statistics = snapshot.toWire(), isStale = false))
    }
}

/**
 * Resolve the project and the repository together, or respond and return null.
 *
 * Both routes need the same two things and would otherwise repeat the same four
 * lines — including the 404, which is the line it would be worst to get subtly
 * different between them.
 *
 * A deployment with no [BoardDependencies.statistics] answers 404, the same as a
 * project that does not exist or may not be read. Deliberately the same status:
 * "this server has no statistics" is not a thing a caller needs to be able to
 * tell apart from "no such project", and 404 is already what this file's other
 * refusal looks like — see [readableProject], which withholds the difference
 * between absent and forbidden for the same reason.
 */
private suspend fun io.ktor.server.application.ApplicationCall.statisticsScope(
    deps: BoardDependencies,
): Pair<se.soderbjorn.lunicle.store.StatisticsStore, Long>? {
    val statistics = deps.statistics ?: run {
        respond(HttpStatusCode.NotFound, "No such project.")
        return null
    }
    val projectId = longParam("id") ?: run {
        respond(HttpStatusCode.NotFound, "No such project.")
        return null
    }
    val project = readableProject(deps, caller(deps), projectId) ?: return null
    return statistics to project.id
}

/**
 * Validate the project dialog's two repository fields, or respond 400 and return
 * null.
 *
 * Lives here rather than in [BoardRoutes] beside its one caller, because what
 * these fields mean and what makes one acceptable is this feature's business —
 * and because the prefix rule is a security boundary that should be readable in
 * the same file as the reasoning behind it. See [parseTokenEnvName].
 *
 * The messages name the field and say what would be right. A validation refusal
 * whose message is "invalid input" makes the user guess which of two fields, and
 * the guess is wrong half the time.
 */
internal suspend fun io.ktor.server.application.ApplicationCall.parseRepositoryConfig(
    body: se.soderbjorn.lunicle.clientserver.ProjectUpdate,
    existing: RepositoryConfig?,
): RepositoryConfig? {
    val repository = if (body.repositoryUrl.isBlank()) {
        null
    } else {
        parseRepositoryUrl(body.repositoryUrl) ?: run {
            respond(
                HttpStatusCode.BadRequest,
                "That does not look like a GitHub repository. Use the repository's URL, or owner/name.",
            )
            return null
        }
    }

    // The token, from whichever source the radio chose. An unknown mode is a bug
    // in a client we ship, not something a stale one sends, so it is a 400 rather
    // than a silent fall-through to "none" that would quietly unlink a token.
    val token = when (body.githubTokenMode) {
        TokenModes.NONE -> TokenSource.None
        TokenModes.ENV ->
            if (body.githubTokenEnv.isBlank()) {
                TokenSource.None
            } else {
                parseTokenEnvName(body.githubTokenEnv)?.let(TokenSource::Env) ?: run {
                    respond(
                        HttpStatusCode.BadRequest,
                        "The token variable must be named ${GITHUB_TOKEN_ENV_PREFIX}something, " +
                            "using capital letters, digits and underscores.",
                    )
                    return null
                }
            }
        TokenModes.LITERAL -> parseLiteralToken(body.githubTokenLiteral, existing) ?: return null
        else -> {
            respond(HttpStatusCode.BadRequest, "There is no token source called \"${body.githubTokenMode}\".")
            return null
        }
    }

    // Half a configuration is a mistake rather than an intention — the same call
    // chooseEmailTransport makes about a half-configured mailer, arriving at a
    // stricter answer because there is somebody on the screen to tell. A token
    // with no repository can never be used, and silently keeping it would leave a
    // settings dialog showing a token that does nothing.
    if (repository == null && token != TokenSource.None) {
        respond(
            HttpStatusCode.BadRequest,
            "A token needs a repository to go with it. Add the repository, or clear the token.",
        )
        return null
    }
    return RepositoryConfig(repository, token)
}

/**
 * Resolve the literal-token field into a [TokenSource], or respond 400 and return
 * null.
 *
 * ── Blank means keep, and that is the whole subtlety ────────────────────────
 *
 * The literal token is write-only: the dialog never receives it, so the field is
 * empty on every save whether or not one is stored. Reading a blank field as
 * "clear the token" would wipe it the first time an owner edits any *other*
 * repository field, so a blank field means "keep whatever is stored" — the
 * project's existing literal if it had one, or [TokenSource.None] if it did not.
 * A non-blank field replaces it. This is the same bargain every write-only secret
 * field strikes; see ProjectUpdate.githubTokenLiteral.
 *
 * The only shape rule is no interior whitespace: a GitHub token has none, and a
 * value with a space in it is a paste accident worth catching on the screen rather
 * than sending to github.com to be rejected fifteen minutes later.
 */
private suspend fun io.ktor.server.application.ApplicationCall.parseLiteralToken(
    raw: String,
    existing: RepositoryConfig?,
): TokenSource? {
    val token = raw.trim()
    if (token.isEmpty()) {
        // Keep the stored literal, if there is one; otherwise this owner picked
        // "literal" and typed nothing, which is no token at all.
        return existing?.token as? TokenSource.Literal ?: TokenSource.None
    }
    if (token.any { it.isWhitespace() }) {
        respond(HttpStatusCode.BadRequest, "A GitHub token has no spaces in it — check the value you pasted.")
        return null
    }
    return TokenSource.Literal(token)
}

/**
 * A snapshot as the browser sees it.
 *
 * The sealed [CommitCounts] flattens into a nullable window plus a nullable
 * reason, because kotlinx.serialization's polymorphism would put a discriminator
 * on the wire and the client's question is only ever "is there a number, and if
 * not what do I say instead".
 *
 * The two are independent rather than exclusive since LNL-175: a window with a
 * reason beside it means "these are the last counts GitHub answered with, and
 * here is why they are not this moment's". The browser needed no change for that
 * — the table row is drawn from the window and the note from the reason, and
 * neither ever asked about the other. See CommitCounts.Counted.notRefreshed.
 */
private fun StatisticsSnapshot.toWire(): ProjectStatistics = ProjectStatistics(
    computedAt = computedAt,
    commits = (commits as? CommitCounts.Counted)
        ?.let { StatisticWindow(it.week, it.month, it.allTime) },
    commitsUnavailable = when (val counts = commits) {
        is CommitCounts.Unavailable -> counts.reason
        is CommitCounts.Counted -> counts.notRefreshed
    },
    issuesCreated = issuesCreated.toWire(),
    issuesClosed = issuesClosed.toWire(),
)

private fun WindowCounts.toWire(): StatisticWindow = StatisticWindow(week, month, allTime)
