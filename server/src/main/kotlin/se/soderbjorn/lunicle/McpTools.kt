/**
 * The tools an agent can call, and nothing else.
 *
 * ── Every tool is a second front door onto code already reasoned about ──────
 *
 * Each one maps onto a route in [boardRoutes] and asks the same [AccessControl]
 * question that route asks. No tool reaches a store without one, and no tool
 * exists that has no route — so the MCP surface adds *no capability*. It adds a
 * caller.
 *
 * That is what makes the whole design defensible in one sentence: an access token
 * resolves to a [UserRecord], which is the same type a session cookie resolves
 * to, so every rule written for the web app applies to the agent unchanged and
 * without being restated here.
 *
 * ── What is deliberately absent ─────────────────────────────────────────────
 *
 * There is no `delete_project`, no `create_project`, no role granting, no
 * impersonation, and no delete of anything. Not gated — **absent**. An earlier
 * draft proposed an admin scope; a capability that has no tool cannot be abused
 * by a confused agent, and that is the cheapest possible form of the guarantee.
 * Deletion in particular is the one thing an agent cannot be talked out of having
 * done. Revisit only when someone actually asks.
 *
 * ── The one exception, and its shape ────────────────────────────────────────
 *
 * Someone asked. `create_issue` and `add_comment` take an optional `author`,
 * `author_external` and `created_at`, so that an admin importing another tracker's
 * history ends up with a board that says who wrote what and when — rather than one
 * where every issue was filed by the admin, today. §3 of the plan says admin
 * operations are not exposed over MCP and that there is one flat `mcp` scope; both
 * still hold, and this is deliberately not a counterexample to either:
 *
 *  - **No new scope**, and the one new tool is not an admin tool — see below.
 *    The token still says only who you are.
 *  - **The gate is [AccessControl.canAttributeWrites]**, asked of the *token's*
 *    user on every call, server-side. Never of anything in the arguments.
 *  - **Refused, never ignored.** A non-admin who sends any of them gets an
 *    error. Silently dropping them would be the worst outcome available: the agent
 *    would report that it had backfilled history under Ada's name having actually
 *    written it under its own, and the person reading that report has no way to
 *    know. A refusal is a fact; a quiet substitution is a lie.
 *  - **Creation only.** There is no way to rewrite an existing row's author or
 *    timestamps, for anyone. Backfill is a thing you do at import, not a thing the
 *    board's history is subject to afterwards.
 *
 * `author_external` is the same exception widened by exactly one case: an imported
 * author who has no account here at all, and cannot be given one — see Issues.sq's
 * `created_by_external`. It is a separate parameter rather than `author` falling
 * back to a bare name when nothing matches, because that fallback would eat the
 * refusal in [resolveAuthor] precisely when it matters: a typo in a real user's
 * name matches nothing, and would quietly become an invented author reading almost
 * right. See [resolveAttribution].
 *
 * ── The second exception: uploading ─────────────────────────────────────────
 *
 * `start_attachment_upload` is a new tool, which the paragraph above says there
 * would not be. It earns it by being the only way to finish the job the first
 * exception started — an imported issue whose screenshots are all dead GitHub
 * links is an import that did not happen — and it stays inside the rule that
 * matters: it grants an agent exactly what the person driving it already has
 * through the web app, gated by the same [AccessControl.canEditIssue] and
 * [AccessControl.canEditComment] the HTTP routes use.
 *
 * It is deliberately **not** admin-only. Attaching a file is an ordinary thing an
 * ordinary user does; attaching one *as somebody else* is the admin-only part, and
 * that is [resolveAttribution]'s job here exactly as it is on `create_issue`.
 *
 * The bytes do not come through this tool, and that is the whole design rather
 * than an optimisation. See [AttachmentTicketStore] for why base64-through-context
 * and fetch-a-URL-server-side were both rejected, and what a ticket can do instead.
 *
 * ── Names, not ids, wherever a human would use a name ───────────────────────
 *
 * Statuses, priorities, resolutions, labels and components are all addressed by
 * name. The board already tells the agent what they are called, and an agent
 * asked to "close LUN-12 as fixed" should not first have to fetch a table to
 * learn that "Fixed" is 3. Issues and projects keep ids as well, because those
 * are what the ids are *for* — they are stable and a name is not.
 *
 * @see McpServer
 * @see AccessControl
 * @see BoardRoutes
 */
package se.soderbjorn.lunicle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * One tool, as `tools/list` describes it.
 *
 * @property description what the agent reads to decide whether to call it. Per-tool
 *   mechanics go here; cross-cutting orientation goes in the server instructions —
 *   see [MCP_INSTRUCTIONS].
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/**
 * What a tool call produced.
 *
 * @property isError whether this is a refusal rather than an answer. Note this is
 *   a *tool* error, not a JSON-RPC one: the call was well-formed and the server
 *   understood it, and the answer is "no". A protocol error would mean the agent
 *   is broken; this means the user cannot do that. See [McpTools.call].
 */
data class McpToolResult(
    val content: List<JsonElement>,
    val isError: Boolean = false,
)

/** Wrap text as MCP tool content. */
private fun textContent(text: String): JsonElement = buildJsonObject {
    put("type", "text")
    put("text", text)
}

private fun ok(text: String) = McpToolResult(listOf(textContent(text)))

/** A structured answer. Rendered as pretty JSON inside a text block, which is what agents parse best. */
private fun ok(value: JsonElement) = McpToolResult(listOf(textContent(McpJson.encodeToString(JsonElement.serializer(), value))))

/**
 * A refusal the agent should show the user and not retry.
 *
 * Every permission failure comes through here, and the sentences are the same
 * ones the HTTP routes use. That is deliberate: "You cannot edit this issue" is
 * already written for a human, and inventing a second vocabulary for agents would
 * mean two sets of words that can drift apart while describing the same rule.
 */
private fun refuse(message: String) = McpToolResult(listOf(textContent(message)), isError = true)

/** Pretty-printing JSON for tool output. Agents read this; the indentation is for them and for logs. */
private val McpJson = kotlinx.serialization.json.Json { prettyPrint = true }

/**
 * The orientation an agent gets in its `initialize` response.
 *
 * **Not optional.** This lands in the agent's system prompt, and it is how a model
 * knows what a Lunicle project *is* — that boards have ordered statuses, that
 * some statuses demand a resolution, that issues carry labels and components, and
 * what good use looks like. Without it an agent has a list of function signatures
 * and no idea what it is looking at, and its first move is to guess.
 *
 * Cross-cutting orientation belongs here; per-tool mechanics belong in the tool
 * descriptions. The split matters because this text is paid for on every single
 * conversation, whether or not a tool is ever called.
 */
