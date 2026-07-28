/**
 * Counting commits on GitHub.
 *
 * Three things live here: the parse that turns what an admin typed into an
 * owner/name pair, the rule about which environment variable may hold a token,
 * and the client that asks github.com how many commits landed in a window.
 *
 * Configuration follows the [EmailTransport] bargain exactly — a feature nobody has
 * configured is off, never broken. There is one difference worth stating: an
 * absent Resend key disables a feature the user cannot see, whereas an absent
 * token here disables *part* of a screen the user is looking at. So "unavailable"
 * is a value that travels all the way to the browser with a reason attached,
 * rather than a silent null. See [CommitCounts].
 *
 * @see StatisticsRepository
 * @see createProviderHttpClient
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("GitHubStatistics")

/**
 * The prefix every token-holding environment variable name must carry.
 *
 * ── Why this exists ────────────────────────────────────────────────────────
 *
 * Without it, an admin who can type a variable name into the settings dialog can
 * type `LUNICLE_GOOGLE_CLIENT_SECRET`, or `LUNICLE_RESEND_API_KEY`, and this server
 * will read that value and send it to github.com in an `Authorization` header. GitHub logs
 * what it is sent. That turns a settings field into a way to post any of this
 * deployment's secrets to a third party, which is a much larger power than "may
 * configure a project".
 *
 * The prefix does not make the field safe against a determined admin — they can
 * set a variable with a conforming name to whatever they like, if they also
 * control the deployment. It makes it safe against the *field*: reading an
 * arbitrary existing secret is no longer one of the things the form can express.
 * That is the whole claim, and it is worth the four lines it costs.
 */
const val GITHUB_TOKEN_ENV_PREFIX = "LUNICLE_GITHUB_TOKEN_"

/** A repository, as the two path segments every GitHub API call needs. */
data class RepositoryRef(val owner: String, val name: String) {
    /** `owner/name`, which is how GitHub itself writes it and how the dialog shows it. */
    override fun toString(): String = "$owner/$name"
}

/**
 * Where a project's GitHub token comes from, if anywhere (LNL-107).
 *
 * ── Three sources, and why the sealed shape ─────────────────────────────────
 *
 * A token used to be one nullable string — the *name* of an environment variable
 * — because that was the only source the form could express. LNL-107 adds a
 * second, a literal token stored on the row, and keeps "none" as a first-class
 * state rather than a null: the settings dialog offers all three as an explicit
 * choice, and a sealed type is what stops "none" and "an env source with a blank
 * name" from being the same value two different pieces of code disagree about.
 *
 * The mode is not stored as its own column. At most one of the two value columns
 * on `projects` is ever non-null, so which source is configured is *read off*
 * which column holds a value (see ProjectStore.repositoryConfig) — which means an
 * existing row carrying only an env-variable name reads back as [Env] with no
 * back-fill, the property 25.sqm's sibling migration leans on.
 */
sealed interface TokenSource {
    /**
     * No token. A supported state, not a broken one: the commit counts go
     * unavailable and every other statistic still answers.
     */
    data object None : TokenSource

    /**
     * The token is the value of a named environment variable on the deployment,
     * resolved at read time — the original and still the safer source, because the
     * secret lives where deployment secrets already live and never touches this
     * database. The name is constrained to a prefix; see [parseTokenEnvName].
     *
     * @property variableName the *name* of the variable, never the token.
     */
    data class Env(val variableName: String) : TokenSource

    /**
     * The token itself, stored on the project row (LNL-107).
     *
     * The concession this feature makes, and the one worth naming out loud: a
     * literal token here is a secret in the database, in every backup of it, and
     * on the wire to anyone entitled to read the row — exactly what the env-name
     * source was shaped to avoid (see Projects.sq's github_token_env). It is
     * offered because a deployment that cannot set environment variables had no
     * way to link a private repository at all, and it is fenced accordingly: only
     * an owner may set or see that a project has one, and the value is write-only
     * on the wire — never echoed back, kept on a blank save. See
     * ProjectSettingsState.githubTokenMode.
     */
    data class Literal(val token: String) : TokenSource
}

/**
 * What an owner has configured for one project, or nothing.
 *
 * @property repository the repo, or null because none is linked.
 * @property token where the token comes from — possibly [TokenSource.None]. Never
 *   null: "no token" is a case of the sealed type, not an absence of one.
 */