internal val MCP_INSTRUCTIONS = """
    Lunicle is an issue tracker. Issues live on a per-project board, in columns.

    Getting oriented: call list_projects first, then get_board for the project you
    care about. get_board is the one call that tells you what everything in that
    project is called — its statuses, priorities, resolutions, labels and
    components are all per-project vocabulary, and you address them by name in
    every other tool. Do not assume a name from one project exists in another.

    How a board is shaped:
      • Statuses are the columns, in order (e.g. New → In progress → Closed).
      • Some statuses REQUIRE a resolution — get_board marks these with
        "requiresResolution": true. Moving an issue into one without saying why it
        is being closed will be refused; pass `resolution` (e.g. "Fixed",
        "Won't fix"). Moving an issue OUT of such a column clears the resolution
        automatically — you do not need to, and cannot, clear it yourself.
      • Priority is a separate axis from status, and every issue has one.
      • Labels and components are optional sets. update_issue replaces them
        wholesale rather than adding to them: send the full set you want, or omit
        the field entirely to leave it alone.

    Permissions, and why a refusal is final: you are acting as a specific Lunicle
    user, and you have exactly their rights — no more. A tool that returns an
    error saying you cannot do something is not a transient failure and retrying
    it will not help; the user you are acting as genuinely lacks that permission.
    Tell the person what happened rather than working around it. Reading is
    filtered the same way: a project you cannot see does not appear in
    list_projects, and there is no way to ask about it.

    What is deliberately not here: you cannot create, rename or delete projects,
    grant roles, or delete issues, comments or attachments. Those tools do not
    exist. If a task needs one, say so — do not approximate it by editing an issue
    into a tombstone.

    Attaching a file takes two steps and the second one is yours: start_attachment_upload
    hands back a URL, and the bytes only arrive when you push them at it with a
    shell command. They never travel through this conversation — do not read a
    file in order to upload it, and do not base64 anything. Uploading also does
    not put the file into any text: the tool tells you the url to use, and writing
    the markdown that points at it is a separate edit you make yourself.

    Backfilling history, if and only if you are acting as an admin: create_issue
    and add_comment take an optional `author`, an optional `author_external` and an
    optional `created_at`, for importing issues from somewhere else so that they
    keep the name and date they had. All three are admin-only and are refused
    outright — not ignored — for anyone else, so do not send them speculatively: a
    refusal costs a round-trip, and the alternative you might imagine (it silently
    files under your own name) is exactly what the refusal exists to prevent.

    Which author parameter to use is a question about the person, not a fallback
    chain. `author` is for somebody who has a Lunicle account: name them by their
    display name as it appears on the board, or by the email address on their
    account if two people share a name. It is refused if no account matches, and
    that refusal is load-bearing — it is how a misspelled name gets caught instead
    of quietly becoming a stranger. `author_external` is for somebody who has no
    account at all, which is the ordinary case when importing from another tracker:
    it records the name as written, creates nothing, and grants nobody anything.
    Passing both is refused. `created_at` is epoch milliseconds and cannot be in
    the future. None of them can be changed afterwards by any tool, so get them
    right on the way in.

    An issue or comment filed under `author_external` is unowned: nobody can edit
    it afterwards except an admin. That is a consequence of there being no account
    to own it, not an oversight, and it applies to imported attachments too.

    Writing issues well: the title is one line and is what people see on the card,
    so make it a statement of the problem rather than a category. The description
    is markdown and is the place for detail — steps, context, what was expected.
    Prefer filing one issue per problem over one issue listing several.
""".trimIndent()

/** Read a required string argument. */
private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * Whether the agent said anything about [name] at all.
 *
 * Presence, deliberately, rather than "did it parse" — and the distinction is the
 * whole of the permission check in [McpTools.resolveAttribution]. A non-admin
 * sending `"created_at": "last Tuesday"` must be refused for having asked, not
 * quietly given today's date because the value happened to be unreadable. Asking
 * `long()` instead would collapse "absent" and "garbage" into one null and hand
 * that caller the silent substitution this feature exists to prevent.
 *
 * `JsonNull` counts as absent: models routinely fill every property in a schema
 * and write `null` for the ones they have nothing to say about, and refusing an
 * ordinary user for that would make `add_comment` fail for half the clients that
 * call it.
 */
private fun JsonObject.isPresent(name: String): Boolean {
    val value = this[name] ?: return false
    return value !is JsonNull
}

/**
 * Read a numeric id argument.
 *
 * Tolerates a string, because models routinely send `"12"` for a number and
 * failing on that would be a refusal about JSON rather than about the request.
 * The `toDoubleOrNull`/`toLong` path is what makes `12.0` — the other thing they
 * send — work too.
 */
private fun JsonObject.long(name: String): Long? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    primitive.doubleOrNull?.let { return it.toLong() }
    return primitive.contentOrNull?.trim()?.toDoubleOrNull()?.toLong()
}

/** Read a string-array argument, or null if it is absent. Absent and empty are different. */
private fun JsonObject.strings(name: String): List<String>? =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** A JSON schema for one tool's arguments. */
private fun schema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (name, spec) -> put(name, spec) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }

private fun stringProp(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun integerProp(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun stringArrayProp(description: String): JsonObject = buildJsonObject {
    put("type", "array")
    putJsonObject("items") { put("type", "string") }
    put("description", description)
}

/**
 * The tools, and the code behind them.
 *
 * @param deps the same bundle [boardRoutes] uses. Shared rather than a parallel
 *   set, because "the MCP surface is a second front door onto code that has
 *   already been reasoned about" is only true if it is literally the same code.
 */
class McpTools(private val deps: BoardDependencies) {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "list_projects",
            description = "List the Lunicle projects you can see. Start here. Returns each " +
                "project's id, name and key prefix (the FOO in FOO-123).",
            inputSchema = schema(),
        ),
        McpTool(
            name = "get_board",
            description = "Get a project's whole board: its statuses (columns, in order), " +
                "priorities, resolutions, labels, components, and every issue on it. This is " +
                "the call that tells you what this project's vocabulary is called — you " +
                "address all of it by name in the other tools.",
            inputSchema = schema(
                "project_id" to integerProp("The project's id, from list_projects."),
                "project_name" to stringProp("The project's name, if you do not have its id."),
            ),
        ),
        McpTool(
            name = "get_issue",
            description = "Get one issue in full: its description and all its comments. " +
                "get_board returns titles only, so use this when the detail matters.",
            inputSchema = schema(
                "issue_id" to integerProp("The issue's id, from get_board."),
                required = listOf("issue_id"),
            ),
        ),
        McpTool(
            name = "create_issue",
            description = "File a new issue. It is created and published in one call — there " +
                "is no draft to clean up afterwards. Lands in the board's first column at " +
                "normal priority unless you say otherwise.",
            inputSchema = schema(
                "project_id" to integerProp("Which project to file it in."),
                "title" to stringProp("One line, stating the problem. Shown on the card."),
                "description" to stringProp("Markdown. The detail: steps, context, expectations."),
                "status" to stringProp("A status name from get_board. Defaults to the first column."),
                "priority" to stringProp("A priority name from get_board. Defaults to the project's default."),
                "resolution" to stringProp("Required only if `status` is one with requiresResolution."),
                "labels" to stringArrayProp("Label names from get_board."),
                "components" to stringArrayProp("Component names from get_board."),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(
                    "ADMIN ONLY, for backfilling. When this issue was written, in epoch " +
                        "milliseconds. Cannot be in the future. Also becomes the issue's " +
                        "last-touched time, which is what the board sorts on — the two are one " +
                        "value and cannot be set apart. Refused, not ignored, if you are not an " +
                        "admin. Defaults to now.",
                ),
                required = listOf("project_id", "title"),
            ),
        ),
        McpTool(
            name = "update_issue",
            description = "Change an existing issue. Every field is optional; omit one to " +
                "leave it as it is. Note that `labels` and `components` REPLACE the current " +
                "sets rather than adding to them.",
            inputSchema = schema(
                "issue_id" to integerProp("The issue to change."),
                "title" to stringProp("A new title."),
                "description" to stringProp("A new description, in markdown. Replaces the old one entirely."),
                "status" to stringProp("A status name from get_board."),
                "priority" to stringProp("A priority name from get_board."),
                "resolution" to stringProp("Required if the resulting status has requiresResolution."),
                "labels" to stringArrayProp("The full set of label names this issue should have."),
                "components" to stringArrayProp("The full set of component names this issue should have."),
                required = listOf("issue_id"),
            ),
        ),
        McpTool(
            name = "move_issue",
            description = "Move an issue to another column. The same thing as dragging its " +
                "card. Pass `resolution` when the target column requires one.",
            inputSchema = schema(
                "issue_id" to integerProp("The issue to move."),
                "status" to stringProp("The status name to move it to."),
                "resolution" to stringProp("Why it is being closed. Required if the target status has requiresResolution."),
                required = listOf("issue_id", "status"),
            ),
        ),
        McpTool(
            name = "add_comment",
            description = "Post a comment on an issue. Written and published in one call.",
            inputSchema = schema(
                "issue_id" to integerProp("The issue to comment on."),
                "body" to stringProp("The comment, in markdown."),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(
                    "ADMIN ONLY, for backfilling. When this comment was written, in epoch " +
                        "milliseconds. Cannot be in the future. Refused, not ignored, if you are " +
                        "not an admin. Defaults to now.",
                ),
                required = listOf("issue_id", "body"),
            ),
        ),
        McpTool(
            name = "start_attachment_upload",
            description = "Attach a file to an issue or a comment. Two steps, because the bytes " +
                "do not travel through this conversation: this call returns an `upload_url`, and " +
                "you then PUSH THE FILE AT IT YOURSELF with a shell command. Nothing is attached " +
                "until you do.\n\n" +
                "  curl -sS --fail --data-binary @FILE -H 'Content-Type: image/png' 'UPLOAD_URL'\n\n" +
                "The response gives you `attachment_id` and `url`. Put the file into the issue or " +
                "comment body yourself, as markdown, using that `url` — uploading does not change " +
                "any text. Use ![name](url) if the response says `renders_inline` is true, and " +
                "[name (size)](url) otherwise; getting that backwards leaves a broken image in the " +
                "issue.\n\n" +
                "For a file on the web, download it first — `curl -sL SRC -o /tmp/f.png` — then " +
                "upload that. Do NOT pipe one curl into another: the upload needs a Content-Length " +
                "and a pipe has none, so it is rejected with 411. When you are importing a body " +
                "that already contains image markdown, you are REPLACING the old URL with the new " +
                "one, not writing new markdown.\n\n" +
                "The ticket is single-use and expires in a few minutes. If it lapses, call this " +
                "again — they are free.",
            inputSchema = schema(
                "issue_id" to integerProp("Attach to this issue's description. Give this or comment_id, not both."),
                "comment_id" to integerProp("Attach to this comment. Give this or issue_id, not both."),
                "filename" to stringProp(
                    "The name to store it under, as it should appear to someone downloading it. " +
                        "Fixed now: the upload cannot rename it.",
                ),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(
                    "ADMIN ONLY, for backfilling. When this file was uploaded, in epoch " +
                        "milliseconds. Cannot be in the future. Refused, not ignored, if you are " +
                        "not an admin. Defaults to whenever the bytes land.",
                ),
                required = listOf("filename"),
            ),
        ),
    )

    /**
     * Run one tool as [user].
     *
     * [user] came from an access token and is a [UserRecord] exactly like the one
     * a session cookie produces — which is why every branch below can hand it
     * straight to [AccessControl] without a word about tokens.
     */
    /**
     * @param origin this server's own base URL, as the caller reached it. Passed
     *   in rather than configured or rebuilt: [start_attachment_upload] hands
     *   back a URL somebody has to be able to curl, and the one function that
     *   knows what this server is called is `ApplicationCall.serverOrigin` — see
     *   its docs for why there must be exactly one of it.
     */
    suspend fun call(
        user: UserRecord,
        name: String,
        arguments: JsonObject,
        origin: String,
    ): McpToolResult = when (name) {
        "list_projects" -> listProjects(user)
        "get_board" -> getBoard(user, arguments)
        "get_issue" -> getIssue(user, arguments)
        "create_issue" -> createIssue(user, arguments)
        "update_issue" -> updateIssue(user, arguments)
        "move_issue" -> moveIssue(user, arguments)
        "add_comment" -> addComment(user, arguments)
        "start_attachment_upload" -> startAttachmentUpload(user, arguments, origin)
        else -> refuse("No such tool: $name")
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    private suspend fun listProjects(user: UserRecord): McpToolResult {
        // Filtered, exactly as GET /api/projects is. A project this user cannot
        // read does not appear — it is not returned and hidden, because there is
        // no UI here to do the hiding and there never should have been.
        val visible = deps.projects.selectAll().filter { deps.access.canReadProject(user, it) }
        return ok(
            buildJsonArray {
                visible.forEach { project ->
                    add(
                        buildJsonObject {
                            put("id", project.id)
                            put("name", project.name)
                            put("keyPrefix", project.namePrefix)
                            put("isPublic", project.isPublic)
                        },
                    )
                }
            },
        )
    }

    private suspend fun getBoard(user: UserRecord, arguments: JsonObject): McpToolResult {
        val project = resolveProject(user, arguments) ?: return noSuchProject()
        val statuses = deps.statuses.forProject(project.id)
        val priorities = deps.priorities.forProject(project.id)
        val resolutions = deps.resolutions.forProject(project.id)
        val labels = deps.labels.forProject(project.id)
        val components = deps.components.forProject(project.id)
        val issues = deps.issues.forProject(project.id)
        val labelsByIssue = deps.issues.labelsForProject(project.id)
        val componentsByIssue = deps.issues.componentsForProject(project.id)

        val statusNames = statuses.associate { it.id to it.name }
        val priorityNames = priorities.associate { it.id to it.name }
        val resolutionNames = resolutions.associate { it.id to it.name }
        val labelNames = labels.associate { it.id to it.name }
        val componentNames = components.associate { it.id to it.name }
        // One lookup per distinct author for the whole board, not one per card.
        // See BoardDependencies.authorNames, whose reasoning this mirrors.
        val authors = issues.map { it.author }.mapNotNull { it.accountId }.distinct()
            .mapNotNull { id -> deps.users.findById(id)?.let { id to it.resolvedName } }
            .toMap()
        // Resolved before the JSON is built, because buildJsonObject's lambda is
        // not a coroutine body and canEditIssue may hit the roles table. Hoisting
        // it also makes it one pass rather than a query interleaved with string
        // building.
        val canEdit = issues.associate { it.id to deps.access.canEditIssue(user, it) }

        return ok(
            buildJsonObject {
                putJsonObject("project") {
                    put("id", project.id)
                    put("name", project.name)
                    put("keyPrefix", project.namePrefix)
                }
                putJsonArray("statuses") {
                    statuses.forEach { status ->
                        add(
                            buildJsonObject {
                                put("name", status.name)
                                // The one field an agent must not have to infer.
                                // Without it, every attempt to close an issue is a
                                // guess about whether a resolution is needed.
                                put("requiresResolution", status.requiresResolution)
                            },
                        )
                    }
                }
                putJsonArray("priorities") { priorities.forEach { add(it.name) } }
                putJsonArray("resolutions") { resolutions.forEach { add(it.name) } }
                putJsonArray("labels") { labels.forEach { add(it.name) } }
                putJsonArray("components") { components.forEach { add(it.name) } }
                putJsonArray("issues") {
                    issues.forEach { issue ->
                        add(
                            buildJsonObject {
                                put("id", issue.id)
                                put("key", "${project.namePrefix}-${issue.number}")
                                put("title", issue.title)
                                put("status", statusNames[issue.statusId])
                                put("priority", priorityNames[issue.priorityId])
                                issue.resolutionId?.let { put("resolution", resolutionNames[it]) }
                                putJsonArray("labels") {
                                    labelsByIssue[issue.id].orEmpty().forEach { id -> labelNames[id]?.let { add(it) } }
                                }
                                putJsonArray("components") {
                                    componentsByIssue[issue.id].orEmpty().forEach { id -> componentNames[id]?.let { add(it) } }
                                }
                                put("author", issue.author.displayName(authors))
                                put("updatedAt", issue.updatedAt)
                                // Sent for the same reason the web board sends it:
                                // editing is per issue — authorship is one of the
                                // three ways to yes — so a project-wide flag would
                                // either invite refused writes or hide allowed ones.
                                put("canEdit", canEdit[issue.id])
                            },
                        )
                    }
                }
            },
        )
    }

    private suspend fun getIssue(user: UserRecord, arguments: JsonObject): McpToolResult {
        val issue = readableIssue(user, arguments) ?: return noSuchIssue()
        val project = deps.projects.findById(issue.projectId) ?: return noSuchIssue()
        val statuses = deps.statuses.forProject(issue.projectId).associate { it.id to it.name }
        val priorities = deps.priorities.forProject(issue.projectId).associate { it.id to it.name }
        val resolutions = deps.resolutions.forProject(issue.projectId).associate { it.id to it.name }
        val labelNames = deps.labels.forProject(issue.projectId).associate { it.id to it.name }
        val componentNames = deps.components.forProject(issue.projectId).associate { it.id to it.name }
        val comments = deps.comments.forIssue(issue.id)
        val authors = (comments.map { it.author } + issue.author).mapNotNull { it.accountId }.distinct()
            .mapNotNull { id -> deps.users.findById(id)?.let { id to it.resolvedName } }
            .toMap()
        // Read before the JSON is built — buildJsonObject's lambda is not a
        // coroutine body, so a suspend query cannot happen inside it.
        val issueLabels = deps.issues.labelsFor(issue.id)
        val issueComponents = deps.issues.componentsFor(issue.id)
        val canEdit = deps.access.canEditIssue(user, issue)
        val canComment = deps.access.canComment(user, issue.projectId)

        return ok(
            buildJsonObject {
                put("id", issue.id)
                put("key", "${project.namePrefix}-${issue.number}")
                put("projectId", issue.projectId)
                put("title", issue.title)
                put("description", issue.description)
                put("status", statuses[issue.statusId])
                put("priority", priorities[issue.priorityId])
                issue.resolutionId?.let { put("resolution", resolutions[it]) }
                putJsonArray("labels") {
                    issueLabels.forEach { id -> labelNames[id]?.let { add(it) } }
                }
                putJsonArray("components") {
                    issueComponents.forEach { id -> componentNames[id]?.let { add(it) } }
                }
                put("author", issue.author.displayName(authors))
                put("createdAt", issue.createdAt)
                put("updatedAt", issue.updatedAt)
                put("canEdit", canEdit)
                put("canComment", canComment)
                putJsonArray("comments") {
                    comments.forEach { comment ->
                        add(
                            buildJsonObject {
                                put("id", comment.id)
                                put("body", comment.body)
                                put("author", comment.author.displayName(authors))
                                put("createdAt", comment.createdAt)
                            },
                        )
                    }
                }
            },
        )
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    /**
     * File an issue.
     *
     * ── One call, both steps ────────────────────────────────────────────────
     *
     * The HTTP API creates a hidden draft and then publishes it, because the web
     * editor needs a row to hang an inline image upload off before the user has
     * typed anything. An agent has no such problem and must not be made to learn
     * about it: a two-call dance would mean an agent that stopped thinking between
     * them leaves a half-created draft on somebody's board forever.
     *
     * So: both steps, or neither. The draft is deleted if publishing fails, which
     * is what makes "or neither" true rather than aspirational.
     */
    private suspend fun createIssue(user: UserRecord, arguments: JsonObject): McpToolResult {
        val project = resolveProject(user, arguments) ?: return noSuchProject()
        if (!deps.access.canCreateIssue(user, project.id)) {
            return refuse("You cannot create issues in this project.")
        }

        // Before anything is written, and before any vocabulary is resolved: a
        // refusal here must cost nothing, and a caller who is not allowed to
        // attribute must not learn whether "Ada" is an account by watching which
        // refusal comes back first.
        val attribution = resolveAttribution(user, arguments)
            .getOrElse { return refuse(it.message ?: "That attribution cannot be used.") }

        val title = arguments.string("title") ?: return refuse("An issue needs a title.")
        if (title.length > MAX_MCP_TITLE_LENGTH) return refuse("That title is too long.")

        val statuses = deps.statuses.forProject(project.id)
        val status = arguments.string("status")
            ?.let { name -> statuses.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return refuseUnknown("status", name, statuses.map { it.name }) }
            ?: statuses.firstOrNull()
            ?: return refuse("That project has no columns, so it cannot take an issue.")

        val priorities = deps.priorities.forProject(project.id)
        val priority = arguments.string("priority")
            ?.let { name -> priorities.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return refuseUnknown("priority", name, priorities.map { it.name }) }
            ?: deps.priorities.defaultForProject(project.id)
            ?: return refuse("That project has no priorities, so it cannot take an issue.")

        val labelIds = resolveVocabulary(
            arguments.strings("labels"),
            deps.labels.forProject(project.id).associate { it.name to it.id },
            "label",
        ).getOrElse { return refuse(it.message ?: "Unknown label.") }
        val componentIds = resolveVocabulary(
            arguments.strings("components"),
            deps.components.forProject(project.id).associate { it.name to it.id },
            "component",
        ).getOrElse { return refuse(it.message ?: "Unknown component.") }

        val resolutionId = resolveResolutionByName(project.id, status.id, arguments.string("resolution"))
            .getOrElse { return refuse(it.message ?: "Bad resolution.") }

        val (issueId, number) = deps.issueRepository.createDraft(
            project.id,
            attribution.author,
            attribution.at,
        )
        val issue = deps.issues.findById(issueId)
            ?: return refuse("The issue could not be created.")
        try {
            deps.issueRepository.save(
                issue = issue,
                title = title,
                description = arguments.string("description").orEmpty(),
                statusId = status.id,
                priorityId = priority.id,
                resolutionId = resolutionId,
                labelIds = labelIds,
                componentIds = componentIds,
                // The same value the draft was created with, never a second read of
                // the clock: publishing stamps updated_at unconditionally, so
                // omitting it here would drag a backfilled issue's "last touched" to
                // today and straddle the two columns Issues.sq requires to agree.
                updatedAt = attribution.at,
            )
        } catch (failure: Exception) {
            // The half of "one call, both steps, or neither" that makes it true.
            // Without this, a failure here leaves an invisible draft row that
            // nobody can see and nobody will ever clean up.
            deps.issueRepository.delete(issue)
            throw failure
        }
        return ok("Created ${project.namePrefix}-$number (issue id $issueId): $title")
    }

    private suspend fun updateIssue(user: UserRecord, arguments: JsonObject): McpToolResult {
        val issue = readableIssue(user, arguments) ?: return noSuchIssue()
        if (!deps.access.canEditIssue(user, issue)) return refuse("You cannot edit this issue.")

        val title = arguments.string("title") ?: issue.title
        if (title.length > MAX_MCP_TITLE_LENGTH) return refuse("That title is too long.")

        val statuses = deps.statuses.forProject(issue.projectId)
        val statusId = arguments.string("status")
            ?.let { name -> statuses.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id ?: return refuseUnknown("status", name, statuses.map { it.name }) }
            ?: issue.statusId

        val priorities = deps.priorities.forProject(issue.projectId)
        val priorityId = arguments.string("priority")
            ?.let { name -> priorities.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id ?: return refuseUnknown("priority", name, priorities.map { it.name }) }
            ?: issue.priorityId

        // Absent means "leave alone"; an empty array means "remove them all". Two
        // different intents, and collapsing them would make it impossible for an
        // agent to clear a label set at all.
        val labelIds = arguments.strings("labels")
            ?.let {
                resolveVocabulary(it, deps.labels.forProject(issue.projectId).associate { l -> l.name to l.id }, "label")
                    .getOrElse { failure -> return refuse(failure.message ?: "Unknown label.") }
            }
            ?: deps.issues.labelsFor(issue.id)
        val componentIds = arguments.strings("components")
            ?.let {
                resolveVocabulary(it, deps.components.forProject(issue.projectId).associate { c -> c.name to c.id }, "component")
                    .getOrElse { failure -> return refuse(failure.message ?: "Unknown component.") }
            }
            ?: deps.issues.componentsFor(issue.id)

        // The resolution is re-derived from the RESULTING status, not carried
        // over. That is what stops an issue reopened by this tool from keeping
        // "Won't fix" — see resolveResolution, whose rule this is.
        val resolutionId = resolveResolutionByName(
            issue.projectId,
            statusId,
            arguments.string("resolution")
                ?: issue.resolutionId?.let { current ->
                    deps.resolutions.forProject(issue.projectId).firstOrNull { it.id == current }?.name
                },
        ).getOrElse { return refuse(it.message ?: "Bad resolution.") }

        deps.issueRepository.save(
            issue = issue,
            title = title,
            description = arguments.string("description") ?: issue.description,
            statusId = statusId,
            priorityId = priorityId,
            resolutionId = resolutionId,
            labelIds = labelIds,
            componentIds = componentIds,
        )
        return ok("Updated issue ${issue.id}.")
    }

    private suspend fun moveIssue(user: UserRecord, arguments: JsonObject): McpToolResult {
        val issue = readableIssue(user, arguments) ?: return noSuchIssue()
        // The same gate the drag route uses, and for the reason AccessControl
        // states: moving a card is a status_id write, not a lighter operation
        // that deserves a lighter check.
        if (!deps.access.canEditIssue(user, issue)) return refuse("You cannot move this issue.")

        val statuses = deps.statuses.forProject(issue.projectId)
        val name = arguments.string("status") ?: return refuse("Which column should it move to?")
        val status = statuses.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return refuseUnknown("status", name, statuses.map { it.name })

        val resolutionId = resolveResolutionByName(issue.projectId, status.id, arguments.string("resolution"))
            .getOrElse { return refuse(it.message ?: "Bad resolution.") }

        deps.issues.setStatus(issue.id, status.id, resolutionId)
        return ok("Moved issue ${issue.id} to ${status.name}.")
    }

    /** Post a comment. One call, both steps — see [createIssue]. */
    private suspend fun addComment(user: UserRecord, arguments: JsonObject): McpToolResult {
        val issue = readableIssue(user, arguments) ?: return noSuchIssue()
        if (!deps.access.canComment(user, issue.projectId)) {
            return refuse("You cannot comment on this project's issues.")
        }
        val attribution = resolveAttribution(user, arguments)
            .getOrElse { return refuse(it.message ?: "That attribution cannot be used.") }
        val body = arguments.string("body") ?: return refuse("A comment needs something in it.")

        val commentId = deps.issueRepository.createCommentDraft(
            issue.id,
            attribution.author,
            attribution.at,
        )
        try {
            deps.issueRepository.saveComment(commentId, body)
        } catch (failure: Exception) {
            deps.comments.findById(commentId)?.let { deps.issueRepository.deleteComment(it) }
            throw failure
        }
        return ok("Commented on issue ${issue.id}.")
    }

    // ── Attachments ──────────────────────────────────────────────────────────

    /**
     * Mint an upload ticket.
     *
     * **This function is the permission check for the entire upload path.** The
     * route that takes the bytes has none — it cannot, because the thing pushing
     * them is a curl command with no session — so everything it will not ask is
     * asked here, once, and stored in the ticket. See [AttachmentTicketStore].
     *
     * Read the order the way [resolveAttribution] asks to be read: may you write
     * here at all, and only then whose name goes on it. The two are separate
     * questions and the second is strictly narrower — anyone who can edit an
     * issue may attach to it, and only an admin may attach *as somebody else*.
     * That is the same split `create_issue` already has, and it is why this tool
     * is not admin-only despite carrying admin-only parameters.
     *
     * The permission is the one the equivalent HTTP route uses, not a new one:
     * [AccessControl.canEditIssue] for an issue, [AccessControl.canEditComment]
     * for a comment. An agent gets what the person driving it would get through
     * the web app, which is the whole rule for this surface.
     */
    private suspend fun startAttachmentUpload(
        user: UserRecord,
        arguments: JsonObject,
        origin: String,
    ): McpToolResult {
        val wantsIssue = arguments.isPresent("issue_id")
        val wantsComment = arguments.isPresent("comment_id")
        if (wantsIssue == wantsComment) {
            // Both or neither. Said as one sentence because the fix is the same
            // either way, and because the CHECK in Attachments.sq means a row
            // with two owners is not a thing that could be written even if this
            // let it through.
            return refuse(
                "An attachment belongs to exactly one thing. Give `issue_id` to put it in an " +
                    "issue's description, or `comment_id` to put it in a comment — not both, and " +
                    "not neither.",
            )
        }

        val filename = arguments.string("filename")
            ?: return refuse("What should the file be called? `filename` is required.")

        val target = if (wantsIssue) {
            val issue = readableIssue(user, arguments) ?: return noSuchIssue()
            if (!deps.access.canEditIssue(user, issue)) {
                return refuse("You cannot attach files to this issue.")
            }
            AttachmentTarget.Issue(issue.id)
        } else {
            val commentId = arguments.long("comment_id")
                ?: return refuse("`comment_id` must be a number.")
            val comment = deps.comments.findById(commentId)
                ?: return refuse("There is no comment $commentId.")
            // Readable-then-editable, in that order, so a comment in a project
            // this user cannot see is "no such comment" rather than "you may
            // not" — the second sentence confirms it exists.
            val issue = deps.issues.findById(comment.issueId)
            val project = issue?.let { deps.projects.findById(it.projectId) }
            if (project == null || !deps.access.canReadProject(user, project)) {
                return refuse("There is no comment $commentId.")
            }
            if (!deps.access.canEditComment(user, comment)) {
                return refuse("You cannot attach files to that comment. It is not yours.")
            }
            AttachmentTarget.Comment(comment.id)
        }

        val attribution = resolveAttribution(user, arguments)
            .getOrElse { return refuse(it.message ?: "That attribution cannot be used.") }

        val token = deps.attachmentTickets.mint(
            target = target,
            filename = filename,
            author = attribution.author,
            createdAt = attribution.at,
        )

        return ok(
            buildJsonObject {
                put("upload_url", "$origin/api/attachments/upload/$token")
                put("filename", filename)
                put(
                    "next",
                    "Nothing is attached yet. Push the bytes at upload_url with a shell command, " +
                        "for example: curl -sS --fail --data-binary @FILE -H 'Content-Type: " +
                        "image/png' '$origin/api/attachments/upload/$token'. It answers with " +
                        "attachment_id, url and renders_inline — then put the file into the body " +
                        "text yourself as markdown using that url. Single use, expires in a few " +
                        "minutes.",
                )
            }.toString(),
        )
    }

    // ── Attribution ──────────────────────────────────────────────────────────

    /**
     * Who a new row belongs to, and when it claims to have been written.
     *
     * @property author who wrote it. An [Author.Account] holds a real `users.id`,
     *   resolved here rather than taken on trust — the column has a foreign key,
     *   and an id that is not one is a constraint violation surfacing as a 500
     *   rather than as a sentence. An [Author.External] holds a name with no
     *   account behind it and no foreign key to satisfy; see Issues.sq. Defaulted
     *   to the token's own user, which is the only value it ever has outside a
     *   backfill.
     * @property at what goes in `created_at` — and, for an issue, `updated_at` too;
     *   they are one value. Null means now, decided at the store where it always
     *   was, rather than here.
     */
    private class Attribution(val author: Author, val at: Long?)

    /**
     * Work out the author and timestamp for a row about to be created, or refuse.
     *
     * The security boundary of this whole feature, in one function, so there is one
     * of it rather than one per tool. The order below is the point:
     *
     *  1. **Did they ask?** By presence, not by parse — see [isPresent].
     *  2. **May they?** Asked of [user], which came from the token, via
     *     [AccessControl]. Nothing in [arguments] participates in this decision;
     *     an argument saying `"is_admin": true` would be a value an untrusted
     *     caller made up.
     *  3. **Only then**, what did they mean.
     *
     * A non-admin who asked is refused with the parameter named, rather than served
     * as if they had not asked. See this file's preamble for why that is the whole
     * feature: the alternative is an agent truthfully reporting a backfill that
     * silently went in under the wrong name.
     *
     * ── Why `author` and `author_external` are two parameters ─────────────────
     *
     * They are one question with two kinds of answer, and the caller has to say
     * which kind it is giving. The tempting alternative — one `author` that means
     * an account when it matches one and a bare name when it does not — fails in
     * the case nobody sees: a typo in a real user's name matches nothing, so it
     * would quietly file the row under an invented author that reads almost
     * right. That is exactly the refusal [resolveAuthor] exists to give, and it
     * would be gone precisely when it was needed. Two parameters keep
     * [resolveAuthor] strict and make "this person has no account" a thing said
     * out loud.
     *
     * Note what is deliberately NOT checked: whether an `author_external` name
     * happens to match an account. It may. The caller said `author_external`, and
     * second-guessing an explicit parameter is the same sin as guessing at an
     * ambiguous one — a GitHub handle that collides with a display name here is
     * still not that person.
     */
    private suspend fun resolveAttribution(user: UserRecord, arguments: JsonObject): Result<Attribution> {
        val wantsAuthor = arguments.isPresent(AUTHOR_ARGUMENT)
        val wantsExternalAuthor = arguments.isPresent(AUTHOR_EXTERNAL_ARGUMENT)
        val wantsTimestamp = arguments.isPresent(CREATED_AT_ARGUMENT)
        if (!wantsAuthor && !wantsExternalAuthor && !wantsTimestamp) {
            // The ordinary path, and the overwhelmingly common one: authored by the
            // token's user, stamped by the store. Byte-for-byte what happened before
            // any of these parameters existed.
            return Result.success(Attribution(author = Author.Account(user.id), at = null))
        }

        if (!deps.access.canAttributeWrites(user)) {
            val asked = listOfNotNull(
                AUTHOR_ARGUMENT.takeIf { wantsAuthor },
                AUTHOR_EXTERNAL_ARGUMENT.takeIf { wantsExternalAuthor },
                CREATED_AT_ARGUMENT.takeIf { wantsTimestamp },
            ).joinToString(" and ")
            return Result.failure(
                ResolutionRefusal(
                    "Only an admin can set $asked, and you are not acting as one. Nothing was " +
                        "written. Remove $asked and the row will be filed under your own name, " +
                        "stamped now — but that is a different thing from what you asked for, so " +
                        "decide rather than assume.",
                ),
            )
        }

        if (wantsAuthor && wantsExternalAuthor) {
            return Result.failure(
                ResolutionRefusal(
                    "`$AUTHOR_ARGUMENT` and `$AUTHOR_EXTERNAL_ARGUMENT` are two answers to one " +
                        "question, so this asks for a row with two authors and the database will " +
                        "not hold one. Nothing was written. Use `$AUTHOR_ARGUMENT` when the person " +
                        "has a Lunicle account and `$AUTHOR_EXTERNAL_ARGUMENT` when they do not.",
                ),
            )
        }

        val author = when {
            wantsAuthor -> {
                val named = arguments.string(AUTHOR_ARGUMENT)
                    ?: return Result.failure(ResolutionRefusal("`$AUTHOR_ARGUMENT` was given as an empty name."))
                Author.Account(resolveAuthor(named).getOrElse { return Result.failure(it) })
            }
            wantsExternalAuthor -> {
                val named = arguments.string(AUTHOR_EXTERNAL_ARGUMENT)
                    ?: return Result.failure(
                        ResolutionRefusal("`$AUTHOR_EXTERNAL_ARGUMENT` was given as an empty name."),
                    )
                Author.External(named.trim())
            }
            else -> Author.Account(user.id)
        }

        val at = if (wantsTimestamp) {
            resolveTimestamp(arguments).getOrElse { return Result.failure(it) }
        } else {
            null
        }

        return Result.success(Attribution(author = author, at = at))
    }

    /**
     * Turn a name or an email address into a `users.id`, or refuse.
     *
     * ── Why a name, and not the id the foreign key actually wants ─────────────
     *
     * Because an agent has no way to learn an id. Nothing on this surface exposes
     * the user table — `get_board` and `get_issue` report an author's
     * [UserRecord.resolvedName] and nothing more — and the alternative was an
     * admin-only `list_users` tool. Rejected: §3's instinct is that a capability
     * with no tool cannot be abused, and "every account on this instance, on
     * request" is exactly the kind of tool that gets called for no reason once it
     * exists. Matching the name already on the board costs nothing and adds no
     * enumeration primitive — the agent can only confirm names it was told.
     *
     * It also matches the file's existing rule: statuses, priorities, resolutions,
     * labels and components are all addressed by name for the same reason. An
     * author is one more piece of vocabulary a human would say out loud.
     *
     * ── Email first, then name ───────────────────────────────────────────────
     *
     * An email is unique in practice and a display name is not — `users` carries no
     * UNIQUE on either (see Users.sq: the same human via Google and GitHub is two
     * accounts by design, sharing an address), so both are checked for ambiguity
     * and neither is ever guessed at. Email is tried first because it is the escape
     * hatch offered when a name is ambiguous, and a display name that is literally
     * somebody else's email address is not a case worth ranking above that.
     *
     * The ambiguous refusal deliberately does NOT list the candidates' addresses.
     * It could — the caller is an admin — but an error message is a bad place to
     * decide to start disclosing emails, and the sentence is just as actionable
     * without: the human driving this import knows which Anna they mean.
     */
    private suspend fun resolveAuthor(named: String): Result<Long> {
        val wanted = named.trim()
        val everyone = deps.users.selectAll()
        val byEmail = everyone.filter { it.email?.equals(wanted, ignoreCase = true) == true }
        val matches = byEmail.ifEmpty {
            everyone.filter { it.resolvedName.equals(wanted, ignoreCase = true) }
        }

        return when (matches.size) {
            1 -> Result.success(matches.single().id)
            0 -> Result.failure(
                ResolutionRefusal(
                    "There is no Lunicle account for \"$wanted\". An author must be somebody who " +
                        "has signed in at least once — accounts are not created by naming them. " +
                        "Use the display name exactly as the board shows it, or the email address " +
                        "on the account.",
                ),
            )
            // Never the first match, never the lowest id. Two accounts named "Anna
            // Karlsson" are two people until someone says which, and picking one
            // would attribute somebody's imported history to a stranger silently and
            // permanently.
            else -> Result.failure(
                ResolutionRefusal(
                    "${matches.size} Lunicle accounts are called \"$wanted\", so this would be a " +
                        "guess about who wrote it. Name the author by the email address on their " +
                        "account instead. Nothing was written.",
                ),
            )
        }
    }

    /**
     * Read and sanity-check a backfilled timestamp.
     *
     * Epoch **milliseconds**, like every other time in this schema.
     *
     * ── The two bounds, and why the future one is the one that matters ────────
     *
     * A negative value is nonsense outright. A far-future one is worse than
     * nonsense: the board's secondary sort is `updated_at DESC`, so an issue
     * stamped in the year 3000 is one card pinned to the top of its column forever,
     * with nothing in the UI to explain why and no tool able to change it back.
     * Refusing costs a round-trip; storing it costs somebody an afternoon with a
     * SQLite shell.
     *
     * A day of slack rather than a hard `<= now`, because "now" is the agent's
     * clock and this server's, and they are not the same clock. An agent stamping a
     * comment with its own idea of the current time is doing something reasonable,
     * and refusing it over four seconds of skew would be a refusal about NTP.
     *
     * Deliberately no lower bound beyond zero, and no "that looks like seconds, not
     * milliseconds" heuristic. Importing a tracker whose history predates anything
     * we could pick as a floor is precisely the job, and a seconds-shaped value is
     * a legal 1970 date — the guess would be right often and wrong unrecoverably,
     * which is a bad trade against a parameter documented as milliseconds twice.
     */
    private fun resolveTimestamp(arguments: JsonObject): Result<Long> {
        val at = arguments.long(CREATED_AT_ARGUMENT)
            ?: return Result.failure(
                ResolutionRefusal(
                    "`$CREATED_AT_ARGUMENT` must be a number of milliseconds since the Unix " +
                        "epoch, which \"${arguments[CREATED_AT_ARGUMENT]}\" is not.",
                ),
            )
        if (at < 0) {
            return Result.failure(
                ResolutionRefusal("`$CREATED_AT_ARGUMENT` cannot be negative; $at is before 1970."),
            )
        }
        val ceiling = System.currentTimeMillis() + MAX_MCP_BACKFILL_SKEW_MILLIS
        if (at > ceiling) {
            return Result.failure(
                ResolutionRefusal(
                    "`$CREATED_AT_ARGUMENT` is in the future ($at). Backfilling is for history " +
                        "that already happened, and a future timestamp would pin the row to the " +
                        "top of its column permanently. Note this is milliseconds since the epoch, " +
                        "not seconds.",
                ),
            )
        }
        return Result.success(at)
    }

    // ── Shared resolution ────────────────────────────────────────────────────

    /**
     * Resolve a project the caller may read, by id or by name.
     *
     * Both answer identically when the project is unreadable — see [noSuchProject].
     */
    private suspend fun resolveProject(user: UserRecord, arguments: JsonObject): ProjectRecord? {
        val project = arguments.long("project_id")?.let { deps.projects.findById(it) }
            ?: arguments.string("project_name")?.let { deps.projects.findByName(it) }
            ?: return null
        return project.takeIf { deps.access.canReadProject(user, it) }
    }

    /**
     * Resolve an issue whose project the caller may read.
     *
     * Every issue tool starts here, exactly as every issue route starts at
     * `readableIssue`: an issue is only as readable as its project.
     */
    private suspend fun readableIssue(user: UserRecord, arguments: JsonObject): IssueRecord? {
        val issue = arguments.long("issue_id")?.let { deps.issues.findById(it) } ?: return null
        val project = deps.projects.findById(issue.projectId) ?: return null
        return issue.takeIf { deps.access.canReadProject(user, project) }
    }

    /**
     * "No such project", for both "there isn't one" and "you may not see it".
     *
     * The same deliberate conflation the HTTP routes make with their 404s: saying
     * "that project is private" would confirm it exists, which is the thing being
     * withheld. An agent is a caller like any other, and a caller that can probe
     * for private project names by watching which error it gets is exactly the
     * leak the routes already refuse.
     */
    private fun noSuchProject() = refuse("No such project.")

    private fun noSuchIssue() = refuse("No such issue.")

    /**
     * A name that is not in this project's vocabulary, refused with the list.
     *
     * Naming the alternatives is what turns this from a dead end into something an
     * agent can act on in one step. Without it, the only recovery is to call
     * get_board and try again — which is a round-trip to learn what we already
     * knew when we refused.
     */
    private fun refuseUnknown(kind: String, name: String, available: List<String>) =
        refuse("There is no $kind called \"$name\" in this project. Available: ${available.joinToString(", ")}.")

    /** Map vocabulary names onto ids, refusing the first one that is not in this project. */
    private fun resolveVocabulary(
        names: List<String>?,
        byName: Map<String, Long>,
        kind: String,
    ): Result<List<Long>> {
        if (names == null) return Result.success(emptyList())
        val ids = names.map { name ->
            // Case-insensitive, because an agent reading "Bug" from a board and
            // sending "bug" is being reasonable, and refusing it would be pedantry
            // that costs a round-trip.
            byName.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
                ?: return Result.failure(
                    ResolutionRefusal(
                        "There is no $kind called \"$name\" in this project. " +
                            "Available: ${byName.keys.joinToString(", ")}.",
                    ),
                )
        }
        return Result.success(ids)
    }

    /**
     * Turn a resolution *name* into the id to store, via the one rule.
     *
     * The name → id step is this function's own; everything after it is
     * [resolveResolution], which is shared with the editor's PUT and the board's
     * drag POST. That is the whole point — a fourth implementation of "a
     * non-closing status must not keep a resolution" is a fourth chance to get it
     * wrong.
     */
    private suspend fun resolveResolutionByName(
        projectId: Long,
        statusId: Long,
        name: String?,
    ): Result<Long?> {
        val resolutions = deps.resolutions.forProject(projectId)
        val resolutionId = name?.let { wanted ->
            resolutions.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.id
                ?: return Result.failure(
                    ResolutionRefusal(
                        "There is no resolution called \"$wanted\" in this project. " +
                            "Available: ${resolutions.joinToString(", ") { it.name }}.",
                    ),
                )
        }
        return deps.resolveResolution(projectId, statusId, resolutionId)
    }
}

/**
 * How long a title may be, over MCP.
 *
 * The same bound BoardRoutes applies, and it has to be: an agent's issue is
 * rendered on the same card a human's is. Duplicated as a constant rather than
 * reaching for the route's private one, which is the smaller wrong — the
 * alternative is widening a route's internals for a number.
 */
private const val MAX_MCP_TITLE_LENGTH = 300

/**
 * The backfill arguments, named once.
 *
 * Constants rather than string literals because each name appears in the schema
 * the agent reads, in the permission check, and inside the refusals that quote it
 * back — and a refusal that names a parameter the schema does not have is a
 * refusal an agent cannot act on.
 */
private const val AUTHOR_ARGUMENT = "author"
private const val AUTHOR_EXTERNAL_ARGUMENT = "author_external"
private const val CREATED_AT_ARGUMENT = "created_at"

/** Shared by every tool that takes it: one description of `author`, not several that drift. */
private const val AUTHOR_PROP_DESCRIPTION =
    "ADMIN ONLY, for backfilling imported history. Who this should belong to: their " +
        "display name exactly as get_board and get_issue report it, or the email address on " +
        "their account when two people share a name. They must already have a Lunicle " +
        "account — naming somebody does not create one — and an ambiguous name is refused " +
        "rather than guessed at. If they have no account, use author_external instead; do not " +
        "pass both. Refused, not ignored, if you are not an admin. Defaults to you."

/** As [AUTHOR_PROP_DESCRIPTION]: one description of `author_external`, shared. */
private const val AUTHOR_EXTERNAL_PROP_DESCRIPTION =
    "ADMIN ONLY, for backfilling imported history written by somebody with no Lunicle " +
        "account — a GitHub handle, say, from a tracker being migrated. Recorded as the name " +
        "itself and rendered as the author; it creates no account and grants nobody anything, " +
        "so the row is unowned and only an admin can edit it afterwards. Use `author` instead " +
        "when the person does have an account, and never pass both — they are two answers to " +
        "one question and the pair is refused. Not checked against existing accounts: if you " +
        "pass a name somebody here happens to share, you get an author who is not them. " +
        "Refused, not ignored, if you are not an admin."

/**
 * How far past this server's clock a backfilled timestamp may still land.
 *
 * A day. Not a tolerance for the future — backfilling is about the past — but for
 * the fact that "now" is measured on the agent's clock and checked on ours. See
 * [McpTools.resolveTimestamp].
 */
private const val MAX_MCP_BACKFILL_SKEW_MILLIS = 24L * 60 * 60 * 1000