data class RepositoryConfig(
    val repository: RepositoryRef?,
    val token: TokenSource,
)

/**
 * Turn what somebody pasted into an owner/name pair, or null if it is not one.
 *
 * Accepts the four spellings a person actually has on their clipboard: the browser
 * URL, that URL with a `.git` tail, the ssh remote, and the bare `owner/name` they
 * would type from memory. Anything else is refused rather than guessed at — a
 * wrong guess here becomes a 404 from GitHub fifteen minutes later, attributed to
 * the token rather than to the URL.
 *
 * Parsed once, at the moment somebody is still on the screen to be told. See
 * Projects.sq's repository_owner for why the pair is what gets stored.
 */
fun parseRepositoryUrl(raw: String): RepositoryRef? {
    val trimmed = raw.trim().removeSuffix("/")
    if (trimmed.isEmpty()) return null
    val path = when {
        // git@github.com:owner/repo.git — the ssh remote, whose separator is a
        // colon rather than a slash, so it cannot be handled as a URL path.
        trimmed.startsWith("git@") -> trimmed.substringAfter(':', "")
        trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
            trimmed.substringAfter("://").substringAfter('/', "")
        else -> trimmed
    }.removeSuffix(".git")

    val segments = path.split('/').filter { it.isNotBlank() }
    if (segments.size != 2) return null
    val (owner, name) = segments
    // GitHub's own rule for both: letters, digits, dot, dash, underscore. Checked
    // because these two go straight into a URL path, and a segment containing a
    // slash or a `..` would address something other than what was typed.
    val allowed = Regex("^[A-Za-z0-9._-]+$")
    if (!allowed.matches(owner) || !allowed.matches(name)) return null
    return RepositoryRef(owner, name)
}

/**
 * Check a token variable name against [GITHUB_TOKEN_ENV_PREFIX], returning it or
 * null.
 *
 * Upper-cased on the way in, because environment variables conventionally are and
 * an admin who typed the name in lower case meant the same variable. The rest of
 * the name is constrained to the characters a shell will actually export, so a
 * name that could never be set is refused on the screen rather than silently
 * resolving to nothing forever.
 */
fun parseTokenEnvName(raw: String): String? {
    val name = raw.trim().uppercase()
    if (!name.startsWith(GITHUB_TOKEN_ENV_PREFIX)) return null
    if (name.length == GITHUB_TOKEN_ENV_PREFIX.length) return null
    if (!Regex("^[A-Z0-9_]+$").matches(name)) return null
    return name
}

/**
 * Commits in each window, or the reason there are none to report.
 *
 * A sealed pair rather than three nullable integers plus a nullable string,
 * because exactly one of the two states is ever true and the type should say so.
 * The unavailable case carries a sentence fit to put on screen — see
 * [GitHubClient.commitCounts] for the rule about which sentence.
 */
sealed interface CommitCounts {
    /**
     * Numbers, and possibly a note saying they are older than the rest.
     *
     * ── Why a counted result can carry a reason (LNL-175) ──────────────────
     *
     * A compile that reaches github.com and is refused used to write
     * [Unavailable] over whatever the last compile had found, which *deleted the
     * commit counts from the dialog* — the row disappears, and stays gone for at
     * least the freshness window and for as long as the refusals continue. One
     * transient 5xx or a dropped connection was enough, and the only thing on
     * screen afterwards was a sentence about a token, beside no numbers.
     *
     * So the last good counts are carried forward instead, with the reason the
     * newer ones could not be had riding along in [notRefreshed]. The row keeps
     * its numbers and the note keeps its sentence; the two travel to the browser
     * as separate fields already (see StatisticsRoutes.toWire), so nothing in the
     * view had to learn a new state.
     *
     * @property notRefreshed why these are not this moment's numbers, in a
     *   sentence fit to show a user, or null because they are. Not a silent
     *   fallback: showing stale figures *as current* is the thing this feature's
     *   age label exists to prevent, so a carried-forward count always says so.
     */
    data class Counted(
        val week: Long,
        val month: Long,
        val allTime: Long,
        val notRefreshed: String? = null,
    ) : CommitCounts

    /** @property reason shown to the user, verbatim. Never a raw provider error. */
    data class Unavailable(val reason: String) : CommitCounts
}

/**
 * This attempt's counts, or the last good ones with this attempt's reason on them.
 *
 * The one rule LNL-175 adds, shared by both backends' compile step so they cannot
 * drift: **a refusal from github.com must not delete the numbers the last
 * successful call found.** Applied only to what GitHub itself answered — the
 * configuration absences (no repository linked, no token, a variable naming
 * nothing) are states where the row is *meant* to go away, and carrying counts
 * through an unlinked repository would leave a number on screen for a repository
 * this project no longer tracks.
 *
 * @receiver what this compile got out of GitHub.
 * @param previous the commit half of the snapshot being replaced, if there is one.
 */
internal fun CommitCounts.orLastKnown(previous: CommitCounts?): CommitCounts {
    if (this !is CommitCounts.Unavailable) return this
    val last = previous as? CommitCounts.Counted ?: return this
    return CommitCounts.Counted(last.week, last.month, last.allTime, notRefreshed = reason)
}

/**
 * Counts commits for a repository, however it cares to.
 *
 * One method, extracted purely so [StatisticsRepository] can be tested without a
 * network — and named for what it does rather than for GitHub, because that is
 * the only thing the repository above it needs to know. [GitHubClient] is the
 * one real implementation.
 *
 * Never throws, by contract: an implementation reports its failures as
 * [CommitCounts.Unavailable] rather than by raising, because the caller has other
 * numbers that answered fine and must not lose them to this one.
 */
fun interface CommitCounter {
    suspend fun commitCounts(
        repository: RepositoryRef,
        token: String,
        weekStart: Long,
        monthStart: Long,
    ): CommitCounts
}

/** GitHub's repository metadata. Only the field we read. */
@Serializable
private data class RepositoryMetadata(
    @SerialName("default_branch") val defaultBranch: String,
)

/** One commit. Only enough to count the array; the contents are never shown. */
@Serializable
private data class CommitRef(val sha: String)

/**
 * Asks github.com how many commits landed, and when.
 *
 * @param httpClient the outbound JSON client, shared with [ResendEmailTransport] and the
 *   OAuth providers. Held for the process's life; there is nothing to close
 *   per-call.
 */
class GitHubClient(private val httpClient: HttpClient = createProviderHttpClient()) : CommitCounter {
    /**
     * Count commits on the default branch in each window.
     *
     * Never throws. Every failure — no token, a refused call, a repository that
     * does not exist — comes back as [CommitCounts.Unavailable] with a sentence
     * for the user, because this is one panel on a dialog whose other numbers
     * answered fine. Failing the whole response over a commit count would take
     * away the statistics that did work.
     *
     * The rule about the sentence is [EmailSendFailure]'s: GitHub's own error body
     * is logged in full and never shown. A provider's raw error on screen is how
     * internals reach screenshots, and here it could carry the repository's
     * existence — see the note on 404 below.
     */
    override suspend fun commitCounts(
        repository: RepositoryRef,
        token: String,
        weekStart: Long,
        monthStart: Long,
    ): CommitCounts {
        val branch = try {
            defaultBranch(repository, token)
        } catch (failure: GitHubFailure) {
            return CommitCounts.Unavailable(failure.userMessage)
        }
        return try {
            CommitCounts.Counted(
                week = countCommits(repository, token, branch, since = weekStart),
                month = countCommits(repository, token, branch, since = monthStart),
                allTime = countCommits(repository, token, branch, since = null),
            )
        } catch (failure: GitHubFailure) {
            CommitCounts.Unavailable(failure.userMessage)
        }
    }

    /**
     * The branch GitHub considers this repository's default.
     *
     * Asked rather than assumed to be `main`, because a repository whose default
     * is `master` would otherwise report zero commits in every window — a wrong
     * answer that looks exactly like a correct one, which is the worst kind.
     */
    private suspend fun defaultBranch(repository: RepositoryRef, token: String): String {
        val response = call("https://api.github.com/repos/$repository", token)
        return response.body<RepositoryMetadata>().defaultBranch
    }

    /**
     * How many commits are on [branch], optionally only since a moment.
     *
     * ── Why `per_page=1` and the Link header ───────────────────────────────
     *
     * GitHub has no "count commits" endpoint. Walking the pages would be one
     * request per hundred commits — for an all-time count on any real repository,
     * dozens of round trips, on a request a user is waiting on.
     *
     * So: ask for pages of one, and read the page number of the `rel="last"` link.
     * With one commit per page, the number of the last page IS the number of
     * commits, and it arrives in a header on the first response. One request per
     * window, whatever the repository's size.
     *
     * When there is no `rel="last"` link there is no second page, which means the
     * answer is however many commits came back in the body — 0 or 1.
     */
    private suspend fun countCommits(
        repository: RepositoryRef,
        token: String,
        branch: String,
        since: Long?,
    ): Long {
        val sinceParam = since?.let { "&since=${java.time.Instant.ofEpochMilli(it)}" }.orEmpty()
        val url = "https://api.github.com/repos/$repository/commits" +
            "?sha=$branch&per_page=1$sinceParam"
        val response = call(url, token)
        lastPageNumber(response.headers[HttpHeaders.Link])?.let { return it }
        return response.body<List<CommitRef>>().size.toLong()
    }

    /**
     * Make one authenticated call, translating every unhappy answer into a
     * [GitHubFailure] carrying a sentence for the user.
     *
     * The status codes are named individually because they mean genuinely
     * different things to whoever has to fix them, and "GitHub returned an error"
     * tells that person nothing about which of the four they are looking at.
     */
    private suspend fun call(url: String, token: String): HttpResponse {
        val response = try {
            httpClient.get(url) {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, "application/vnd.github+json")
                // Pins the response shape against a future GitHub default. Their
                // own recommendation, and free.
                header("X-GitHub-Api-Version", "2022-11-28")
            }
        } catch (t: Throwable) {
            logger.warn("GitHub request failed: ${t.message}")
            throw GitHubFailure("Could not reach GitHub.")
        }
        if (response.status.isSuccess()) return response

        // An empty repository. GitHub answers 409 to the commits endpoint when
        // there is no initial commit, which is not an error to report — the true
        // answer is simply zero, and this is the one non-2xx that is not a
        // failure. Handled by the caller through an empty body.
        if (response.status == HttpStatusCode.Conflict) return response

        logger.warn("GitHub refused ${response.status}: ${response.bodyAsText()}")
        throw GitHubFailure(
            when {
                response.status == HttpStatusCode.Unauthorized ->
                    "GitHub rejected the token. It may have expired, or been revoked."
                // 404 rather than 403 for a repository the token cannot see: GitHub
                // deliberately does not distinguish "does not exist" from "you may
                // not know whether it exists", and neither should this sentence.
                response.status == HttpStatusCode.NotFound ->
                    "GitHub cannot see that repository. Check the URL, and that the token grants Contents access to it."
                response.status == HttpStatusCode.Forbidden &&
                    response.headers["x-ratelimit-remaining"] == "0" ->
                    "GitHub's rate limit is exhausted. Statistics will refresh once it resets."
                response.status == HttpStatusCode.Forbidden ->
                    "GitHub refused the request. The token may lack Contents access to that repository."
                else -> "GitHub could not answer just now."
            },
        )
    }

}

/**
 * The page number of the `rel="last"` link, or null when there is not one.
 *
 * The header is a comma-separated list of `<url>; rel="name"` and the one value
 * needed is a query parameter inside one of those urls. Parsed with a regex
 * rather than a link-header library, because this reads exactly one field of one
 * relation and a dependency for that would be the larger commitment.
 *
 * ── Two traps, both in the pattern ─────────────────────────────────────────
 *
 * The leading `[?&]` is not decoration. The very query string this reads contains
 * `per_page=1`, so a pattern matching a bare `page=` finds the **1** in
 * `per_page=1` first and reports every repository as having one commit — a wrong
 * answer indistinguishable from a correct one on a quiet repository.
 *
 * The `[^>]*` is the second. GitHub sends `rel="next"` before `rel="last"`, and a
 * pattern allowed to cross a `>` would happily match the next-link's page number
 * against the last-link's relation. Forbidding `>` is what keeps each candidate
 * inside its own `<...>`.
 *
 * A malformed header returns null, which degrades to counting the body — an
 * undercount rather than a crash, on a number nobody authorises against.
 *
 * Internal rather than private so [GitHubStatisticsParsingTest] can pin both
 * traps against a real header captured from api.github.com.
 */
internal fun lastPageNumber(linkHeader: String?): Long? =
    linkHeader?.let { LAST_PAGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }

private val LAST_PAGE = Regex("""[?&]page=(\d+)[^>]*>;\s*rel="last"""")

/** A GitHub call that did not answer, with a message safe to show a user. */
private class GitHubFailure(val userMessage: String) : Exception(userMessage)
