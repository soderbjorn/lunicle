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
 * Someone asked. `create_issue`, `add_comment` and `update_comment` take an optional
 * `author`, `author_external` and `created_at`, so that an admin importing another
 * tracker's history ends up with a board that says who wrote what and when — rather
 * than one where every issue was filed by the admin, today. §3 of the plan says
 * admin operations are not exposed over MCP and that there is one flat `mcp` scope;
 * both still hold, and this is deliberately not a counterexample to either:
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
 *  - **Creation only for everyone but an admin.** A non-admin can never rewrite an
 *    existing row's author or timestamps — those are set once, at creation, and no
 *    tool here lets an ordinary editor near them. An admin can: `update_issue` and
 *    `update_comment` let one re-attribute and re-date an existing issue or comment
 *    in place, an external author included. They earn the exception the way
 *    `start_attachment_upload` does — this surface offers no deletion, so an import
 *    that put the wrong name or date on a row could otherwise never be repaired at
 *    all. The gate is unchanged ([AccessControl.canAttributeWrites]); what an admin
 *    may fix is the existing row, not only the new one.
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
 * ── The third exception: `send_email`, which has no route at all ────────────
 *
 * The first sentence of this file says no tool exists that has no route, so the
 * MCP surface adds no capability. `send_email` breaks that, and pretending
 * otherwise would be worse than saying so: nothing in the web app sends free-form
 * mail, and a person signed into Lunicle cannot do what this tool does. It exists
 * because an agent finishing a long job has nowhere to report to except a
 * conversation the person has already left.
 *
 * What replaces the missing route as the argument for its safety is that the
 * capability is *bounded by construction* rather than by a check:
 *
 *  - **It has no recipient parameter.** The address is [UserRecord.email] on the
 *    token's own user. Not an argument that is checked against it — absent. So
 *    "it can only mail the person whose account it is" is not a rule this file
 *    enforces and could stop enforcing; it is the only address in scope. This is
 *    what keeps a tool that sends mail from being a relay reachable with a
 *    Lunicle login, and it is the property to defend if this is ever extended.
 *  - **It says what it is.** Fixed subject prefix, and a header naming the agent
 *    above anything the agent wrote — so a message cannot be composed to arrive
 *    looking like one a colleague typed. See AgentMail.
 *  - **It writes nothing.** No row, no history, nothing to undo. The worst
 *    outcome available is that somebody gets an e-mail they did not want, from
 *    an agent holding their own token, which they can stop by turning MCP off.
 *
 * This paragraph used to end "it asks no [AccessControl] question, and that is
 * not an omission: there is no project involved and no other party to be
 * protected from". It now asks [AccessControl.canSendAgentMail], and the reason
 * the old sentence was wrong is worth keeping rather than deleting: the other
 * party is the deployment. Every message goes out over this instance's sending
 * domain, and an instance where every account with MCP on is a live outbound
 * mail path has a deliverability problem nobody chose. The three bounds above
 * still stand and are still what keeps this from being a relay — the check is
 * about who may make the instance send at all, not about where a message goes.
 * See [AccessControl.canSendAgentMail] for why that answer is admin and what a
 * non-admin who wants it should get instead.
 *
 * It is gated in two places, and the second is the one that enforces: the tool
 * is left out of `tools/list` for a caller who cannot use it, so an agent is not
 * shown a capability it will be refused for using — but `tools/call` never
 * consults that list, so [sendEmail] asks again. An agent that names a tool it
 * was never offered is exactly the caller this surface assumes.
 *
 * ── The fourth exception: the forums, retired but still standing ────────────
 *
 * Read this section in the past tense. LNL-190 retired discussions and private
 * messages ahead of the permission rework, and the fifteen forum tools below are
 * offered to nobody — see [toolsFor]. Every definition, handler and refusal is
 * still here and still correct, so what follows describes what they do when they
 * come back rather than what any caller sees today.
 *
 * LNL-30 settled that forums would get no MCP tools. LNL-78 asked for them, for
 * a reason that was not on the table then: a forum's history has to be
 * importable and exportable, and the Discussion tab is the only way into those
 * tables. So thirteen tools — `list_forums`, `create_forum`, `update_forum`,
 * `delete_forum`, `reorder_forums`, `list_forum_posts`, `get_forum_post`,
 * `create_forum_post`, `update_forum_post`, `delete_forum_post`,
 * `create_forum_comment`, `update_forum_comment`, `delete_forum_comment` — plus
 * two new targets on `start_attachment_upload`, without which an imported
 * discussion arrives with every image broken.
 *
 * They are **system administrator only**, which is the ticket's own decision and
 * makes them the largest departure in this file from "an agent gets what the
 * person driving it has in the web app". An ordinary user posts in forums; their
 * agent does not. See [AccessControl.canUseForumTools] for the argument — briefly,
 * a forum is a room full of other people's words that records no history, and
 * posting in one mails everybody watching it.
 *
 * Two consequences worth stating out loud rather than discovering:
 *
 *  - **Gated twice, and the second one enforces.** The thirteen are filtered out
 *    of `tools/list` for a non-admin, exactly as `send_email` is — and every one
 *    of them asks again, because `tools/call` never consults that list.
 *  - **A backfilled write announces nothing.** `create_forum_post` and
 *    `create_forum_comment` fire the same notifications the web routes fire, so
 *    an agent starting a genuine thread reaches the forum's watchers — *unless*
 *    the call carries `author`, `author_external` or `created_at`, which is the
 *    signature of an import. Mailing a project once per row while five thousand
 *    posts land is not a courtesy anybody wants, and a post dated 2019 is not
 *    news. See [announceForumPost].
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.clientserver.formatByteSize

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
 *
 * This is the part everyone gets. What a particular caller is told on top of it
 * is [McpTools.instructionsFor]'s job — see [MCP_AGENT_MAIL_INSTRUCTIONS], which
 * is the only such part so far.
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
        get_board takes an optional `status` to return just one of them; the
        vocabulary still comes back in full, so this costs you nothing but the
        issues you did not ask for.
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
    grant roles, or delete an attachment on its own. Those tools do not exist. If
    a task needs one, say so — do not approximate it.

    Deleting, which you CAN do: delete_issue and delete_comment are permanent and
    there is no trash. Both are gated on the same rights the web app applies, so a
    refusal is final in the way described above. Two habits go with them. Confirm
    with the person first unless they have already named the thing they want gone
    — a broad instruction to tidy a board is not permission to destroy rows. And
    prefer the reversible move: an issue that will not be done belongs in a closing
    status with a resolution, which keeps the trail, and a comment that is merely
    wrong can be edited. Deletion is for things that should never have been filed.

    Attaching a file takes two steps and the second one is yours: start_attachment_upload
    hands back a URL, and the bytes only arrive when you push them at it with a
    shell command. They never travel through this conversation — do not read a
    file in order to upload it, and do not base64 anything. Uploading also does
    not put the file into any text: the tool tells you the url to use, and writing
    the markdown that points at it is a separate edit you make yourself.

    Backfilling history, if and only if you are acting as a system administrator: create_issue,
    add_comment, update_issue and update_comment take an optional `author`, an
    optional `author_external` and an optional `created_at`, for importing issues
    from somewhere else so that they keep the name and date they had — and, on the
    two update tools, for fixing one after the fact. All three are admin-only and
    are refused outright — not ignored — for anyone else, so do not send them
    speculatively: a refusal costs a round-trip, and the alternative you might
    imagine (it silently files under your own name) is exactly what the refusal
    exists to prevent.

    Which author parameter to use is a question about the person, not a fallback
    chain. `author` is for somebody who has a Lunicle account: name them by their
    display name as it appears on the board, or by the email address on their
    account if two people share a name. It is refused if no account matches, and
    that refusal is load-bearing — it is how a misspelled name gets caught instead
    of quietly becoming a stranger. `author_external` is for somebody who has no
    account at all, which is the ordinary case when importing from another tracker:
    it records the name as written, creates nothing, and grants nobody anything.
    Passing both is refused. `created_at` is epoch milliseconds and cannot be in
    the future. As a system administrator you can also correct all three after the fact —
    update_issue on an issue, update_comment on a comment — so a name or date that
    came in wrong is fixable in place rather than only at creation; for anyone else
    they are set once, when the row is written.

    `created_at` also sets the issue's last-touched time, which is what the board
    sorts on — but only at creation. Every later edit re-stamps that column with
    the wall clock, and an import usually ends in an edit: an inline image can only
    be attached once the issue exists, so the description has to be rewritten
    afterwards to point at the uploaded file. Done naively that lands a years-old
    issue at the top of the board dated today. `update_issue` therefore takes an
    admin-only `updated_at` — pass the date the imported history actually ended.
    It cannot be in the future, and cannot precede the issue's own `created_at`.

    An issue or comment filed under `author_external` is unowned: nobody can edit
    it afterwards except a system administrator. That is a consequence of there being no account
    to own it, not an oversight, and it applies to imported attachments too.

    Saying you are an agent: create_issue and add_comment take an optional
    `agent_name`, and unlike the backfill parameters above this is one you should
    NORMALLY SEND. Put your own name in it — the assistant or product you are — so
    that the board shows clearly an agent filed the issue or wrote the comment
    rather than a human typing it by hand. It is not admin-only and does not change
    who the issue belongs to: it rides alongside the user's own account as a label.
    Leave it out only when the user has explicitly asked you to act purely as them
    with no agent attribution — that is the override, and it is the only reason to
    omit it.

    Writing issues well: the title is one line and is what people see on the card,
    so make it a statement of the problem rather than a category. The description
    is markdown and is the place for detail — steps, context, what was expected.
    Prefer filing one issue per problem over one issue listing several.
""".trimIndent()

/**
 * The `send_email` paragraph, appended to [MCP_INSTRUCTIONS] only for a caller
 * who may actually send — see [McpTools.instructionsFor].
 *
 * Conditional rather than a sentence saying "system administrators only", which
 * is how the backfill parameters above handle the same problem. The difference is
 * that those parameters live on tools everybody is offered, so a non-admin's
 * agent has to be told they exist in order to be told not to reach for them.
 * `send_email` is not in a non-admin's `tools/list` at all, and a paragraph
 * explaining a tool that is not in the list is worse than silence: the model's
 * options are to hallucinate the call or to tell the person about a capability
 * they do not have. This text is also paid for on every conversation — see
 * [MCP_INSTRUCTIONS] — and most of them are not an admin's.
 */
internal val MCP_AGENT_MAIL_INSTRUCTIONS = """
    E-mailing the user: send_email writes to the person you are acting as, and to
    nobody else — there is no recipient parameter, because the address is the one
    on their own account. Use it for something they will want to read after they
    have stopped watching: a long job that finished, a summary of what changed,
    something you hit that needs their judgement. One message when there is
    something to say, not one per step. Every message is visibly marked as coming
    from an agent, so do not spend the subject or the first line saying so.
""".trimIndent()

/**
 * The forum paragraphs, appended to [MCP_INSTRUCTIONS] for a caller who is offered
 * the forum tools — which since LNL-190 is nobody, so this reaches no conversation
 * at all. See [McpTools.instructionsFor], which is where it goes back.
 *
 * Conditional for [MCP_AGENT_MAIL_INSTRUCTIONS]' reason, which applies with more
 * force here: this is the longest block of text in the file, and describing
 * thirteen tools that are not in the caller's `tools/list` would be paid for on
 * every ordinary user's conversation in exchange for teaching their model to
 * hallucinate calls.
 */
@Suppress("unused")
internal val MCP_FORUM_INSTRUCTIONS = """
    Discussion forums, which you can reach because you are acting as a system
    administrator: a project may have forums, a forum holds posts, and a post
    holds a flat list of comments. There is no nesting and no threading. Start at
    list_forums for a project, then list_forum_posts for a forum, then
    get_forum_post for one post with every comment in full.

    These tools exist mainly so that discussion history can be MOVED — imported
    from another system, or exported out of this one. That is worth knowing
    before you reach for them, because it is what the shape is for:
    get_forum_post returns whole bodies rather than excerpts, and the create and
    update tools take the same `author`, `author_external` and `created_at`
    backfill parameters the issue tools take, with the same rules and the same
    refusals.

    What is different from the issue side, and matters:
      • FORUMS RECORD NO HISTORY. An issue keeps an audit trail; a post does not.
        Nothing anywhere will say what you changed or removed, so a wrong edit or
        a wrong delete leaves no evidence and no way back. Read that as a reason
        to confirm before writing, not as permission to be casual.
      • DELETING A FORUM DELETES EVERYTHING IN IT — every post, every comment,
        every attached file — in one call, permanently. Never do it on a broad
        instruction. Confirm the forum by name with the person first.
      • A post has a created date and nothing else. There is no "last edited",
        because there is no history to hang one on.
      • Posting is not quiet. A new post e-mails everybody watching the forum and
        a new comment e-mails everybody watching the post — UNLESS the call is a
        backfill, meaning it carries `author`, `author_external` or `created_at`.
        So an import is silent, and a post you write as yourself, now, is not.
        Do not start threads people did not ask for.

    Attaching a file to a post or a comment is start_attachment_upload with
    `forum_post_id` or `forum_comment_id` instead of `issue_id` — same two-step
    dance, same rules, and the same reminder that writing the markdown that
    points at the uploaded file is a separate edit you make yourself, with
    update_forum_post or update_forum_comment.
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

/**
 * Read a boolean argument, or null if it is absent or unrecognisable.
 *
 * Tolerates the string forms `"true"`/`"false"` for [long]'s reason: a model that
 * sends a boolean as a quoted string is being ordinary, not wrong, and failing on
 * it would be a refusal about JSON rather than about the request.
 */
private fun JsonObject.bool(name: String): Boolean? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    return when (primitive.contentOrNull?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/** Read a string-array argument, or null if it is absent. Absent and empty are different. */
private fun JsonObject.strings(name: String): List<String>? =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/**
 * Read an array of numeric ids, or null if it is absent or any element is not a
 * number.
 *
 * `mapNotNull`, deliberately, is what this must NOT be: `reorder_forums` takes
 * the whole list and refuses anything that is not exactly it, so an element
 * silently dropped would turn "these five, in this order" into a refusal about a
 * list the caller did not send — or, worse on a future caller, into a partial
 * apply. One bad element makes the whole argument unreadable. Numbers-as-strings
 * are tolerated for [long]'s reason.
 */
private fun JsonObject.longs(name: String): List<Long>? {
    val array = this[name] as? JsonArray ?: return null
    return array.map { element ->
        val primitive = element as? JsonPrimitive ?: return null
        primitive.doubleOrNull?.toLong()
            ?: primitive.contentOrNull?.trim()?.toDoubleOrNull()?.toLong()
            ?: return null
    }
}

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

private fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
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

    /**
     * Every tool this server has, whoever is asking.
     *
     * Not what a caller is offered — that is [toolsFor], which is what `tools/list`
     * answers with. This is the whole table, and it stays public because a test
     * that reads a tool's schema wants the tool, not a user who can reach it.
     */
    val tools: List<McpTool> = listOf(
        McpTool(
            name = "list_projects",
            description = "List the Lunicle projects you can see. Start here. Returns each " +
                "project's id, name and issue prefix — `keyPrefix`, the FOO in FOO-123.",
            inputSchema = schema(),
        ),
        McpTool(
            name = "get_board",
            description = "Get a project's whole board: its statuses (columns, in order), " +
                "priorities, resolutions, labels, components, its sprints if it has any, " +
                "and every issue on it. This is " +
                "the call that tells you what this project's vocabulary is called — you " +
                "address all of it by name in the other tools.\n\n" +
                "Pass `status` to get one column instead of the whole board. The " +
                "vocabulary comes back in full either way, so a filtered call still " +
                "tells you every status, priority, label and component this project " +
                "has — it is the `issues` array, and only that, which narrows. Use it " +
                "on a big board when you only care about one column: the whole board " +
                "can be large enough to be awkward to read in one piece.",
            inputSchema = schema(
                "project_id" to integerProp("The project's id, from list_projects."),
                "project_name" to stringProp("The project's name, if you do not have its id."),
                "status" to stringProp(
                    "A status name from this project — return only the issues in that " +
                        "column. Omit it for the whole board. A name this project does " +
                        "not have is refused with the list of the ones it does, rather " +
                        "than quietly returning nothing.",
                ),
            ),
        ),
        McpTool(
            name = "get_issue",
            description = "Get one issue in full: its description, all its comments, and its " +
                "history — what changed, who changed it, and when. " +
                "get_board returns titles only, so use this when the detail matters.\n\n" +
                "`history` is oldest first, one entry per change, and is the only way to ask " +
                "when an issue was moved or by whom: get_board's `updatedAt` is a " +
                "last-touched stamp for any edit and cannot tell a close from a later typo " +
                "fix. Each entry has an `id` and a `kind` — CREATED, TITLE_CHANGED, DESCRIPTION_CHANGED, " +
                "STATUS_CHANGED, ASSIGNEE_CHANGED, LABELS_CHANGED, COMPONENTS_CHANGED — plus " +
                "`author`, `createdAt`, and `agentName` when an agent made the change. " +
                "STATUS_CHANGED and TITLE_CHANGED carry the new value in `value`; " +
                "ASSIGNEE_CHANGED carries the new assignee there, and omits it when the issue " +
                "was unassigned. LABELS_CHANGED and COMPONENTS_CHANGED carry the whole set as " +
                "it stood afterwards in `values`, not a delta. A status, label or component is " +
                "named as it was AT THE TIME, so a column renamed since reads by its old name. " +
                "Priority and resolution changes are not recorded, so a STATUS_CHANGED into a " +
                "closing column does not say why it was closed. Newer builds may add kinds. A " +
                "system administrator can correct an entry's author or date — but never what it " +
                "records — with update_history_event, addressing it by that `id`.",
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
                "assignee" to stringProp(
                    ASSIGNEE_PROP_DESCRIPTION + " Omit it and the issue starts unassigned, " +
                        "which is what an issue filed in the web app gets.",
                ),
                "sprint" to stringProp(
                    SPRINT_PROP_DESCRIPTION + " Omit it and the issue starts in the backlog.",
                ),
                "planned_version" to stringProp(
                    PLANNED_VERSION_PROP_DESCRIPTION + " Omit it and the issue starts with none.",
                ),
                "fixed_version" to stringProp(
                    FIXED_VERSION_PROP_DESCRIPTION + " Omit it and the issue starts with none.",
                ),
                "parent_id" to integerProp(
                    PARENT_PROP_DESCRIPTION + " Omit it and the issue starts belonging under no epic.",
                ),
                "agent_name" to stringProp(AGENT_NAME_PROP_DESCRIPTION),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(
                    "SYSTEM ADMINISTRATOR ONLY, for backfilling. When this issue was written, in epoch " +
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
                "assignee" to stringProp(
                    ASSIGNEE_PROP_DESCRIPTION + " Omit it to leave the current assignee alone — " +
                        "sending null is how you unassign, not how you say nothing.",
                ),
                "sprint" to stringProp(
                    SPRINT_PROP_DESCRIPTION + " Omit it to leave the issue where it is — sending " +
                        "null is how you move it to the backlog, not how you say nothing.",
                ),
                "planned_version" to stringProp(
                    PLANNED_VERSION_PROP_DESCRIPTION + " Omit it to leave the current planned version alone — " +
                        "null clears it, not says nothing.",
                ),
                "fixed_version" to stringProp(
                    FIXED_VERSION_PROP_DESCRIPTION + " Omit it to leave the current fixed version alone — " +
                        "null clears it, not says nothing.",
                ),
                "parent_id" to integerProp(
                    PARENT_PROP_DESCRIPTION + " Omit it to leave the current parent alone; send null to " +
                        "detach the issue from its epic.",
                ),
                "agent_name" to stringProp(
                    "Set or change the agent label on this issue — your own name as the agent " +
                        "making the edit. Omitting it leaves whatever is already there. Not " +
                        "admin-only, and it does not change who the issue belongs to. To REMOVE " +
                        "the badge — so a purely-human or migrated issue wears none — a system " +
                        "administrator sends an empty string; a non-admin who tries is refused.",
                ),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(
                    "SYSTEM ADMINISTRATOR ONLY, for correcting backfilled history. When this issue should claim " +
                        "to have been written, in epoch milliseconds. Cannot be in the future, and " +
                        "cannot land after `updated_at`. Refused, not ignored, if you are not an " +
                        "admin. Leaves the existing date if omitted.",
                ),
                "updated_at" to integerProp(UPDATED_AT_PROP_DESCRIPTION),
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
                "fixed_version" to stringProp(
                    FIXED_VERSION_PROP_DESCRIPTION + " Only consulted when closing; a move between " +
                        "open columns leaves it alone.",
                ),
                // The move is already recorded under this name in the issue's
                // history — see moveIssue — but an undeclared parameter is one no
                // agent will ever send, so the events said "a human moved it" for
                // every move an agent made. Declared here for the same reason
                // get_issue reports history at all: auditing what an agent did to a
                // board only works if the board wrote down that it was an agent.
                "agent_name" to stringProp(AGENT_NAME_PROP_DESCRIPTION),
                required = listOf("issue_id", "status"),
            ),
        ),
        McpTool(
            name = "watch_issue",
            description = "Watch an issue, or stop watching it — the same toggle as the issue " +
                "detail's notification bell. A watcher is e-mailed when the issue changes: new " +
                "comments, moves, edits.\n\n" +
                "By default this is about YOU — it adds or removes your own watch, which any " +
                "signed-in user who can see the issue may do (you need an e-mail address on your " +
                "account to actually receive anything). Name someone else in `user` to watch or " +
                "unwatch ON THEIR BEHALF; that is SYSTEM ADMINISTRATOR ONLY, since it decides " +
                "another person's inbox. Idempotent either way — watching what you already watch, " +
                "or unwatching what you do not, changes nothing and still reports where it left " +
                "things. get_issue lists an issue's current watchers.",
            inputSchema = schema(
                "issue_id" to integerProp("The issue to watch or unwatch."),
                "watching" to boolProp("true to watch — the default — or false to stop watching."),
                "user" to stringProp(
                    "Whose watch to change: a display name exactly as the board shows it, or the " +
                        "email address on their account. Omit it to change your own. Naming " +
                        "somebody else is SYSTEM ADMINISTRATOR ONLY — refused, not ignored, if you " +
                        "are not one — and an ambiguous name is refused rather than guessed at; use " +
                        "the email address to settle it.",
                ),
                required = listOf("issue_id"),
            ),
        ),
        McpTool(
            name = "add_comment",
            description = "Post a comment on an issue. Written and published in one call.",
            inputSchema = schema(
                "issue_id" to integerProp("The issue to comment on."),
                "body" to stringProp("The comment, in markdown."),
                "agent_name" to stringProp(AGENT_NAME_PROP_DESCRIPTION),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(
                    "SYSTEM ADMINISTRATOR ONLY, for backfilling. When this comment was written, in epoch " +
                        "milliseconds. Cannot be in the future. Refused, not ignored, if you are " +
                        "not a system administrator. Defaults to now.",
                ),
                required = listOf("issue_id", "body"),
            ),
        ),
        McpTool(
            name = "update_comment",
            description = "Change an existing comment. Every field is optional; omit one to " +
                "leave it as it is. You may edit a comment that is your own, or any comment if " +
                "you are acting as a system administrator — the same rule the web app applies.",
            inputSchema = schema(
                "comment_id" to integerProp("The comment to change."),
                "body" to stringProp("A new body, in markdown. Replaces the old one entirely."),
                "agent_name" to stringProp(
                    "Set or change the agent label on this comment — your own name as the agent " +
                        "making the edit. Omitting it leaves whatever is already there. Not " +
                        "admin-only, and it does not change who the comment belongs to — it only " +
                        "labels the row. To REMOVE the badge, a system administrator sends an " +
                        "empty string; a non-admin who tries is refused.",
                ),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(
                    "SYSTEM ADMINISTRATOR ONLY. When this comment should claim to have been written, in epoch " +
                        "milliseconds. Cannot be in the future. Refused, not ignored, if you are " +
                        "not a system administrator. Leaves the existing date if omitted.",
                ),
                required = listOf("comment_id"),
            ),
        ),
        McpTool(
            name = "update_history_event",
            description = "SYSTEM ADMINISTRATOR ONLY. Correct who made one history entry, or when — and " +
                "nothing else about it. Each entry in an issue's `history` (from get_issue) carries " +
                "an `id`; pass that here to re-attribute that one entry.\n\n" +
                "This exists for one job. History imported from another tracker lands under a " +
                "placeholder author, and when that person later gets a real Lunicle account their " +
                "CREATED, STATUS_CHANGED and other entries can be moved onto it rather than naming a " +
                "stranger forever. WHAT happened — the `kind` of change and the value or values it " +
                "carries — cannot be edited here or by any tool; only the entry's author, its date " +
                "and its agent label can. A history whose events you could re-word would not be one. " +
                "Refused, not ignored, if you are not a system administrator.",
            inputSchema = schema(
                "event_id" to integerProp(
                    "The history entry to change — its `id`, from get_issue's `history` array.",
                ),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "agent_name" to stringProp(
                    "Set or change the agent label on this entry. Omitting it leaves whatever is " +
                        "already there. It does not change who the entry belongs to — it only " +
                        "labels the row. Send an empty string to REMOVE the badge, which is what a " +
                        "migrated entry wants: it was not an agent's, so it should wear no badge. " +
                        "(Clearing is admin-only, but this whole tool already is.)",
                ),
                "created_at" to integerProp(
                    "SYSTEM ADMINISTRATOR ONLY. When this entry should claim to have happened, in epoch " +
                        "milliseconds. Cannot be in the future. The history is ordered by entry, not " +
                        "by date, so re-dating an entry does not move it. Refused, not ignored, if " +
                        "you are not a system administrator. Leaves the existing date if omitted.",
                ),
                required = listOf("event_id"),
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
                "issue_id" to integerProp("Attach to this issue's description. Give exactly one target."),
                "comment_id" to integerProp("Attach to this comment. Give exactly one target."),
                "forum_post_id" to integerProp(
                    "Attach to this forum post's body. System administrator only, like the rest of " +
                        "the forum tools. Give exactly one target.",
                ),
                "forum_comment_id" to integerProp(
                    "Attach to this forum comment. System administrator only. Give exactly one target.",
                ),
                "filename" to stringProp(
                    "The name to store it under, as it should appear to someone downloading it. " +
                        "Fixed now: the upload cannot rename it.",
                ),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(
                    "SYSTEM ADMINISTRATOR ONLY, for backfilling. When this file was uploaded, in epoch " +
                        "milliseconds. Cannot be in the future. Refused, not ignored, if you are " +
                        "not a system administrator. Defaults to whenever the bytes land.",
                ),
                required = listOf("filename"),
            ),
        ),
        McpTool(
            name = "delete_attachment",
            description = "SYSTEM ADMINISTRATOR ONLY. Delete one stored attachment — its row and the " +
                "bytes on disk both — by the id in its URL. Permanent, and there is no trash.\n\n" +
                "This is a cleanup tool for a file nothing points at any more: the ordinary way an " +
                "attachment dies is with the issue, comment or post it hangs on, which takes its " +
                "attachments with it. Deleting one out from under a body that still shows it leaves " +
                "a broken image there, so CHECK IT IS UNREFERENCED FIRST — the answer says which " +
                "issue, comment or post it was attached to, and whether that body still mentions the " +
                "URL is yours to confirm before and after.\n\n" +
                "`attachment` is the id from the URL: the `pFN33v…` in " +
                "`/api/attachments/pFN33v…/view`. The whole URL is accepted too, and the bare id.",
            inputSchema = schema(
                "attachment" to stringProp(
                    "The attachment's public id, or a full `/api/attachments/<id>/view` URL to take " +
                        "it from. This is the id a reader sees, not the numeric row id.",
                ),
                required = listOf("attachment"),
            ),
        ),
        McpTool(
            name = "create_sprint",
            description = "Create a sprint in a project. It lands at the end of the project's sprint " +
                "list and is NOT activated — creating next quarter's sprint in advance must not " +
                "point the board at it. Start it separately with set_active_sprint.\n\n" +
                "A PROJECT-ADMINISTRATOR action: you can do this only in a project you administer, " +
                "exactly as in the web app. A sprint's name must be unique within its project.",
            inputSchema = schema(
                "project_id" to integerProp("Which project to make it in."),
                "project_name" to stringProp("The project's name, if you do not have its id."),
                "name" to stringProp("What the sprint is called. Unique within the project."),
                required = listOf("name"),
            ),
        ),
        McpTool(
            name = "set_active_sprint",
            description = "Point a project's board at a sprint, or at none. A PROJECT-ADMINISTRATOR " +
                "action, in a project you administer.\n\n" +
                "Pass `sprint` as a sprint name to activate it — this is starting the sprint. Pass " +
                "`sprint` as null to leave the project with no active sprint, which is a real state " +
                "between sprints and not the same as completing one. A completed sprint cannot be " +
                "activated. Re-activating the one already active is allowed and does nothing.",
            inputSchema = schema(
                "project_id" to integerProp("Whose board is being pointed."),
                "project_name" to stringProp("The project's name, if you do not have its id."),
                "sprint" to stringProp(
                    "A sprint name from get_board to activate, or null to clear the active sprint.",
                ),
                required = listOf("sprint"),
            ),
        ),
        McpTool(
            name = "complete_sprint",
            description = "Finish a sprint and roll its unfinished work forward. A PROJECT-ADMINISTRATOR " +
                "action, in a project you administer. Permanent: a completed sprint cannot be " +
                "reopened, activated, or planned into.\n\n" +
                "Its unfinished issues — everything not in a closing column — move to " +
                "`move_unfinished_to`: another sprint's name to carry them into the next one, or " +
                "null to drop them to the backlog. The two are genuinely different intentions, so " +
                "there is no default; say which. Finished issues stay put. If this was the active " +
                "sprint, the board is left with none active.",
            inputSchema = schema(
                "project_id" to integerProp("Whose sprint is being completed."),
                "project_name" to stringProp("The project's name, if you do not have its id."),
                "sprint" to stringProp("The sprint to complete, by name."),
                "move_unfinished_to" to stringProp(
                    "Where unfinished work goes: another (open) sprint's name, or null for the backlog.",
                ),
                required = listOf("sprint"),
            ),
        ),
        McpTool(
            name = "delete_issue",
            description = "Delete an issue, permanently. Its comments, its attachments and its " +
                "history go with it — this is not a status change and there is no trash to " +
                "recover it from.\n\n" +
                "You may delete an issue that is your own, or any issue if you hold " +
                "change_unowned_issues in its project, or anything at all as a system administrator — the same " +
                "rule the web app's Delete button applies, which is also the rule for editing " +
                "one. Nothing here is deletable that you could not already have emptied of every " +
                "word it said.\n\n" +
                "CONFIRM WITH THE PERSON FIRST unless they have already asked for this exact " +
                "issue to go. \"Tidy up the board\" is not that. If what they want is to record " +
                "that the work will not be done, move_issue to a closing status with a " +
                "resolution instead — that keeps the trail, and it is almost always what was " +
                "meant.",
            inputSchema = schema(
                "issue_id" to integerProp("The issue to delete."),
                required = listOf("issue_id"),
            ),
        ),
        McpTool(
            name = "delete_comment",
            description = "Delete a comment, permanently, along with any files attached to it. " +
                "There is no trash to recover it from.\n\n" +
                "You may delete a comment that is your own, or any comment if you are acting as " +
                "a system administrator — the same rule that governs editing one. Note this is NARROWER than " +
                "delete_issue: holding change_unowned_issues lets you delete somebody's issue " +
                "but never their words.\n\n" +
                "CONFIRM WITH THE PERSON FIRST unless they have already asked for this exact " +
                "comment to go. If the point is to correct something rather than erase it, " +
                "update_comment keeps the thread readable.",
            inputSchema = schema(
                "comment_id" to integerProp("The comment to delete."),
                required = listOf("comment_id"),
            ),
        ),

        // ── Forums, system administrator only ────────────────────────────────
        //
        // Thirteen tools, filtered out of tools/list for everybody else — see
        // toolsFor, canUseForumTools, and this file's fourth exception.

        McpTool(
            name = "list_forums",
            description = "SYSTEM ADMINISTRATOR ONLY. List a project's discussion forums, in the order " +
                "its administrator put them. Start here for anything to do with the Discussion " +
                "tab: a forum's id is what list_forum_posts takes.",
            inputSchema = schema(
                "project_id" to integerProp("The project's id, from list_projects."),
                "project_name" to stringProp("The project's name, if you do not have its id."),
            ),
        ),
        McpTool(
            name = "create_forum",
            description = "SYSTEM ADMINISTRATOR ONLY. Make a new forum in a project. It lands at the end " +
                "of the project's list — use reorder_forums to move it. A forum's name must be " +
                "unique within its project.",
            inputSchema = schema(
                "project_id" to integerProp("Which project to make it in."),
                "name" to stringProp(
                    "What the forum is called, at most $MAX_FORUM_NAME_LENGTH characters. Unique " +
                        "within the project, compared case-insensitively.",
                ),
                "description" to stringProp(
                    "One line under the name saying what belongs here, or omit it for none. " +
                        "Truncated at $MAX_FORUM_DESCRIPTION_LENGTH characters rather than refused.",
                ),
                required = listOf("project_id", "name"),
            ),
        ),
        McpTool(
            name = "update_forum",
            description = "SYSTEM ADMINISTRATOR ONLY. Rename a forum or change its description. Omit a " +
                "field to leave it alone; send `description` as null to remove it. A forum cannot " +
                "be moved to another project — that would carry its posts into a project whose " +
                "members never agreed to read them.",
            inputSchema = schema(
                "forum_id" to integerProp("The forum to change, from list_forums."),
                "name" to stringProp("A new name. Still has to be unique in the project."),
                "description" to stringProp("A new one-line description, or null to remove it."),
                required = listOf("forum_id"),
            ),
        ),
        McpTool(
            name = "delete_forum",
            description = "SYSTEM ADMINISTRATOR ONLY. Delete a forum AND EVERYTHING IN IT: every post, " +
                "every comment on every post, and every file attached to any of them. Permanent, " +
                "and there is no trash.\n\n" +
                "This is the most destructive tool on this server. Forums record no history, so " +
                "afterwards nothing anywhere will say what was in it. CONFIRM THE FORUM BY NAME " +
                "WITH THE PERSON FIRST, and tell them how many posts are about to go — " +
                "list_forum_posts will tell you. A broad instruction to tidy up a project is " +
                "never permission to run this.\n\n" +
                "The answer says what was destroyed, because after the call nobody can look.",
            inputSchema = schema(
                "forum_id" to integerProp("The forum to delete."),
                required = listOf("forum_id"),
            ),
        ),
        McpTool(
            name = "reorder_forums",
            description = "SYSTEM ADMINISTRATOR ONLY. Put a project's forums in a given order, 0 first. " +
                "`forum_ids` must name EXACTLY this project's forums — all of them, none twice, " +
                "and none from anywhere else. Anything short of that is refused rather than " +
                "partly applied, so read the current order from list_forums and send it back " +
                "rearranged.",
            inputSchema = schema(
                "project_id" to integerProp("Whose forums are being ordered."),
                "forum_ids" to buildJsonObject {
                    put("type", "array")
                    putJsonObject("items") { put("type", "integer") }
                    put("description", "Every forum id in this project, in the order you want them.")
                },
                required = listOf("project_id", "forum_ids"),
            ),
        ),
        McpTool(
            name = "list_forum_posts",
            description = "SYSTEM ADMINISTRATOR ONLY. A forum's posts, newest first, with each one's " +
                "author, date, comment count and who last replied. Bodies are NOT included — this " +
                "is the index. Use get_forum_post for one post in full.\n\n" +
                "Unpublished drafts never appear here; a draft is somebody's unsent text.",
            inputSchema = schema(
                "forum_id" to integerProp("The forum, from list_forums."),
                required = listOf("forum_id"),
            ),
        ),
        McpTool(
            name = "get_forum_post",
            description = "SYSTEM ADMINISTRATOR ONLY. One post in full — its whole markdown body — with " +
                "every comment on it, in order, also in full. This is the unit to export: a post " +
                "and its thread come back in one call.\n\n" +
                "Note there is no history and no `updatedAt`: a post carries when it was written " +
                "and nothing about when it was last touched. That is the schema's decision, not " +
                "an omission in this response.",
            inputSchema = schema(
                "post_id" to integerProp("The post's id, from list_forum_posts."),
                required = listOf("post_id"),
            ),
        ),
        McpTool(
            name = "watch_forum",
            description = "SYSTEM ADMINISTRATOR ONLY. Watch a forum, or stop — the notification bell on " +
                "the forum. A watcher is e-mailed when a new post appears in it.\n\n" +
                "By default this is about YOU: it adds or removes your own watch. Name someone in " +
                "`user` to change theirs instead, on their behalf. Idempotent — watching what you " +
                "already watch changes nothing and still reports where it left things. list_forums " +
                "reports whether you are watching each forum.",
            inputSchema = schema(
                "forum_id" to integerProp("The forum to watch or unwatch, from list_forums."),
                "watching" to boolProp("true to watch — the default — or false to stop watching."),
                "user" to stringProp(
                    "Whose watch to change: a display name, or the e-mail on their account. Omit for " +
                        "your own. Watching needs an e-mail address on the account to notify.",
                ),
                required = listOf("forum_id"),
            ),
        ),
        McpTool(
            name = "watch_forum_post",
            description = "SYSTEM ADMINISTRATOR ONLY. Watch a single post's thread, or stop. A watcher is " +
                "e-mailed when a new comment is added to the post.\n\n" +
                "By default about YOU; name someone in `user` to change theirs. Idempotent, exactly " +
                "as watch_forum. get_forum_post reports whether you are watching the post.",
            inputSchema = schema(
                "post_id" to integerProp("The post to watch or unwatch, from list_forum_posts."),
                "watching" to boolProp("true to watch — the default — or false to stop watching."),
                "user" to stringProp(
                    "Whose watch to change: a display name, or the e-mail on their account. Omit for " +
                        "your own. Watching needs an e-mail address on the account to notify.",
                ),
                required = listOf("post_id"),
            ),
        ),
        McpTool(
            name = "create_forum_post",
            description = "SYSTEM ADMINISTRATOR ONLY. Start a new post in a forum. Written and published " +
                "in one call — there is no draft left behind if it fails.\n\n" +
                "THIS E-MAILS PEOPLE. Everybody watching the forum is notified, exactly as if a " +
                "person had posted — unless the call carries `author`, `author_external` or " +
                "`created_at`, which marks it as imported history and sends nothing. So a " +
                "backfill is silent and a post you write as yourself is not. Do not start threads " +
                "nobody asked for.",
            inputSchema = schema(
                "forum_id" to integerProp("Which forum to post in, from list_forums."),
                "title" to stringProp("One line, which is what the post list shows."),
                "body" to stringProp("Markdown. The post itself."),
                "agent_name" to stringProp(AGENT_NAME_PROP_DESCRIPTION),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(FORUM_CREATED_AT_PROP_DESCRIPTION),
                required = listOf("forum_id", "title", "body"),
            ),
        ),
        McpTool(
            name = "update_forum_post",
            description = "SYSTEM ADMINISTRATOR ONLY. Rewrite an existing post: its title, its body, and " +
                "— because this is the import-repair path — who is recorded as having written it " +
                "and when. Omit a field to leave it as it is.\n\n" +
                "This is also how an uploaded image gets into a post: start_attachment_upload " +
                "hands you a url, and putting it into the body is this call.\n\n" +
                "Nothing is notified by an update, and nothing records that it happened. The web " +
                "app has no edit button for a post at all, so an edit here is invisible to " +
                "everyone — which is exactly why it is limited to a system administrator and " +
                "should be used to correct imports rather than to change what somebody said.",
            inputSchema = schema(
                "post_id" to integerProp("The post to change."),
                "title" to stringProp("A new title."),
                "body" to stringProp("A new body, in markdown. Replaces the old one entirely."),
                "agent_name" to stringProp(
                    "Set or change the agent label on this post. Omitting it leaves whatever is " +
                        "already there; it is not how you clear a badge.",
                ),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(
                    "SYSTEM ADMINISTRATOR ONLY. When this post should claim to have been written, in epoch " +
                        "milliseconds. Cannot be in the future. Leaves the existing date if omitted.",
                ),
                required = listOf("post_id"),
            ),
        ),
        McpTool(
            name = "delete_forum_post",
            description = "SYSTEM ADMINISTRATOR ONLY. Delete a post, every comment on it, and every file " +
                "attached to any of them. Permanent, with no trash and no history that it " +
                "happened.\n\n" +
                "CONFIRM WITH THE PERSON FIRST unless they have already named this exact post. " +
                "Unlike an issue, there is no closing status to move it to instead — a post is " +
                "either there or gone.",
            inputSchema = schema(
                "post_id" to integerProp("The post to delete."),
                required = listOf("post_id"),
            ),
        ),
        McpTool(
            name = "create_forum_comment",
            description = "SYSTEM ADMINISTRATOR ONLY. Comment on a forum post. Comments are flat — there " +
                "is no replying to a comment, only to the post.\n\n" +
                "THIS E-MAILS PEOPLE: everybody watching the post, including its author, unless " +
                "the call is a backfill carrying `author`, `author_external` or `created_at`. See " +
                "create_forum_post.",
            inputSchema = schema(
                "post_id" to integerProp("The post to comment on."),
                "body" to stringProp("The comment, in markdown."),
                "agent_name" to stringProp(AGENT_NAME_PROP_DESCRIPTION),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(FORUM_CREATED_AT_PROP_DESCRIPTION),
                required = listOf("post_id", "body"),
            ),
        ),
        McpTool(
            name = "update_forum_comment",
            description = "SYSTEM ADMINISTRATOR ONLY. Rewrite a comment on a forum post — its body, and " +
                "its author and date. Omit a field to leave it alone. Notifies nobody and records " +
                "nothing; see update_forum_post.",
            inputSchema = schema(
                "comment_id" to integerProp("The comment to change, from get_forum_post."),
                "body" to stringProp("A new body, in markdown. Replaces the old one entirely."),
                "agent_name" to stringProp(
                    "Set or change the agent label on this comment. Omitting it leaves whatever is " +
                        "already there.",
                ),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(
                    "SYSTEM ADMINISTRATOR ONLY. When this comment should claim to have been written, in " +
                        "epoch milliseconds. Cannot be in the future. Leaves the existing date if " +
                        "omitted.",
                ),
                required = listOf("comment_id"),
            ),
        ),
        McpTool(
            name = "delete_forum_comment",
            description = "SYSTEM ADMINISTRATOR ONLY. Delete one comment on a forum post, and any files " +
                "attached to it. Permanent, and nothing records that it happened. If the point is " +
                "to correct something rather than erase it, update_forum_comment keeps the thread " +
                "readable.",
            inputSchema = schema(
                "comment_id" to integerProp("The comment to delete."),
                required = listOf("comment_id"),
            ),
        ),

        McpTool(
            name = "send_email",
            description = "E-mail the person you are acting as — and only them. Use it to report " +
                "on work they will read later: a long job that has finished, a summary of what " +
                "you changed, something you found that they should look at. It is not a " +
                "notification system and not a way to reach anyone else.\n\n" +
                "THERE IS NO RECIPIENT PARAMETER. The message goes to the address on the account " +
                "whose token you are holding, always. If that account has no address on it, this " +
                "is refused and the fix is for the person to add one in Lunicle's web app.\n\n" +
                "Every message is marked as coming from an agent: a fixed subject prefix, and a " +
                "line above your text saying it was sent on your behalf rather than typed by a " +
                "person. Write the subject and body as if the reader can see that, because they " +
                "can — do not restate it.\n\n" +
                "The body is PLAIN TEXT, not markdown: asterisks and backticks arrive as " +
                "themselves. Blank lines make paragraphs and single newlines are kept, so lay " +
                "text out with those.\n\n" +
                "Send one message when there is something to say, not one per step. An agent " +
                "that mails a running commentary is an agent whose mail gets filtered.",
            inputSchema = schema(
                "subject" to stringProp(
                    "The subject line, without any prefix of your own — one is added. One short " +
                        "line saying what this is about.",
                ),
                "body" to stringProp("The message, as plain text. Blank lines separate paragraphs."),
                "agent_name" to stringProp(
                    "Your own name as the agent sending this — the assistant or product you are. " +
                        "NORMALLY SET IT: it is named in the header above your text, so the " +
                        "reader can see which agent wrote to them. Omitted, the header says " +
                        "\"an agent\" instead, which is true but less useful.",
                ),
                required = listOf("subject", "body"),
            ),
        ),
    )

    /**
     * The tools [user] is offered, which is what `tools/list` answers with.
     *
     * A filter over [tools] rather than a second table, so a tool cannot be added
     * to one and forgotten in the other. It exists so an agent is not shown a
     * capability it will be refused for using: a model told about `send_email`
     * and then refused has to explain the refusal to somebody who never had the
     * capability in the first place, and a model not told about it simply does
     * something else.
     *
     * **This is an affordance, not the enforcement.** `tools/call` takes a name
     * and never consults this list — see [call], and [AccessControl]'s preamble
     * for the general form of the rule. Every filter here has a matching refusal
     * in the tool itself.
     */
    suspend fun toolsFor(user: UserRecord): List<McpTool> = tools.filter { tool ->
        when (tool.name) {
            "send_email" -> deps.access.canSendAgentMail(user)
            // Whole-tool admin-only, exactly as send_email and the forum tools:
            // editing history is an act nobody but an admin can perform, so a
            // non-admin is not offered it rather than shown a tool that will only
            // refuse them. See updateHistoryEvent, and the ordinary-vs-admin tool
            // list assertion in McpSendEmailTest.
            "update_history_event" -> deps.access.canAttributeWrites(user)
            // Deleting a stored attachment out of band is admin cleanup — the web
            // gives no one a standalone attachment delete, so this is offered only
            // to the account that could be trusted with a brand-new destructive
            // power. See AccessControl.canDeleteAttachment and deleteAttachment.
            "delete_attachment" -> deps.access.canDeleteAttachment(user)
            // Offered to nobody, not even a system administrator: discussions are
            // retired (LNL-190), so the fifteen forum tools are off every caller's
            // list. The tools themselves still stand — their definitions above, their
            // handlers below and their canUseForumTools refusals all untouched — so
            // re-enabling discussions is this line going back to the access check.
            in FORUM_TOOL_NAMES -> false
            else -> true
        }
    }

    /**
     * The orientation [user] gets in `initialize`.
     *
     * [MCP_INSTRUCTIONS] plus whatever this particular caller can act on — which
     * so far is one paragraph, and matches [toolsFor] tool for tool on purpose. A
     * caller offered a tool and not told what it is for gets a worse answer than
     * one told nothing about it.
     */
    suspend fun instructionsFor(user: UserRecord): String = buildString {
        append(MCP_INSTRUCTIONS)
        if (deps.access.canSendAgentMail(user)) append("\n\n").append(MCP_AGENT_MAIL_INSTRUCTIONS)
        // [MCP_FORUM_INSTRUCTIONS] is appended for nobody since LNL-190 retired
        // discussions, exactly as [toolsFor] offers the forum tools to nobody — the
        // two match tool for tool, which is why they were changed together.
    }

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
        "watch_issue" -> watchIssue(user, arguments)
        "add_comment" -> addComment(user, arguments)
        "update_comment" -> updateComment(user, arguments)
        "update_history_event" -> updateHistoryEvent(user, arguments)
        "start_attachment_upload" -> startAttachmentUpload(user, arguments, origin)
        "delete_attachment" -> deleteAttachment(user, arguments)
        "create_sprint" -> createSprint(user, arguments)
        "set_active_sprint" -> setActiveSprint(user, arguments)
        "complete_sprint" -> completeSprint(user, arguments)
        "delete_issue" -> deleteIssue(user, arguments)
        "delete_comment" -> deleteComment(user, arguments)
        "send_email" -> sendEmail(user, arguments)
        "list_forums" -> listForums(user, arguments)
        "create_forum" -> createForum(user, arguments)
        "update_forum" -> updateForum(user, arguments)
        "delete_forum" -> deleteForum(user, arguments)
        "reorder_forums" -> reorderForums(user, arguments)
        "list_forum_posts" -> listForumPosts(user, arguments)
        "get_forum_post" -> getForumPost(user, arguments)
        "create_forum_post" -> createForumPost(user, arguments)
        "update_forum_post" -> updateForumPost(user, arguments)
        "delete_forum_post" -> deleteForumPost(user, arguments)
        "create_forum_comment" -> createForumComment(user, arguments)
        "update_forum_comment" -> updateForumComment(user, arguments)
        "delete_forum_comment" -> deleteForumComment(user, arguments)
        "watch_forum" -> watchForum(user, arguments)
        "watch_forum_post" -> watchForumPost(user, arguments)
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
                            // Retired pending tickets 3–5, which give the tool the
                            // project's audience rows instead. Kept on the output,
                            // reading false, so a running agent's parsing does not
                            // break mid-epic. See ProjectSummary.
                            put("isPublic", false)
                            put("visibleToAllSignedIn", false)
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
        val sprints = deps.sprints.forProject(project.id)
        val versions = deps.versions.forProject(project.id)
        val activeSprintId = deps.projects.activeSprintId(project.id)
        // Refused rather than ignored: a filter silently dropped is a caller who
        // reads an empty column as "nothing to do" when the truth is that they
        // misspelled it. Same rule, and the same message, as moving an issue into
        // a column this project does not have.
        val statusFilter = arguments.string("status")
            ?.let { name ->
                statuses.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: return refuseUnknown("status", name, statuses.map { it.name })
            }
        // Narrowed here rather than at the JSON, so the per-issue lookups below —
        // authors, assignees, and one canEditIssue apiece — are paid for the column
        // asked about instead of the whole board.
        val issues = deps.issues.forProject(project.id)
            .filter { statusFilter == null || it.statusId == statusFilter.id }
        val labelsByIssue = deps.issues.labelsForProject(project.id)
        val componentsByIssue = deps.issues.componentsForProject(project.id)

        val statusNames = statuses.associate { it.id to it.name }
        val priorityNames = priorities.associate { it.id to it.name }
        val resolutionNames = resolutions.associate { it.id to it.name }
        val labelNames = labels.associate { it.id to it.name }
        val componentNames = components.associate { it.id to it.name }
        val sprintNames = sprints.associate { it.id to it.name }
        val versionNames = versions.associate { it.id to it.name }
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
        // Same one-lookup-per-distinct-person rule as `authors` above, and a
        // separate map for the same reason it is separate in buildIssueDetail: an
        // assignee has very often written nothing on the board.
        val assignees = issues.mapNotNull { it.assigneeId }.distinct()
            .mapNotNull { id -> deps.users.findById(id)?.let { id to it.resolvedName } }
            .toMap()

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
                // Absent — not an empty array — when the project has none, so a
                // board with no sprint axis reads as one that has never heard of
                // sprints rather than one that has an empty list of them. An agent
                // working a kanban project should see nothing here to reason about.
                if (sprints.isNotEmpty()) {
                    putJsonArray("sprints") {
                        sprints.forEach { sprint ->
                            add(
                                buildJsonObject {
                                    put("name", sprint.name)
                                    // Present only on finished sprints, for the same
                                    // reason `resolution` is absent on open issues.
                                    // Its presence is the whole signal: work cannot
                                    // be scheduled into a sprint that has one.
                                    sprint.completedAt?.let { put("completedAt", it) }
                                },
                            )
                        }
                    }
                    // The sprint the board scopes to. Absent when nothing is
                    // active, which is a real state between sprints.
                    activeSprintId?.let { id -> sprintNames[id]?.let { put("activeSprint", it) } }
                }
                // Absent — not empty — when the project has no versions, so a board
                // that never uses them reads as one that has never heard of them.
                // See Versions.sq.
                if (versions.isNotEmpty()) {
                    putJsonArray("versions") { versions.forEach { add(it.name) } }
                }
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
                                // Present only when somebody holds the issue —
                                // absent rather than null, like `resolution` above,
                                // so "nobody is on this" reads as a missing key
                                // rather than as a value an agent has to interpret.
                                issue.assigneeId?.let { id -> assignees[id]?.let { put("assignee", it) } }
                                // Present only on issues an agent filed. Says a
                                // human did not type this — see resolveAgentName.
                                issue.agentName?.let { put("agentName", it) }
                                // Absent when the issue is in the backlog, which is
                                // every issue in a project with no sprints.
                                issue.sprintId?.let { id -> sprintNames[id]?.let { put("sprint", it) } }
                                // Absent when unset (LNL-134), like every optional
                                // reference here — "no version" is a missing key, not
                                // a null to interpret.
                                issue.plannedVersionId?.let { id -> versionNames[id]?.let { put("plannedVersion", it) } }
                                issue.fixedVersionId?.let { id -> versionNames[id]?.let { put("fixedVersion", it) } }
                                // The epic this card belongs under, by id — absent when
                                // it belongs under none, like every optional field here
                                // (LNL-55). get_issue reports the parent and children in
                                // full; on the board this one id is enough to see the
                                // shape.
                                issue.parentId?.let { put("parentId", it) }
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
        // The sprint the issue is scheduled into, by name — the same map get_board
        // builds. Read here so get_issue can report the one field it used to omit
        // (LNL-35): the board named an issue's sprint but fetching that issue on its
        // own did not, so an agent could write `sprint` and not read it back.
        val sprintNames = deps.sprints.forProject(issue.projectId).associate { it.id to it.name }
        // The version maps, by name, the same get_board builds — so an agent that
        // wrote `planned_version` or `fixed_version` can read it back here (LNL-134).
        val versionNames = deps.versions.forProject(issue.projectId).associate { it.id to it.name }
        val comments = deps.comments.forIssue(issue.id)
        // Same skip as buildIssueDetail: a draft's history is empty by
        // construction, so asking is a guaranteed-empty query.
        val events = if (issue.isDraft) emptyList() else deps.history?.forIssue(issue.id).orEmpty()
        // Every account named anywhere in the response, resolved in one pass: the
        // issue's author, its comments', its events', and whoever an
        // ASSIGNEE_CHANGED points at. That last group is folded in as an
        // Author.Account only to reach this lookup — the map is keyed on account id
        // and has no opinion about why an id is in it. See buildIssueDetail.
        val authors = (
            comments.map { it.author } + events.map { it.author } +
                events.mapNotNull { it.valueUserId?.let(Author::Account) } + issue.author
            ).mapNotNull { it.accountId }.distinct()
            .mapNotNull { id -> deps.users.findById(id)?.let { id to it.resolvedName } }
            .toMap()
        // Read before the JSON is built — buildJsonObject's lambda is not a
        // coroutine body, so a suspend query cannot happen inside it.
        val issueLabels = deps.issues.labelsFor(issue.id)
        val issueComponents = deps.issues.componentsFor(issue.id)
        val canEdit = deps.access.canEditIssue(user, issue)
        val canComment = deps.access.canComment(user, issue.projectId)
        // Not folded into `authors`: an assignee is usually not one of them.
        val assigneeName = issue.assigneeId?.let { deps.users.findById(it)?.resolvedName }
        // The watcher list the issue detail shows, by name — no address crosses, see
        // SubscriptionStore.watchersForIssue. Read here for the same reason as the
        // rest: buildJsonObject's lambda cannot run a suspend query. Lets an agent
        // that just called watch_issue confirm the result.
        val watchers = deps.subscriptions.watchersForIssue(issue.id)
        // The epic this belongs under, and this issue's own children — read here for
        // the same reason as everything else above, buildJsonObject cannot suspend
        // (LNL-55). Both are in this project, so its prefix names their keys.
        val parentRecord = issue.parentId?.let { deps.issues.findById(it) }
        val childRecords = deps.issues.childrenOf(issue.id)

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
                // Absent when nobody holds it. See getBoard's `assignee`.
                assigneeName?.let { put("assignee", it) }
                // Absent for a backlog issue and for every issue in a project with
                // no sprint axis, exactly as get_board reports it — see getBoard's
                // per-issue `sprint`.
                issue.sprintId?.let { id -> sprintNames[id]?.let { put("sprint", it) } }
                // Absent when unset, like `sprint` — see get_board's per-issue versions.
                issue.plannedVersionId?.let { id -> versionNames[id]?.let { put("plannedVersion", it) } }
                issue.fixedVersionId?.let { id -> versionNames[id]?.let { put("fixedVersion", it) } }
                issue.agentName?.let { put("agentName", it) }
                put("createdAt", issue.createdAt)
                put("updatedAt", issue.updatedAt)
                put("canEdit", canEdit)
                put("canComment", canComment)
                // Omitted entirely when nobody watches, as the empty sets above are —
                // an absent key reads the same and saves a line of empty array.
                if (watchers.isNotEmpty()) {
                    putJsonArray("watchers") { watchers.forEach { add(it) } }
                }
                // The epic this belongs under, absent when it stands alone — a
                // missing key, like every other optional reference here (LNL-55).
                parentRecord?.let { parent ->
                    putJsonObject("parent") {
                        put("id", parent.id)
                        put("key", "${project.namePrefix}-${parent.number}")
                        put("title", parent.title)
                    }
                }
                // This issue's children, in their work order — present only when it
                // is an epic, so an ordinary ticket carries no `children` key.
                if (childRecords.isNotEmpty()) {
                    putJsonArray("children") {
                        childRecords.forEach { child ->
                            add(
                                buildJsonObject {
                                    put("id", child.id)
                                    put("key", "${project.namePrefix}-${child.number}")
                                    put("title", child.title)
                                },
                            )
                        }
                    }
                }
                putJsonArray("comments") {
                    comments.forEach { comment ->
                        add(
                            buildJsonObject {
                                put("id", comment.id)
                                put("body", comment.body)
                                put("author", comment.author.displayName(authors))
                                comment.agentName?.let { put("agentName", it) }
                                put("createdAt", comment.createdAt)
                            },
                        )
                    }
                }
                putJsonArray("history") {
                    events.forEach { event ->
                        add(
                            buildJsonObject {
                                put("id", event.id)
                                put("kind", event.kind.name)
                                // The stored snapshot, except for an assignee whose
                                // account still exists — where the live name wins.
                                // The same asymmetry the web history renders with,
                                // and for the same reason: renaming a column does
                                // not rewrite what happened on Tuesday, but renaming
                                // yourself does not make you somebody else. See
                                // buildIssueDetail.
                                (event.valueUserId?.let { authors[it] } ?: event.value)
                                    ?.let { put("value", it) }
                                if (event.values.isNotEmpty()) {
                                    putJsonArray("values") { event.values.forEach { add(it) } }
                                }
                                put("author", event.author.displayName(authors))
                                event.agentName?.let { put("agentName", it) }
                                put("createdAt", event.createdAt)
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

        // Orthogonal to attribution and not gated by it: an agent labelling its own
        // writes is the ordinary case, not the admin-only act of writing as someone
        // else. See [resolveAgentName].
        val agentName = resolveAgentName(arguments)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }

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

        // Resolved with the rest of the vocabulary, and deliberately before
        // createDraft: this is the last thing that can be refused, and a refusal
        // must cost nothing. Naming an unassignable person after the draft row
        // existed would leave the invisible orphan the catch below exists to
        // prevent. Absent means nobody, as an issue filed in the web app gets.
        // current = null: a created issue that says nothing about a sprint lands in
        // the backlog, which is where every issue in a project with no sprints is
        // anyway. That is what lets an agent working a kanban board stay entirely
        // unaware this argument exists.
        val sprintId = resolveSprint(project.id, arguments, current = null)
            .getOrElse { return refuse(it.message ?: "That sprint cannot be used.") }

        val plannedVersionId = resolveVersion(project.id, arguments, PLANNED_VERSION_ARGUMENT, current = null)
            .getOrElse { return refuse(it.message ?: "That version cannot be used.") }
        val fixedVersionId = resolveVersion(project.id, arguments, FIXED_VERSION_ARGUMENT, current = null)
            .getOrElse { return refuse(it.message ?: "That version cannot be used.") }
        // The same fix-version rule the web close obeys: closing as done can demand
        // a fixed version, and an agent must not slip past it.
        checkFixedVersionRequirementForMcp(project.id, resolutionId, fixedVersionId)
            .getOrElse { return refuse(it.message ?: "A fixed version is required.") }

        val assigneeId = resolveAssignee(project.id, arguments, current = null)
            .getOrElse { return refuse(it.message ?: "That assignee cannot be used.") }

        // Parsed before createDraft with the rest, so garbage costs nothing — but
        // APPLIED after the issue exists, since attaching needs the new issue's id.
        // Its own rules (same project, one level, no cycle) are checked when it is
        // applied, inside the try below, so a refused parent unwinds the whole create.
        val parent = parentIntent(arguments)
            .getOrElse { return refuse(it.message ?: "That parent cannot be used.") }

        val (issueId, number) = deps.issueRepository.createDraft(
            project.id,
            attribution.author,
            attribution.at,
            agentName,
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
                assigneeId = assigneeId,
                sprintId = sprintId,
                // Full access, the same the web editor has (LNL-134): the agent may
                // set both versions by name, or leave them null.
                plannedVersionId = plannedVersionId,
                fixedVersionId = fixedVersionId,
                labelIds = labelIds,
                componentIds = componentIds,
                // The same value the draft was created with, never a second read of
                // the clock: publishing stamps updated_at unconditionally, so
                // omitting it here would drag a backfilled issue's "last touched" to
                // today and straddle the two columns Issues.sq requires to agree.
                updatedAt = attribution.at,
                // The acting user, excluded from their own new-issue notification —
                // even when they attribute the write to an imported external author.
                actorId = user.id,
                // The attributed author rather than the acting user, which differs
                // only when an admin is backfilling — and there the whole point is
                // that the imported issue was filed by somebody else. See
                // IssueRepository.save's `actor`.
                actor = attribution.author,
                agentName = agentName,
            )
            // Attaching under an epic, if one was named — the last step of the create,
            // and inside the try so a refused parent (wrong project, already a child,
            // a cycle) deletes the issue rather than leaving it orphaned under no epic
            // it half-asked for. Detach/Leave never happen on a create: a new issue
            // has no parent to remove.
            if (parent is ParentIntent.Attach) {
                deps.issueRepository.setParent(issue, parent.parentId).getOrElse { failure ->
                    deps.issueRepository.delete(issue)
                    return refuse(failure.message ?: "That parent cannot be used.")
                }
            }
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

        // author / author_external / created_at are the admin-only levers, defaulted
        // to the issue's current values so an ordinary edit leaves attribution exactly
        // as it was. The same gate and the same function as create and update_comment —
        // this is where "a system administrator may change everything" lands for an issue.
        val attribution = resolveAttribution(
            user,
            arguments,
            default = Attribution(issue.author, issue.createdAt),
            removalOutcome = "the issue keeps its current author and date",
        ).getOrElse { return refuse(it.message ?: "That attribution cannot be used.") }
        val createdAt = attribution.at ?: issue.createdAt

        // updated_at is the issue's second stamp, admin-only like the rest but kept its
        // own check because it is validated against the RESULTING created_at, not the
        // old one: a system administrator may move both in a single call, and "edited before it
        // existed" has to be judged on where created_at ends up.
        val updatedAt = if (arguments.isPresent(UPDATED_AT_ARGUMENT)) {
            if (!deps.access.canAttributeWrites(user)) {
                return refuse(
                    "Only a system administrator can set $UPDATED_AT_ARGUMENT, and you are not acting as one. " +
                        "Nothing was written. Remove $UPDATED_AT_ARGUMENT and the edit will be " +
                        "stamped now — but that is a different thing from what you asked for, so " +
                        "decide rather than assume.",
                )
            }
            resolveTimestamp(arguments, UPDATED_AT_ARGUMENT)
                .getOrElse { return refuse(it.message ?: "That timestamp cannot be used.") }
                // The straddle insertDraft refuses to create by hand, refused here
                // too: created_at and updated_at are one value at birth, and the
                // only way back to disagreement is an edit claiming to predate the
                // row it edits. Issues.sq's `updated_at` comment forbids it.
                .also { at ->
                    if (at < createdAt) {
                        return refuse(
                            "`$UPDATED_AT_ARGUMENT` ($at) is before this issue's created_at " +
                                "($createdAt), so it would claim to have been edited before it " +
                                "existed. Nothing was written.",
                        )
                    }
                }
        } else if (arguments.isPresent(CREATED_AT_ARGUMENT)) {
            // created_at was moved but updated_at was not given. Keep them together, as
            // a create does — otherwise publish would stamp "last touched" to now and
            // an issue backdated to 2019 would still claim it was edited today, and a
            // within-skew future created_at could even land ahead of that now.
            createdAt
        } else {
            null
        }

        // Absent leaves the badge untouched; a real name sets it, neither admin-gated
        // — self-labelling is the norm. An empty value clears it, which is admin-only.
        // See resolveAgentNameEdit.
        val agentName = resolveAgentNameEdit(user, arguments, issue.agentName)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }

        // ── What the HISTORY says about this edit, which is not what the issue says
        //
        // Two values above are defaulted to the issue's own — `attribution.author`
        // to its author, `agentName` to its badge — and both defaults are right for
        // the row: an edit that says nothing about attribution must leave the
        // author column and the badge exactly as they were, which is what
        // editAttribution below writes. Passing those same two on to the history
        // was LNL-180: every edit was filed under whoever had OPENED the ticket,
        // wearing whatever badge the ticket wore, so an agent moving somebody
        // else's issue recorded — permanently, plausibly, and silently — that its
        // author had done it. A history that names the wrong person is worse than
        // no history, and it is the one record documented as answering "by whom".
        //
        // So the event gets what actually happened in THIS call: the caller, and
        // the badge this call carried. Exactly what move_issue has always recorded.
        // The one deliberate exception is an admin naming an `author` — that is the
        // backfill the parameter exists for, where the imported edit really does
        // belong to somebody else. A lone `created_at` re-authors nothing and so
        // does not count.
        val reattributed = arguments.isPresent(AUTHOR_ARGUMENT) || arguments.isPresent(AUTHOR_EXTERNAL_ARGUMENT)
        val eventAuthor = if (reattributed) attribution.author else user.asAuthor()
        // From this call alone, never the issue's standing badge — per-event is the
        // whole point of IssueEvents.sq's `agent_name`, and inheriting the issue's
        // would mark a human's later edit of an agent-filed issue as the agent's.
        // Already length-checked by resolveAgentNameEdit above; re-read rather than
        // re-derived because "" means "clear the badge" there and "no badge" here.
        val eventAgentName = resolveAgentName(arguments)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }

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

        // Defaulted to the issue's own value, so an edit that says nothing about the
        // assignee leaves it exactly as it was. Written explicitly rather than left
        // to a parameter default, because `publish` overwrites the column
        // unconditionally — a value that could be forgotten here would silently
        // unassign somebody every time an agent fixed a typo. See
        // IssueRepository.save.
        // Defaulted to the issue's own value for exactly the assignee's reason
        // below: `publish` overwrites the column unconditionally, so a value that
        // could be forgotten here would silently un-schedule work every time an
        // agent fixed a typo.
        val sprintId = resolveSprint(issue.projectId, arguments, current = issue.sprintId)
            .getOrElse { return refuse(it.message ?: "That sprint cannot be used.") }

        // Current-as-default, never null-by-omission: an edit that says nothing about
        // a version keeps the one the issue holds, so fixing a typo does not un-version
        // it — exactly the sprintId hazard flagged above. Sending null clears it.
        val plannedVersionId = resolveVersion(issue.projectId, arguments, PLANNED_VERSION_ARGUMENT, issue.plannedVersionId)
            .getOrElse { return refuse(it.message ?: "That version cannot be used.") }
        val fixedVersionId = resolveVersion(issue.projectId, arguments, FIXED_VERSION_ARGUMENT, issue.fixedVersionId)
            .getOrElse { return refuse(it.message ?: "That version cannot be used.") }
        // The resulting resolution and the resulting fixed version, checked together
        // against the project's rule — the same one the web save obeys.
        checkFixedVersionRequirementForMcp(issue.projectId, resolutionId, fixedVersionId)
            .getOrElse { return refuse(it.message ?: "A fixed version is required.") }

        val assigneeId = resolveAssignee(issue.projectId, arguments, current = issue.assigneeId)
            .getOrElse { return refuse(it.message ?: "That assignee cannot be used.") }

        // The parent, applied BEFORE the content save, so a refused reparent (wrong
        // project, already a child, a cycle) leaves the issue untouched rather than
        // half-edited. setParent does not write any column publish() below writes, so
        // the two do not fight; a leave is a no-op, a null detaches. See parentIntent.
        val parent = parentIntent(arguments)
            .getOrElse { return refuse(it.message ?: "That parent cannot be used.") }
        when (parent) {
            ParentIntent.Leave -> {}
            ParentIntent.Detach -> deps.issueRepository.setParent(issue, null)
            is ParentIntent.Attach -> deps.issueRepository.setParent(issue, parent.parentId)
                .getOrElse { return refuse(it.message ?: "That parent cannot be used.") }
        }

        deps.issueRepository.save(
            issue = issue,
            title = title,
            description = arguments.string("description") ?: issue.description,
            statusId = statusId,
            priorityId = priorityId,
            resolutionId = resolutionId,
            assigneeId = assigneeId,
            sprintId = sprintId,
            // Full access, the same the web editor has (LNL-134): set either version
            // by name, null to clear, or omit to keep — resolveVersion above.
            plannedVersionId = plannedVersionId,
            fixedVersionId = fixedVersionId,
            labelIds = labelIds,
            componentIds = componentIds,
            updatedAt = updatedAt,
            actorId = user.id,
            // The history's two fields, resolved above and deliberately NOT the
            // issue's own author and badge — see LNL-180 there.
            actor = eventAuthor,
            agentName = eventAgentName,
        )
        // The columns publish() does not reach — author, creation date, agent label.
        // Written after the content save, gated above: admin-only for the author and
        // date, open for the label, and idempotent for an ordinary edit that moved
        // none of the three.
        deps.issues.editAttribution(issue.id, createdAt, attribution.author, agentName)
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

        // The fixed version, and the same fix-version rule the drag route enforces
        // (LNL-134): closing as done can demand one. current = the issue's, so a move
        // that says nothing about a version keeps it.
        val fixedVersionId = resolveVersion(issue.projectId, arguments, FIXED_VERSION_ARGUMENT, issue.fixedVersionId)
            .getOrElse { return refuse(it.message ?: "That version cannot be used.") }
        checkFixedVersionRequirementForMcp(issue.projectId, resolutionId, fixedVersionId)
            .getOrElse { return refuse(it.message ?: "A fixed version is required.") }

        deps.issues.setStatus(issue.id, status.id, resolutionId)
        // Written on a close, alongside the move, exactly as the drag route does —
        // an ordinary move between open columns (resolutionId null) leaves the fixed
        // version alone.
        if (resolutionId != null) {
            deps.issues.setFixedVersion(issue.id, fixedVersionId)
        }
        // A move is an update to the issue; fired here rather than in the store, as
        // in the drag route. See BoardDependencies.notifications.
        deps.notifications.issueUpdated(issue, user.id, "moved")
        // `issue` is the pre-write record, so a move to the column the issue is
        // already in records nothing. Agents re-assert state they believe to be
        // true far more often than a person drags a card, so that guard earns its
        // place more here than on the drag route. See IssueHistory.
        deps.history?.recordStatusChanged(issue, status.id, user.asAuthor(), resolveAgentName(arguments).getOrNull())
        return ok("Moved issue ${issue.id} to ${status.name}.")
    }

    /**
     * Watch an issue, or stop — for yourself, or (as an admin) for somebody else.
     *
     * ── Why the gate is "readable", not "editable" ────────────────────────────
     *
     * This is not a write to the issue; it is the caller managing an inbox. The web
     * route this mirrors (`POST /api/issues/{id}/notification`) asks only that the
     * caller be signed in and the issue readable, on the reasoning that anyone who
     * may *see* an issue may ask to hear about it. So there is no `canEditIssue`
     * here — a watcher who cannot edit is the ordinary case, not an anomaly.
     *
     * ── The two subjects, and why one of them is admin-only ───────────────────
     *
     * Absent `user` means the caller's own watch, which is the whole of the web
     * feature and needs no more right than reading. Naming somebody else is a
     * different act — a decision about *their* inbox — and admin-only for
     * [resolveAttribution]'s reason: acting as or for another person is exactly
     * what [AccessControl.canAttributeWrites] guards. The parallel is `assignee`
     * naming somebody else on an edit, and the refusal is by name rather than
     * silent, as everywhere on this surface.
     *
     * Watching needs an address to reach; unwatching does not. A subscribe from an
     * account with no e-mail is refused — it would notify nobody — naming the
     * subject when it is not the caller, exactly as the web route refuses the
     * address-less caller.
     */
    private suspend fun watchIssue(user: UserRecord, arguments: JsonObject): McpToolResult {
        val issue = readableIssue(user, arguments) ?: return noSuchIssue()
        // Default true: an agent that says "watch" plainly means to start; false stops.
        val watching = arguments.bool(WATCHING_ARGUMENT) ?: true

        // Whose inbox. Absent → the caller's own. Naming somebody else resolves to
        // an account — refusing unknown and ambiguous, as resolveAuthor does — and
        // is admin-only when it is not the caller.
        val subject: UserRecord = if (arguments.isPresent(USER_ARGUMENT)) {
            val named = arguments.string(USER_ARGUMENT)
                ?: return refuse("`$USER_ARGUMENT` was given as an empty name.")
            val id = resolveAuthor(named).getOrElse { return refuse(it.message ?: "No such person.") }
            if (id != user.id && !deps.access.canAttributeWrites(user)) {
                return refuse(
                    "Only a system administrator can change somebody else's watch, and you are not " +
                        "acting as one. Omit `$USER_ARGUMENT` to change your own. Nothing was written.",
                )
            }
            // Resolved to a live record for its address and name below. It exists —
            // resolveAuthor just matched it — but the store call is nullable.
            deps.users.findById(id) ?: return refuse("No such person.")
        } else {
            user
        }

        if (watching && subject.email == null) {
            val who = if (subject.id == user.id) "You have" else "${subject.resolvedName} has"
            return refuse(
                "$who no e-mail address on the account, so watching would notify nobody. Add one " +
                    "first. Nothing was written.",
            )
        }

        deps.subscriptions.setIssueUpdateSubscription(subject.id, issue.id, watching)
        val who = if (subject.id == user.id) "You are" else "${subject.resolvedName} is"
        return ok(
            if (watching) "$who now watching issue ${issue.id}."
            else "$who no longer watching issue ${issue.id}.",
        )
    }

    /**
     * Delete one stored attachment — its row and its bytes — by the id in its URL.
     *
     * System-administrator only (see [toolsFor] and [AccessControl.canDeleteAttachment]):
     * the web app gives nobody a standalone attachment delete, so this is a new
     * destructive power rather than a mirror of an existing web right, and it is
     * offered only to the account trusted with the rest of the instance-wide ones.
     *
     * [AttachmentRepository.delete] takes the row first and the file second, so the
     * failure this can leave is a collectable orphaned file, never a row pointing at
     * nothing — see its doc.
     */
    private suspend fun deleteAttachment(user: UserRecord, arguments: JsonObject): McpToolResult {
        if (!deps.access.canDeleteAttachment(user)) {
            return refuse(
                "Deleting a stored attachment is a system-administrator action, and you are not " +
                    "acting as one. Nothing was deleted.",
            )
        }
        val raw = arguments.string("attachment")
            ?: return refuse("`attachment` must be the id from an attachment URL.")
        val publicId = attachmentPublicId(raw)
            ?: return refuse("That is neither an attachment id nor an `/api/attachments/<id>/view` URL.")
        val record = deps.attachments.findByPublicId(publicId)
            ?: return refuse(
                "No attachment has the id \"$publicId\" — it may have been deleted already (its row " +
                    "goes when the issue, comment or post it hung on does), or the id is wrong.",
            )
        val where = attachmentTargetDescription(record)
        deps.attachmentRepository.delete(record)
        return ok(
            "Deleted attachment \"${record.filename}\" (${formatByteSize(record.byteSize)}), which was " +
                "attached to $where. The bytes are gone and there is no undo.",
        )
    }

    /**
     * The public id inside whatever the caller passed for `attachment`.
     *
     * Accepts the bare id, a `/api/attachments/<id>/view` URL, or the tail of one.
     * Public ids are base64url and never contain `/`, so cutting at the first slash
     * strips a trailing `/view` (and anything after the id) without ever splitting a
     * real id.
     */
    private fun attachmentPublicId(raw: String): String? {
        val trimmed = raw.trim()
        val marker = "/attachments/"
        val afterMarker = if (marker in trimmed) trimmed.substringAfter(marker) else trimmed
        return afterMarker.substringBefore("/").trim().ifBlank { null }
    }

    /** Which issue, comment, post or message an attachment hangs on, for the answer. */
    private fun attachmentTargetDescription(record: AttachmentRecord): String = when {
        record.issueId != null -> "issue ${record.issueId}'s description"
        record.commentId != null -> "comment ${record.commentId}"
        record.forumPostId != null -> "forum post ${record.forumPostId}"
        record.forumCommentId != null -> "forum comment ${record.forumCommentId}"
        record.messageId != null -> "a private message"
        else -> "nothing — it was already orphaned"
    }

    // ── Sprints: the lifecycle a vocabulary has no verb for (LNL-35) ───────────
    //
    // Gated on the same right the web routes ask — see SprintRoutes and the
    // clarification on LNL-35: an agent gets its user's own sprint powers, no more
    // and no less. Since LNL-191 that right is a maintainer's rather than an
    // administrator's. Per-project, so these are shown to everyone (like
    // create_issue) and refused per project rather than hidden.

    /**
     * The project for a sprint-lifecycle write, or a refusal.
     *
     * Readable first and administrable second, exactly as [adminSprintScope]: an id
     * the caller cannot see answers "no such project" rather than confirming it
     * exists by refusing on rights.
     */
    private suspend fun sprintAdminProject(user: UserRecord, arguments: JsonObject): Result<ProjectRecord> {
        val project = resolveProject(user, arguments)
            ?: return Result.failure(ResolutionRefusal("No such project."))
        if (!deps.access.canEditVocabulary(user, project.id, VocabularyKind.SPRINT)) {
            return Result.failure(
                ResolutionRefusal(
                    "You cannot configure sprints in ${project.name} — that is a project-maintainer " +
                        "action, and you do not maintain this project. Nothing was changed.",
                ),
            )
        }
        return Result.success(project)
    }

    /** A sprint named within a project, or a refusal that lists the ones there are. */
    private suspend fun namedSprint(projectId: Long, name: String): Result<SprintRecord> {
        val sprints = deps.sprints.forProject(projectId)
        val match = sprints.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: return Result.failure(
                ResolutionRefusal(
                    if (sprints.isEmpty()) {
                        "This project has no sprints yet; create one with create_sprint."
                    } else {
                        "\"$name\" is not a sprint in this project. There is: " +
                            sprints.joinToString(", ") { it.name } + "."
                    },
                ),
            )
        return Result.success(match)
    }

    private suspend fun createSprint(user: UserRecord, arguments: JsonObject): McpToolResult {
        val project = sprintAdminProject(user, arguments)
            .getOrElse { return refuse(it.message ?: "No such project.") }
        val name = arguments.string("name") ?: return refuse("A sprint needs a name.")
        val row = try {
            deps.vocabularies.add(project.id, VocabularyKind.SPRINT, name)
        } catch (conflict: VocabularyConflict) {
            return refuse(conflict.userMessage)
        }
        return ok(
            "Created sprint \"${row.name}\" (sprint id ${row.id}) in ${project.name}. It is not active " +
                "— start it with set_active_sprint.",
        )
    }

    private suspend fun setActiveSprint(user: UserRecord, arguments: JsonObject): McpToolResult {
        val project = sprintAdminProject(user, arguments)
            .getOrElse { return refuse(it.message ?: "No such project.") }
        // Present-and-null clears; a name activates. `sprint` is required in the
        // schema, so its absence is a malformed call, not "leave it alone" — this
        // tool does exactly one thing and there is nothing to leave alone.
        if (!arguments.isPresent(SPRINT_ARGUMENT)) {
            return refuse("`sprint` is required: a sprint name to activate, or null to clear the active sprint.")
        }
        val named = arguments.string(SPRINT_ARGUMENT)
        if (named == null) {
            deps.sprintRepository.activate(project.id, null)
            return ok("${project.name} now has no active sprint.")
        }
        val sprint = namedSprint(project.id, named).getOrElse { return refuse(it.message ?: "No such sprint.") }
        try {
            deps.sprintRepository.activate(project.id, sprint.id)
        } catch (refusal: SprintRefusal) {
            return refuse(refusal.userMessage)
        }
        return ok("Activated sprint \"${sprint.name}\" in ${project.name}.")
    }

    private suspend fun completeSprint(user: UserRecord, arguments: JsonObject): McpToolResult {
        val project = sprintAdminProject(user, arguments)
            .getOrElse { return refuse(it.message ?: "No such project.") }
        val named = arguments.string(SPRINT_ARGUMENT)
            ?: return refuse("`sprint` must name the sprint to complete.")
        val sprint = namedSprint(project.id, named).getOrElse { return refuse(it.message ?: "No such sprint.") }
        // Absent or present-and-null is the backlog; a name is another sprint.
        // Optional in the schema because "to the backlog" is a real, common answer
        // — unlike `sprint`, which has nothing sensible to default to.
        val moveTo: Long? = if (arguments.isPresent(MOVE_UNFINISHED_ARGUMENT)) {
            arguments.string(MOVE_UNFINISHED_ARGUMENT)?.let { toName ->
                namedSprint(project.id, toName).getOrElse { return refuse(it.message ?: "No such sprint.") }.id
            }
        } else {
            null
        }
        try {
            deps.sprintRepository.complete(project.id, sprint.id, moveTo)
        } catch (refusal: SprintRefusal) {
            return refuse(refusal.userMessage)
        }
        val destination = moveTo?.let { id ->
            deps.sprints.forProject(project.id).firstOrNull { it.id == id }?.name
        }
        return ok(
            "Completed sprint \"${sprint.name}\" in ${project.name}. " +
                if (destination != null) {
                    "Unfinished work moved to \"$destination\"."
                } else {
                    "Unfinished work went to the backlog."
                },
        )
    }

    /** Post a comment. One call, both steps — see [createIssue]. */
    private suspend fun addComment(user: UserRecord, arguments: JsonObject): McpToolResult {
        val issue = readableIssue(user, arguments) ?: return noSuchIssue()
        if (!deps.access.canComment(user, issue.projectId)) {
            return refuse("You cannot comment on this project's issues.")
        }
        val attribution = resolveAttribution(user, arguments)
            .getOrElse { return refuse(it.message ?: "That attribution cannot be used.") }
        val agentName = resolveAgentName(arguments)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }
        val body = arguments.string("body") ?: return refuse("A comment needs something in it.")

        val commentId = deps.issueRepository.createCommentDraft(
            issue.id,
            attribution.author,
            attribution.at,
            agentName,
        )
        try {
            deps.issueRepository.saveComment(commentId, body, actorId = user.id)
        } catch (failure: Exception) {
            deps.comments.findById(commentId)?.let { deps.issueRepository.deleteComment(it) }
            throw failure
        }
        return ok("Commented on issue ${issue.id}.")
    }

    /**
     * Edit a comment.
     *
     * The comment counterpart to [updateIssue], and the same two-part permission
     * the web app's `PUT /api/comments/{id}` uses: [AccessControl.canEditComment]
     * decides whether you may touch this comment at all — your own, or anyone's if
     * you are an admin — and only then does the admin-only backfill gate decide
     * whether you may also re-attribute or re-date it.
     *
     * ── Why this rewrites author and timestamp at all ────────────────────────
     *
     * The rest of this file holds that backfill is creation-only (see the preamble).
     * `update_comment` is the deliberate exception: a system administrator may change everything
     * about a comment, an external author included, so an import that got a name or
     * a date wrong can be corrected in place rather than deleted and refiled — and
     * deletion is the one thing this surface does not offer. It is admin-only for
     * exactly the reason create-time attribution is, gated by the same
     * [AccessControl.canAttributeWrites] through [resolveAttribution].
     */
    private suspend fun updateComment(user: UserRecord, arguments: JsonObject): McpToolResult {
        val comment = readableComment(user, arguments) ?: return noSuchComment()
        if (!deps.access.canEditComment(user, comment)) {
            return refuse("That is not your comment.")
        }

        // author / author_external / created_at are the admin-only backfill levers,
        // exactly as on add_comment — but defaulted to what the comment already has,
        // because an edit that does not mention them must not silently re-author or
        // re-date the row. resolveAttribution carries the whole gate; passing the
        // current values as its default is the only difference from a create.
        val attribution = resolveAttribution(
            user,
            arguments,
            default = Attribution(comment.author, comment.createdAt),
            removalOutcome = "the comment keeps its current author and date",
        ).getOrElse { return refuse(it.message ?: "That attribution cannot be used.") }

        // Absent leaves the badge untouched and a real name sets it, neither
        // admin-gated — self-labelling is the norm, see resolveAttribution's
        // preamble. An empty value clears it, which is admin-only. See
        // resolveAgentNameEdit.
        val agentName = resolveAgentNameEdit(user, arguments, comment.agentName)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }

        // Blank or absent keeps the current body, as update_issue does with a title:
        // an edit is not how a comment gets emptied.
        val body = arguments.string("body") ?: comment.body

        deps.comments.edit(
            id = comment.id,
            body = body,
            createdAt = attribution.at ?: comment.createdAt,
            author = attribution.author,
            agentName = agentName,
        )
        return ok("Updated comment ${comment.id}.")
    }

    /**
     * Correct the author, date or agent label of one history entry.
     *
     * The events' analogue of [updateComment], and the append-only history table's
     * one deliberate hole — see IssueEvents.sq's preamble. Where [updateComment] has
     * a two-part gate (may you touch this row, then may you re-attribute it), this
     * has only the second half, widened into the whole: there is no such thing as
     * editing your own history entry, because nobody writes history by hand. The
     * only reason to reach a row here is to fix attribution on imported history,
     * which is an administrative act start to finish.
     *
     * So the admin gate IS the gate, and it is asked first — before the row is even
     * resolved — so that a non-admin who somehow reached this tool (it is absent
     * from their tool list) cannot use an `event_id` to probe which entries, and so
     * which private boards, exist. The readable-project chain below is then
     * belt-and-braces, since an admin can read every board anyway.
     *
     * WHAT the entry records is never touched: `kind`, its `value` and its `values`
     * are not parameters, here or in the store. Only who, when, and the agent label
     * move — the same three levers [resolveAttribution] and [resolveAgentName]
     * carry everywhere else, gated the same way.
     */
    private suspend fun updateHistoryEvent(user: UserRecord, arguments: JsonObject): McpToolResult {
        if (!deps.access.canAttributeWrites(user)) {
            return refuse(
                "Only a system administrator can edit an issue's history, and you are not acting " +
                    "as one. A history is append-only for everyone else — its entries record what " +
                    "happened and are not yours to rewrite. Nothing was written.",
            )
        }
        // Null only in test deployments that wire up no history; a production
        // server always has one. An admin who reached here on such a deployment is
        // told plainly rather than met with "no such event", which would be a lie
        // about the id they sent.
        val history = deps.history ?: return refuse("This deployment keeps no issue history to edit.")
        val eventId = arguments.long(EVENT_ID_ARGUMENT)
            ?: return refuse("Which history entry? Give its `$EVENT_ID_ARGUMENT` — the `id` from get_issue's history.")
        val event = history.findEvent(eventId) ?: return noSuchHistoryEvent()
        // The same readable-project chain readableComment walks, and the same
        // conflation noSuchHistoryEvent makes: an entry on a board you cannot read
        // is indistinguishable from one that does not exist.
        val issue = deps.issues.findById(event.issueId) ?: return noSuchHistoryEvent()
        val project = deps.projects.findById(issue.projectId) ?: return noSuchHistoryEvent()
        if (!deps.access.canReadProject(user, project)) return noSuchHistoryEvent()

        // author / author_external / created_at through the same gate and the same
        // function as every other backfill, defaulted to what the entry already
        // records so a call that names only one of them leaves the rest exactly as
        // they were. The admin gate above has already passed, so resolveAttribution's
        // own check is a no-op here — but it is the check, and going around it would
        // be the one copy that could drift.
        val attribution = resolveAttribution(
            user,
            arguments,
            default = Attribution(event.author, event.createdAt),
            removalOutcome = "the entry keeps its current author and date",
        ).getOrElse { return refuse(it.message ?: "That attribution cannot be used.") }

        // Absent leaves the badge as it is; a real name sets it; an empty value
        // clears it. Clearing is admin-only — but this whole tool already is, so
        // here it is simply available: a migrated entry that was never an agent's
        // can shed the badge in the same call that reattaches it. See
        // resolveAgentNameEdit.
        val agentName = resolveAgentNameEdit(user, arguments, event.agentName)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }

        history.reattribute(
            id = event.id,
            author = attribution.author,
            createdAt = attribution.at ?: event.createdAt,
            agentName = agentName,
        )
        return ok("Updated history event ${event.id}.")
    }

    // ── Deleting ─────────────────────────────────────────────────────────────

    /**
     * Delete an issue, its comments, its attachments and its history.
     *
     * The same two gates the HTTP route has, in the same order and asking the
     * same functions: readable first, so a private project cannot be probed by
     * watching which refusal comes back (see [noSuchIssue]), and then
     * [AccessControl.canDeleteIssue]. An agent gets exactly what the person
     * driving it would get through the web app, which is the rule for this whole
     * surface — deleting is not the place to start inventing a second one.
     *
     * Note what is deliberately NOT here: no admin-only gate, and no "are you
     * sure" argument. The first because deletion is not an administrative act —
     * `canDeleteIssue` is `canEditIssue`, on the reasoning AccessControl gives:
     * anyone who can open the editor can already empty the issue of everything it
     * said, so guarding the row and not its contents is ceremony. The second
     * because a confirmation flag a caller sets itself confirms nothing; the
     * asking belongs in the tool description, where the agent reads it, and that
     * is where it is.
     *
     * The cascade is the repository's, not this function's — the comments and the
     * attachment ROWS go by foreign key, and the FILES need someone to unlink
     * them. See [IssueRepository.delete], which is also what the route calls.
     */
    private suspend fun deleteIssue(user: UserRecord, arguments: JsonObject): McpToolResult {
        val issue = readableIssue(user, arguments) ?: return noSuchIssue()
        if (!deps.access.canDeleteIssue(user, issue)) {
            return refuse("You cannot delete this issue.")
        }
        // Read before the row is gone: the answer names the ticket rather than
        // the id, because "Deleted issue 412" is not something anybody can check
        // afterwards — the row it refers to no longer exists.
        val project = deps.projects.findById(issue.projectId)
        val ticket = project?.let { "${it.namePrefix}-${issue.number}" } ?: "issue ${issue.id}"
        val commentCount = deps.comments.forIssue(issue.id).size

        deps.issueRepository.delete(issue)
        return ok(
            buildString {
                append("Deleted $ticket")
                if (commentCount > 0) {
                    append(" and its $commentCount comment${if (commentCount == 1) "" else "s"}")
                }
                append(". This cannot be undone.")
            },
        )
    }

    /**
     * Delete a comment and any files attached to it.
     *
     * [AccessControl.canEditComment] rather than a rule of its own, which is what
     * the HTTP route's `editableComment` uses for exactly this — and it is
     * NARROWER than [deleteIssue]'s gate on purpose. `change_unowned_issues` is a
     * grant over issues; it has never meant "and you may also delete what other
     * people wrote", and reading it that way here would quietly widen a role that
     * every project has handed out already.
     */
    private suspend fun deleteComment(user: UserRecord, arguments: JsonObject): McpToolResult {
        val comment = readableComment(user, arguments) ?: return noSuchComment()
        if (!deps.access.canEditComment(user, comment)) {
            return refuse("That is not your comment.")
        }
        deps.issueRepository.deleteComment(comment)
        return ok("Deleted comment ${comment.id}. This cannot be undone.")
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
        // Four owners since LNL-78, and the arithmetic below replaces the
        // `wantsIssue == wantsComment` test that read so neatly with two. Same
        // rule: exactly one, because the CHECK in Attachments.sq means a row with
        // two owners is not a thing that could be written even if this let it
        // through, and a row with none has nowhere to go.
        val named = ATTACHMENT_TARGET_ARGUMENTS.filter { arguments.isPresent(it) }
        if (named.size != 1) {
            return refuse(
                "An attachment belongs to exactly one thing, and you named ${named.size}. Give " +
                    "`issue_id` to put it in an issue's description, `comment_id` for a comment, " +
                    "`forum_post_id` for a forum post's body, or `forum_comment_id` for a comment " +
                    "on one — exactly one of the four.",
            )
        }

        val filename = arguments.string("filename")
            ?: return refuse("What should the file be called? `filename` is required.")

        val target = when (named.single()) {
            "issue_id" -> {
                val issue = readableIssue(user, arguments) ?: return noSuchIssue()
                if (!deps.access.canEditIssue(user, issue)) {
                    return refuse("You cannot attach files to this issue.")
                }
                AttachmentTarget.Issue(issue.id)
            }

            "comment_id" -> {
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

            // The two forum targets are gated by canUseForumTools rather than by
            // canEditForumContent, and that is not this tool going soft. The
            // forum surface is admin-only as a whole — see canUseForumTools — so
            // the caller who reaches these two branches is the caller who could
            // rewrite the post's body outright with update_forum_post. A
            // narrower check here would refuse nobody and would imply the forum
            // tools have a permission model they do not.
            "forum_post_id" -> {
                val scope = forumPostScope(user, arguments, "forum_post_id")
                    .getOrElse { return refuse(it.message ?: "No such post.") }
                AttachmentTarget.ForumPost(scope.post.id)
            }

            else -> {
                val scope = forumCommentScope(user, arguments, "forum_comment_id")
                    .getOrElse { return refuse(it.message ?: "No such comment.") }
                AttachmentTarget.ForumComment(scope.comment.id)
            }
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

    // ── Mailing ──────────────────────────────────────────────────────────────

    /**
     * Send the token's own user an e-mail from the agent.
     *
     * ── The recipient is not an argument, and that is the design ─────────────
     *
     * [UserRecord.email] is read straight off the user the token resolved to.
     * There is no address parameter to validate, so there is no validation to get
     * wrong and nothing an injected instruction can aim at: an agent talked into
     * mailing somebody else has no parameter with which to try. See AgentMail.
     *
     * ── The order of the refusals ────────────────────────────────────────────
     *
     * Configuration first, then the address, then the arguments — cheapest fix
     * last. All three are ordinary states rather than faults, and each says what
     * would make it work, because the agent's next move differs completely
     * between "this server cannot send mail at all" (stop asking, tell the
     * person) and "your subject is too long" (shorten it and retry).
     *
     * A send that Resend refuses is reported, not swallowed. That is the opposite
     * of [NotificationService.dispatch], and deliberately: a notification rides on
     * a write that already succeeded, while here the send IS the task. An agent
     * that reports "I have e-mailed you the summary" about a message the provider
     * rejected has told the person something untrue about the only thing it was
     * asked to do.
     */
    private suspend fun sendEmail(user: UserRecord, arguments: JsonObject): McpToolResult {
        // First, before anything about this deployment or this account is said.
        // A caller who may not send is not owed the news that mail is unconfigured
        // here, and the refusal has to be the same one whether it is or not.
        if (!deps.access.canSendAgentMail(user)) {
            return refuse(
                "Sending e-mail over MCP is restricted to system administrators on this Lunicle " +
                    "server, and this account is not one. This is not a missing setting the user " +
                    "can turn on themselves — tell them what you would have written instead, and " +
                    "do not try again.",
            )
        }
        val sender = deps.agentMail ?: return refuse(
            "This Lunicle server has no e-mail configured, so it cannot send anything. That is a " +
                "deployment setting and not something you or the user can fix from here — say so " +
                "rather than trying again.",
        )
        val address = user.email ?: return refuse(
            "There is no e-mail address on your Lunicle account, so there is nowhere to send " +
                "this — and this tool can only ever send to you. Ask the user to add an address " +
                "in Lunicle's web app, under their user settings.",
        )
        val subject = arguments.string("subject")
            ?: return refuse("An e-mail needs a subject. Give one short line saying what it is about.")
        if (subject.length > MAX_AGENT_MAIL_SUBJECT_LENGTH) {
            return refuse(
                "`subject` is too long — keep it under $MAX_AGENT_MAIL_SUBJECT_LENGTH characters. " +
                    "It is a subject line, and a mail client will cut it off long before that " +
                    "anyway. Put the detail in `body`.",
            )
        }
        val body = arguments.string("body")
            ?: return refuse("An e-mail needs a body. There is no point sending a bare subject.")
        if (body.length > MAX_AGENT_MAIL_BODY_LENGTH) {
            return refuse(
                "`body` is too long — keep it under $MAX_AGENT_MAIL_BODY_LENGTH characters. " +
                    "This is a note to a person, not a log file: summarise, and leave the detail " +
                    "somewhere they can go and read it.",
            )
        }
        val agentName = resolveAgentName(arguments)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }

        return try {
            sender.send(
                to = address,
                subject = agentMailSubject(subject),
                html = agentMailBody(recipientName = user.resolvedName, agentName = agentName, body = body),
            )
            ok("Sent to your own address, <$address>.")
        } catch (failure: EmailSendFailure) {
            // The provider's own words are logged by the EmailTransport and kept off this
            // surface, exactly as they are kept out of the UI.
            refuse(failure.message ?: "The e-mail could not be sent.")
        }
    }

    // ── Forums ───────────────────────────────────────────────────────────────

    /**
     * The gate every forum tool runs first, or null when the caller may proceed.
     *
     * Asked here as well as in [toolsFor] because `tools/call` never consults the
     * list a caller was offered — see [toolsFor]'s doc. An agent that names a tool
     * it was never shown is exactly the caller this surface assumes, and this is
     * the half that enforces.
     *
     * It runs before anything is resolved, so a refusal costs no query and tells a
     * non-admin nothing about which projects have forums.
     */
    private fun forumGate(user: UserRecord): McpToolResult? =
        if (deps.access.canUseForumTools(user)) null else refuse(FORUM_REFUSAL)

    /** A project this caller may read, and one of its forums. */
    private class ForumScope(val project: ProjectRecord, val forum: ForumRecord)

    /** ...and a published post in it. */
    private class ForumPostScope(
        val project: ProjectRecord,
        val forum: ForumRecord,
        val post: ForumPostRecord,
    )

    /** ...and a published comment on that post. */
    private class ForumCommentScope(val post: ForumPostScope, val comment: ForumCommentRecord)

    private suspend fun listForums(user: UserRecord, arguments: JsonObject): McpToolResult {
        forumGate(user)?.let { return it }
        val project = resolveProject(user, arguments) ?: return noSuchProject()
        val forums = deps.forums.forProject(project.id)
        // Whether the caller watches each forum, read before the JSON is built (a
        // suspend query cannot run inside buildJsonObject's lambda). Lets an agent
        // that just called watch_forum confirm the result. See getForumPost.
        val watching = forums.associate {
            it.id to deps.subscriptions.isSubscribedToForumNewPosts(user.id, it.id)
        }
        return ok(
            buildJsonObject {
                putJsonObject("project") {
                    put("id", project.id)
                    put("name", project.name)
                }
                putJsonArray("forums") {
                    forums.forEach { forum ->
                        add(
                            buildJsonObject {
                                put("id", forum.id)
                                put("name", forum.name)
                                // The caller's own watch, always present as a bool:
                                // it is a fact about this reader, not about the
                                // forum, so absent-when-false would read as "unknown"
                                // rather than "no". See watch_forum.
                                put("watching", watching[forum.id] == true)
                                // Absent rather than null when there is none, as
                                // `resolution` is on an issue: the store has
                                // already collapsed blank to null, so one spelling
                                // of "no description" reaches this far and it
                                // should reach the agent as one too.
                                forum.description?.let { put("description", it) }
                                put("position", forum.position)
                                put("createdAt", forum.createdAt)
                            },
                        )
                    }
                }
            },
        )
    }

    private suspend fun createForum(user: UserRecord, arguments: JsonObject): McpToolResult {
        forumGate(user)?.let { return it }
        val project = resolveProject(user, arguments) ?: return noSuchProject()
        val name = arguments.string("name") ?: return refuse("A forum needs a name.")
        val forum = refusable { deps.forums.create(project.id, name, arguments.string("description")) }
            .getOrElse { return refuse(it.message ?: "That forum could not be created.") }
        return ok("Created forum \"${forum.name}\" (forum id ${forum.id}) in ${project.name}.")
    }

    private suspend fun updateForum(user: UserRecord, arguments: JsonObject): McpToolResult {
        val scope = forumScope(user, arguments).getOrElse { return refuse(it.message ?: "No such forum.") }
        val name = arguments.string("name") ?: scope.forum.name
        // Absent means "leave it alone"; present-but-null (or blank) means
        // "remove it". The same three-way reading `assignee` takes on an issue,
        // and for the same reason: an agent that cannot express "none" cannot
        // undo its own mistake.
        val description = if (arguments.containsKey("description")) {
            arguments.string("description")
        } else {
            scope.forum.description
        }
        refusable { deps.forums.edit(scope.forum, name, description) }
            .getOrElse { return refuse(it.message ?: "That forum could not be changed.") }
        return ok("Updated forum ${scope.forum.id}.")
    }

    /**
     * Delete a forum and everything under it.
     *
     * The counts are read *before* the delete, for [deleteIssue]'s reason and with
     * more force: forums record no history, so after this call there is nothing
     * anywhere — not a row, not an event, not a name — that says what was in the
     * room. The sentence this returns is the only account of it that will ever
     * exist, so it had better say how much went.
     */
    private suspend fun deleteForum(user: UserRecord, arguments: JsonObject): McpToolResult {
        val scope = forumScope(user, arguments).getOrElse { return refuse(it.message ?: "No such forum.") }
        val listings = deps.forumPosts.listing(scope.forum.id)
        val posts = listings.size
        val comments = listings.sumOf { it.commentCount }

        deps.forums.delete(scope.forum)
        return ok(
            "Deleted forum \"${scope.forum.name}\" from ${scope.project.name}, along with " +
                "$posts ${plural(posts.toLong(), "post")} and $comments " +
                "${plural(comments, "comment")}. This cannot be undone, and nothing recorded what " +
                "was in it.",
        )
    }

    private suspend fun reorderForums(user: UserRecord, arguments: JsonObject): McpToolResult {
        forumGate(user)?.let { return it }
        val project = resolveProject(user, arguments) ?: return noSuchProject()
        val ids = arguments.longs("forum_ids")
            ?: return refuse("`forum_ids` must be an array of forum ids, as numbers.")
        refusable { deps.forums.reorder(project.id, ids) }
            .getOrElse { return refuse(it.message ?: "That order was refused.") }
        return ok("Reordered ${ids.size} ${plural(ids.size.toLong(), "forum")} in ${project.name}.")
    }

    private suspend fun listForumPosts(user: UserRecord, arguments: JsonObject): McpToolResult {
        val scope = forumScope(user, arguments).getOrElse { return refuse(it.message ?: "No such forum.") }
        val listings = deps.forumPosts.listing(scope.forum.id)
        // One lookup per distinct account across the whole page, both authors
        // folded in together — the poster and whoever last replied are very often
        // the same handful of people. The same call the Discussion pane makes.
        val names = deps.authorNames(listings.map { it.post.author } + listings.map { it.lastCommentAuthor })
        return ok(
            buildJsonObject {
                putJsonObject("forum") {
                    put("id", scope.forum.id)
                    put("name", scope.forum.name)
                    put("projectId", scope.project.id)
                }
                putJsonArray("posts") {
                    listings.forEach { listing ->
                        add(
                            buildJsonObject {
                                put("id", listing.post.id)
                                put("title", listing.post.title)
                                put("author", listing.post.author.displayName(names))
                                listing.post.agentName?.let { put("agentName", it) }
                                put("createdAt", listing.post.createdAt)
                                put("commentCount", listing.commentCount)
                                // Both absent when nobody has replied. Absent
                                // rather than null for the reason ForumPostListing
                                // gives: "nobody has answered this" and "the last
                                // answer was as old as the post" are different
                                // things, and a missing key says the first.
                                listing.lastCommentAt?.let { put("lastCommentAt", it) }
                                if (listing.lastCommentAt != null) {
                                    put("lastCommentAuthor", listing.lastCommentAuthor.displayName(names))
                                }
                            },
                        )
                    }
                }
            },
        )
    }

    private suspend fun getForumPost(user: UserRecord, arguments: JsonObject): McpToolResult {
        val scope = forumPostScope(user, arguments).getOrElse { return refuse(it.message ?: "No such post.") }
        val comments = deps.forumPosts.commentsOn(scope.post.id)
        val names = deps.authorNames(comments.map { it.author } + scope.post.author)
        // The caller's own watch, read before the JSON (no suspend query inside
        // buildJsonObject). Lets an agent confirm watch_forum_post. See listForums.
        val watching = deps.subscriptions.isSubscribedToForumPost(user.id, scope.post.id)
        return ok(
            buildJsonObject {
                put("id", scope.post.id)
                put("forumId", scope.forum.id)
                put("forumName", scope.forum.name)
                put("projectId", scope.project.id)
                put("title", scope.post.title)
                put("body", scope.post.body)
                put("author", scope.post.author.displayName(names))
                scope.post.agentName?.let { put("agentName", it) }
                put("createdAt", scope.post.createdAt)
                // A fact about this reader, always present as a bool. See watch_forum_post.
                put("watching", watching)
                putJsonArray("comments") {
                    comments.forEach { comment ->
                        add(
                            buildJsonObject {
                                put("id", comment.id)
                                put("body", comment.body)
                                put("author", comment.author.displayName(names))
                                comment.agentName?.let { put("agentName", it) }
                                put("createdAt", comment.createdAt)
                            },
                        )
                    }
                }
            },
        )
    }

    /**
     * Start a post, in one call.
     *
     * Draft-then-publish, and the draft is deleted if publishing fails, for
     * [createIssue]'s reason exactly: a two-call dance would mean an agent that
     * stopped thinking between them leaves an invisible half-post behind for ever.
     */
    private suspend fun createForumPost(user: UserRecord, arguments: JsonObject): McpToolResult {
        val scope = forumScope(user, arguments).getOrElse { return refuse(it.message ?: "No such forum.") }
        val attribution = resolveAttribution(user, arguments)
            .getOrElse { return refuse(it.message ?: "That attribution cannot be used.") }
        val agentName = resolveAgentName(arguments)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }
        val title = arguments.string("title") ?: return refuse("A post needs a title.")
        val body = arguments.string("body") ?: return refuse("A post needs something in it.")

        val draftId = deps.forumPosts.createPostDraft(
            scope.forum.id,
            attribution.author,
            attribution.at,
            agentName,
        )
        val draft = deps.forumPosts.findPost(draftId) ?: return refuse("The post could not be created.")
        val published = try {
            deps.forumPosts.publishPost(draft, title, body)
        } catch (failure: Exception) {
            // The half of "one call, both steps, or neither" that makes it true.
            deps.forumPosts.deletePost(draft)
            if (failure is ForumPostRefusal) return refuse(failure.message ?: "That post was refused.")
            throw failure
        }

        if (!arguments.isBackfill()) announceForumPost(scope, published, actorId = user.id)
        return ok("Posted \"${published.title}\" (post id ${published.id}) in ${scope.forum.name}.")
    }

    private suspend fun updateForumPost(user: UserRecord, arguments: JsonObject): McpToolResult {
        val scope = forumPostScope(user, arguments).getOrElse { return refuse(it.message ?: "No such post.") }
        // Defaulted to what the post already says, so an edit that mentions
        // neither author nor date rewrites exactly what was there. The same
        // arrangement update_comment has; see resolveAttribution's last section.
        val attribution = resolveAttribution(
            user,
            arguments,
            default = Attribution(scope.post.author, scope.post.createdAt),
            removalOutcome = "the post keeps its current author and date",
        ).getOrElse { return refuse(it.message ?: "That attribution cannot be used.") }
        val agentName = resolveAgentName(arguments)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }
            ?: scope.post.agentName

        refusable {
            deps.forumPosts.editPost(
                post = scope.post,
                title = arguments.string("title") ?: scope.post.title,
                body = arguments.string("body") ?: scope.post.body,
                createdAt = attribution.at ?: scope.post.createdAt,
                author = attribution.author,
                agentName = agentName,
            )
        }.getOrElse { return refuse(it.message ?: "That post could not be changed.") }
        return ok("Updated post ${scope.post.id}.")
    }

    private suspend fun deleteForumPost(user: UserRecord, arguments: JsonObject): McpToolResult {
        val scope = forumPostScope(user, arguments).getOrElse { return refuse(it.message ?: "No such post.") }
        // Counted before the row is gone; see deleteForum, whose reasoning this is.
        val comments = deps.forumPosts.commentsOn(scope.post.id).size

        deps.forumPosts.deletePost(scope.post)
        return ok(
            buildString {
                append("Deleted \"${scope.post.title}\" from ${scope.forum.name}")
                if (comments > 0) append(" and its $comments ${plural(comments.toLong(), "comment")}")
                append(". This cannot be undone, and nothing recorded that it happened.")
            },
        )
    }

    private suspend fun createForumComment(user: UserRecord, arguments: JsonObject): McpToolResult {
        val scope = forumPostScope(user, arguments).getOrElse { return refuse(it.message ?: "No such post.") }
        val attribution = resolveAttribution(user, arguments)
            .getOrElse { return refuse(it.message ?: "That attribution cannot be used.") }
        val agentName = resolveAgentName(arguments)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }
        val body = arguments.string("body") ?: return refuse("A comment needs something in it.")

        val draftId = deps.forumPosts.createCommentDraft(
            scope.post.id,
            attribution.author,
            attribution.at,
            agentName,
        )
        val draft = deps.forumPosts.findComment(draftId)
            ?: return refuse("The comment could not be created.")
        val published = try {
            deps.forumPosts.publishComment(draft, body)
        } catch (failure: Exception) {
            deps.forumPosts.deleteComment(draft)
            if (failure is ForumPostRefusal) return refuse(failure.message ?: "That comment was refused.")
            throw failure
        }

        if (!arguments.isBackfill()) {
            deps.forumNotifications.commentPublished(
                scope.project,
                scope.forum,
                scope.post,
                published,
                actorId = user.id,
            )
        }
        return ok("Commented on post ${scope.post.id}.")
    }

    private suspend fun updateForumComment(user: UserRecord, arguments: JsonObject): McpToolResult {
        val scope = forumCommentScope(user, arguments)
            .getOrElse { return refuse(it.message ?: "No such comment.") }
        val attribution = resolveAttribution(
            user,
            arguments,
            default = Attribution(scope.comment.author, scope.comment.createdAt),
            removalOutcome = "the comment keeps its current author and date",
        ).getOrElse { return refuse(it.message ?: "That attribution cannot be used.") }
        val agentName = resolveAgentName(arguments)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }
            ?: scope.comment.agentName

        refusable {
            deps.forumPosts.editComment(
                comment = scope.comment,
                body = arguments.string("body") ?: scope.comment.body,
                createdAt = attribution.at ?: scope.comment.createdAt,
                author = attribution.author,
                agentName = agentName,
            )
        }.getOrElse { return refuse(it.message ?: "That comment could not be changed.") }
        return ok("Updated forum comment ${scope.comment.id}.")
    }

    private suspend fun deleteForumComment(user: UserRecord, arguments: JsonObject): McpToolResult {
        val scope = forumCommentScope(user, arguments)
            .getOrElse { return refuse(it.message ?: "No such comment.") }
        deps.forumPosts.deleteComment(scope.comment)
        return ok(
            "Deleted forum comment ${scope.comment.id}. This cannot be undone, and nothing " +
                "recorded that it happened.",
        )
    }

    /**
     * Watch a forum's new posts, or stop — for yourself, or (named) somebody else.
     *
     * The forum counterpart of [watchIssue], reachable only through the forum gate
     * [forumScope] enforces, so every caller here is already a system administrator.
     * The subject rule is still [watchSubject]'s — the caller's own inbox by
     * default, another's admin-only — because "these tools are admin" and "changing
     * another person's subscription is an attribution" are two rules, and the second
     * should read the same here as it does on an issue.
     */
    private suspend fun watchForum(user: UserRecord, arguments: JsonObject): McpToolResult {
        val scope = forumScope(user, arguments).getOrElse { return refuse(it.message ?: "No such forum.") }
        val watching = arguments.bool(WATCHING_ARGUMENT) ?: true
        val subject = watchSubject(user, arguments, watching)
            .getOrElse { return refuse(it.message ?: "That watch could not be changed.") }
        deps.subscriptions.setForumNewPostSubscription(subject.id, scope.forum.id, watching)
        val who = if (subject.id == user.id) "You are" else "${subject.resolvedName} is"
        return ok(
            if (watching) "$who now watching forum \"${scope.forum.name}\"."
            else "$who no longer watching forum \"${scope.forum.name}\".",
        )
    }

    /** Watch a single post's thread, or stop. The post counterpart of [watchForum]. */
    private suspend fun watchForumPost(user: UserRecord, arguments: JsonObject): McpToolResult {
        val scope = forumPostScope(user, arguments).getOrElse { return refuse(it.message ?: "No such post.") }
        val watching = arguments.bool(WATCHING_ARGUMENT) ?: true
        val subject = watchSubject(user, arguments, watching)
            .getOrElse { return refuse(it.message ?: "That watch could not be changed.") }
        deps.subscriptions.setForumPostSubscription(subject.id, scope.post.id, watching)
        val who = if (subject.id == user.id) "You are" else "${subject.resolvedName} is"
        return ok(
            if (watching) "$who now watching post \"${scope.post.title}\"."
            else "$who no longer watching post \"${scope.post.title}\".",
        )
    }

    /**
     * Whose inbox a watch call is about — the caller, or a named other person.
     *
     * The subject half of [watchIssue], lifted out so the forum watch tools resolve
     * it identically: absent `user` is the caller; a named user is admin-only via
     * [AccessControl.canAttributeWrites] and refused by name if unknown; and a
     * subscribe onto an address-less account is refused, since it would notify
     * nobody. Unwatching needs no address.
     */
    private suspend fun watchSubject(
        user: UserRecord,
        arguments: JsonObject,
        watching: Boolean,
    ): Result<UserRecord> {
        val subject = if (arguments.isPresent(USER_ARGUMENT)) {
            val named = arguments.string(USER_ARGUMENT)
                ?: return Result.failure(ResolutionRefusal("`$USER_ARGUMENT` was given as an empty name."))
            val id = resolveAuthor(named)
                .getOrElse { return Result.failure(ResolutionRefusal(it.message ?: "No such person.")) }
            if (id != user.id && !deps.access.canAttributeWrites(user)) {
                return Result.failure(
                    ResolutionRefusal(
                        "Only a system administrator can change somebody else's watch. Omit " +
                            "`$USER_ARGUMENT` to change your own. Nothing was written.",
                    ),
                )
            }
            deps.users.findById(id) ?: return Result.failure(ResolutionRefusal("No such person."))
        } else {
            user
        }
        if (watching && subject.email == null) {
            val who = if (subject.id == user.id) "You have" else "${subject.resolvedName} has"
            return Result.failure(
                ResolutionRefusal(
                    "$who no e-mail address on the account, so watching would notify nobody. Add one " +
                        "first. Nothing was written.",
                ),
            )
        }
        return Result.success(subject)
    }

    /**
     * A post has just been published by an agent: subscribe its author, then tell
     * the forum's watchers.
     *
     * The same two steps `ForumPostRoutes.announcePost` takes, called for the same
     * reason and deliberately not shared with it: that function is a
     * `BoardDependencies` extension over a route's `PostScope`, and reaching it
     * from here would mean widening a private type in another file to save four
     * lines. What must not drift is the *pair* — subscribing the author without
     * mailing the forum, or the reverse, would be a post that behaves unlike every
     * other post in the room.
     *
     * ── Why a backfilled post does not come through here ────────────────────
     *
     * The caller checks [isBackfill] first. An import writes history, and history
     * is not news: five thousand posts landing would be five thousand mails to
     * everybody watching the forum, arriving out of order, about conversations
     * that ended years ago. It would also subscribe imported authors to imported
     * threads, which is a mailbox nobody signed up for.
     *
     * The line is drawn at *the parameters*, not at the date, and that is
     * deliberate: "did the caller say this was somebody else's, or from another
     * time" is a question about intent that the caller answered explicitly, where
     * "is this timestamp old enough to be an import" is a threshold somebody would
     * have to guess at. An agent posting as itself, now, is announced — which is
     * the case where announcing is right.
     */
    private suspend fun announceForumPost(
        scope: ForumScope,
        post: ForumPostRecord,
        actorId: Long,
    ) {
        post.author.accountId?.let { deps.subscriptions.setForumPostSubscription(it, post.id, true) }
        deps.forumNotifications.postPublished(scope.project, scope.forum, post, actorId)
    }

    /**
     * Resolve a forum, having first established that this caller may reach forums
     * at all.
     *
     * The gate comes before the lookup so that a non-admin's refusal is about the
     * surface rather than about the id, and cannot be used to learn whether a
     * forum exists.
     */
    private suspend fun forumScope(
        user: UserRecord,
        arguments: JsonObject,
        argument: String = "forum_id",
    ): Result<ForumScope> {
        if (!deps.access.canUseForumTools(user)) return Result.failure(ResolutionRefusal(FORUM_REFUSAL))
        val forumId = arguments.long(argument)
            ?: return Result.failure(ResolutionRefusal("`$argument` must be a number."))
        val forum = deps.forums.findById(forumId)
            ?: return Result.failure(ResolutionRefusal(NO_SUCH_FORUM))
        // A forum is only as readable as its project, exactly as an issue is —
        // and answers identically for "there is no such project" and "you may not
        // see it". See noSuchProject.
        val project = deps.projects.findById(forum.projectId)
            ?.takeIf { deps.access.canReadProject(user, it) }
            ?: return Result.failure(ResolutionRefusal(NO_SUCH_FORUM))
        return Result.success(ForumScope(project, forum))
    }

    /**
     * ...and a post in it.
     *
     * ── Drafts do not exist on this surface, and that is the decision ────────
     *
     * A draft is refused as "no such post", for every tool. It is not a row an
     * agent should ever have reached: it is somebody's half-typed text in an open
     * composer, it appears in no list, and the only reason the row exists at all
     * is that an inline image needs an owner before the body is saved — see
     * ForumPosts.kt's preamble.
     *
     * The web `PUT` does address a draft, because publishing is the one thing that
     * has to name the row it is filling in. Nothing here needs that:
     * [createForumPost] mints its own draft and publishes it inside one call, so
     * no draft id ever leaves this file. Which means the whole class can be
     * excluded rather than guarded per tool, and "an agent cannot touch anybody's
     * unsent writing" is true by construction instead of by four checks that could
     * lose one.
     */
    private suspend fun forumPostScope(
        user: UserRecord,
        arguments: JsonObject,
        argument: String = "post_id",
    ): Result<ForumPostScope> {
        if (!deps.access.canUseForumTools(user)) return Result.failure(ResolutionRefusal(FORUM_REFUSAL))
        val postId = arguments.long(argument)
            ?: return Result.failure(ResolutionRefusal("`$argument` must be a number."))
        val post = deps.forumPosts.findPost(postId)?.takeIf { !it.isDraft }
            ?: return Result.failure(ResolutionRefusal(NO_SUCH_POST))
        val forum = deps.forums.findById(post.forumId)
            ?: return Result.failure(ResolutionRefusal(NO_SUCH_POST))
        val project = deps.projects.findById(forum.projectId)
            ?.takeIf { deps.access.canReadProject(user, it) }
            ?: return Result.failure(ResolutionRefusal(NO_SUCH_POST))
        return Result.success(ForumPostScope(project, forum, post))
    }

    /** ...and a comment on that post. Drafts are excluded; see [forumPostScope]. */
    private suspend fun forumCommentScope(
        user: UserRecord,
        arguments: JsonObject,
        argument: String = "comment_id",
    ): Result<ForumCommentScope> {
        if (!deps.access.canUseForumTools(user)) return Result.failure(ResolutionRefusal(FORUM_REFUSAL))
        val commentId = arguments.long(argument)
            ?: return Result.failure(ResolutionRefusal("`$argument` must be a number."))
        val comment = deps.forumPosts.findComment(commentId)?.takeIf { !it.isDraft }
            ?: return Result.failure(ResolutionRefusal(NO_SUCH_FORUM_COMMENT))
        // The post is resolved through the same helper the post tools use, so the
        // project check and the draft exclusion are one implementation rather
        // than two. A comment on a draft post is unreachable for that reason,
        // which is correct: the post it hangs off does not exist out here.
        val post = forumPostScope(user, buildJsonObject { put("post_id", comment.postId) })
            .getOrElse { return Result.failure(ResolutionRefusal(NO_SUCH_FORUM_COMMENT)) }
        return Result.success(ForumCommentScope(post, comment))
    }

    /**
     * Run a forum write, turning the two refusal types into a [Result].
     *
     * `ForumRefusal` and `ForumPostRefusal` are what the repositories throw for
     * things the caller can fix — a duplicate forum name, a blank title, an order
     * that does not name this project's forums. The routes turn them into a 409
     * with words; this turns them into a tool error with the same words, so the
     * agent reads the sentence a person would have. Anything else is a bug and
     * propagates to [McpServer]'s catch, which logs it.
     */
    private suspend fun <T> refusable(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (refusal: ForumRefusal) {
        Result.failure(refusal)
    } catch (refusal: ForumPostRefusal) {
        Result.failure(refusal)
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
     *   was, rather than here. Null only ever arrives on a create; [updateComment]
     *   and [updateIssue] build their default with the row's existing timestamp, so
     *   an edit always carries a concrete one.
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
     *
     * ── One function, create and edit both ────────────────────────────────────
     *
     * [updateComment] reuses this rather than forking a near-copy — the whole
     * reason it is one function survives an edit caller. The only things that
     * differ on an edit are the answers when nothing was asked: [default] carries
     * the row's *current* author and timestamp, so an untouched edit re-writes what
     * was already there instead of re-authoring to the token user and re-stamping
     * to now. [removalOutcome] is the tail of the not-admin refusal, since "remove
     * these and it files under you, now" is only true at creation.
     */
    private suspend fun resolveAttribution(
        user: UserRecord,
        arguments: JsonObject,
        default: Attribution = Attribution(author = Author.Account(user.id), at = null),
        removalOutcome: String = "the row will be filed under your own name, stamped now",
    ): Result<Attribution> {
        val wantsAuthor = arguments.isPresent(AUTHOR_ARGUMENT)
        val wantsExternalAuthor = arguments.isPresent(AUTHOR_EXTERNAL_ARGUMENT)
        val wantsTimestamp = arguments.isPresent(CREATED_AT_ARGUMENT)
        if (!wantsAuthor && !wantsExternalAuthor && !wantsTimestamp) {
            // The ordinary path, and the overwhelmingly common one: on a create,
            // authored by the token's user and stamped by the store — byte-for-byte
            // what happened before any of these parameters existed; on an edit, the
            // row's own current values, left exactly as they were.
            return Result.success(default)
        }

        if (!deps.access.canAttributeWrites(user)) {
            val asked = listOfNotNull(
                AUTHOR_ARGUMENT.takeIf { wantsAuthor },
                AUTHOR_EXTERNAL_ARGUMENT.takeIf { wantsExternalAuthor },
                CREATED_AT_ARGUMENT.takeIf { wantsTimestamp },
            ).joinToString(" and ")
            return Result.failure(
                ResolutionRefusal(
                    "Only a system administrator can set $asked, and you are not acting as one. Nothing was " +
                        "written. Remove $asked and $removalOutcome — but that is a different thing " +
                        "from what you asked for, so decide rather than assume.",
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
            // Only a timestamp was asked for: the author is whatever it already was
            // — the token user on a create, the row's current author on an edit.
            else -> default.author
        }

        val at = if (wantsTimestamp) {
            resolveTimestamp(arguments).getOrElse { return Result.failure(it) }
        } else {
            default.at
        }

        return Result.success(Attribution(author = author, at = at))
    }

    /**
     * The agent's own name for a write it is making, or null.
     *
     * Nothing like [resolveAttribution]'s ceremony, and deliberately: this is not
     * a permission and asks no question of [AccessControl]. An agent naming itself
     * on the row it writes is the ordinary, encouraged case — the whole point is
     * that a reader can see a human did not type this — where writing *as somebody
     * else* is the admin-only act that [resolveAttribution] guards. So there is no
     * "did they ask, may they" here; there is only a value, trimmed, with an upper
     * bound so a runaway string cannot become a row nobody can read past.
     *
     * Absent, blank and null all collapse to null — the override the instructions
     * describe: an agent told to file purely as the user simply leaves it out, and
     * the badge does not appear. There is nothing to refuse for its absence.
     */
    private fun resolveAgentName(arguments: JsonObject): Result<String?> {
        val name = arguments.string(AGENT_NAME_ARGUMENT)?.trim()?.takeIf { it.isNotBlank() }
            ?: return Result.success(null)
        if (name.length > MAX_AGENT_NAME_LENGTH) {
            return Result.failure(
                ResolutionRefusal(
                    "`$AGENT_NAME_ARGUMENT` is too long — keep it under $MAX_AGENT_NAME_LENGTH " +
                        "characters. It is a name, not a description of what you did.",
                ),
            )
        }
        return Result.success(name)
    }

    /**
     * The agent label for an EDIT: set it, leave it, or — for an admin — clear it.
     *
     * A create can only ever ADD a badge, so [resolveAgentName] is all it needs:
     * absent, null and blank alike mean "no badge", and there is nothing yet to
     * remove. An edit is the one place a badge can already exist and need to *go* —
     * a migration forces the case, because a row imported from another tracker was
     * not made by an agent and must not wear one. So the edit tools carry one state
     * more than a create does, and it is admin-only for [resolveAttribution]'s
     * reason: the badge is the mark that says "an agent did this", and removing it
     * rewrites the record of who did, which is [AccessControl.canAttributeWrites]'s
     * gate and no lighter an act than re-authoring the row.
     *
     * The four states, by how `agent_name` arrives:
     *
     *  - **Absent, or explicit null → leave [current] alone.** Null counts as absent
     *    for [isPresent]'s reason: models null-fill fields they have nothing to say
     *    about, and that must not silently strip a badge.
     *  - **Present and blank (`""` or whitespace) → CLEAR the badge.** Admin-only: a
     *    non-admin who asks is refused with the parameter named, never quietly left
     *    as-is — the silent substitution this whole surface refuses to make.
     *  - **Present and a real name → set it,** length-checked by [resolveAgentName].
     *    Not admin-only; labelling a row you are already allowed to edit is the
     *    ordinary case.
     */
    private suspend fun resolveAgentNameEdit(
        user: UserRecord,
        arguments: JsonObject,
        current: String?,
    ): Result<String?> {
        val element = arguments[AGENT_NAME_ARGUMENT]
        // Absent, or an explicit null: say nothing, change nothing.
        if (element == null || element is JsonNull) return Result.success(current)
        // Present and non-null. A blank value is a request to REMOVE the badge —
        // string() reports blank as null — and only a system administrator may.
        if (arguments.string(AGENT_NAME_ARGUMENT) == null) {
            if (!deps.access.canAttributeWrites(user)) {
                return Result.failure(
                    ResolutionRefusal(
                        "Only a system administrator can clear an agent label, and you are not " +
                            "acting as one. An empty `$AGENT_NAME_ARGUMENT` asks to remove the " +
                            "badge; omit the parameter entirely to leave it exactly as it is. " +
                            "Nothing was written.",
                    ),
                )
            }
            return Result.success(null)
        }
        // A real name: set it, on resolveAgentName's terms.
        return resolveAgentName(arguments)
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
     * Who should hold this issue, or null for nobody.
     *
     * ── Absent, null and blank are three things, and only one is silence ──────
     *
     * Only an *absent* key means "say nothing about the assignee"; [current] comes
     * straight back, which is the issue's own value on an edit and null on a
     * create. A key that is present means the agent said something, so an explicit
     * JSON null — the most natural way to write "nobody" — unassigns rather than
     * doing nothing. Note this cannot use [isPresent], which answers false for
     * JsonNull: that is the right reading for `created_at`, where null is a
     * non-answer, and the wrong one here, where it is the answer. Blank collapses
     * into the same case, since [string] already treats `""` as no value.
     *
     * This mirrors `labels` and `components`, where an empty array clears the set:
     * an agent that cannot express "none" cannot undo its own mistakes.
     *
     * ── Matched against the assignable set, not against every account ─────────
     *
     * The candidates are [assignableUsers] for this project — the same list the
     * editor's dropdown is built from, so the tool and the UI cannot disagree about
     * who exists. That scoping is also what keeps the refusals honest: searching
     * every account would mean "there is no such person" and "they cannot be
     * assigned here" are different sentences, and the difference is an oracle for
     * which accounts exist on this instance, handed to anyone with edit rights on
     * one issue. `POST /api/issues/{id}/assignee` collapses the two for exactly
     * this reason; so does the PUT. This gives one answer for both, and it names
     * only people the caller could already see in the dropdown.
     *
     * Ambiguity within that set is still refused rather than guessed, as
     * [resolveAuthor] refuses it: handing an issue to the wrong Anna Karlsson is a
     * quiet wrong that only she can notice, and she is the one person the
     * assignment mail does not tell.
     *
     * ── The permission, and why the caller is not re-checked ──────────────────
     *
     * Callers have already established that the caller may write here —
     * [updateIssue] via `canEditIssue`, [createIssue] via `canCreateIssue`. What is
     * left is the question the assignee route asks about the person being *named*:
     * may they be assigned here? See [AccessControl.canBeAssigned] for why those
     * are two questions about two different people. Membership of [assignableUsers]
     * is that check, applied to the caller naming themselves too — being able to
     * edit an issue is not the same as being assignable on the project, and an
     * admin qualifies either way.
     *
     * So MCP assignment is strictly the editor's path: it needs write rights, where
     * the web app's button needs only `be_assigned_issue`. Deliberate. The button
     * exists so somebody who cannot edit can still pick up work by hand; an agent
     * filing or editing an issue on your behalf is already doing more than that.
     */
    /**
     * Which sprint an argument names, by name.
     *
     * Absent means [current] — the same "say nothing, change nothing" rule every
     * other optional argument on `update_issue` follows, and the reason a
     * kanban-project agent that has never heard of sprints cannot accidentally
     * un-schedule anything. An explicit null means the backlog, which is a real
     * destination and not a way of saying nothing.
     *
     * Completed sprints are refused as a *destination* while still being a legal
     * current value: scheduling work into a sprint that is over is very nearly
     * always a mistake, and always one the agent can see coming, because
     * `get_board` reports `completedAt` on every sprint it lists.
     *
     * An unknown name is refused with the list, never dropped — see
     * [refuseUnknown]. An edit that quietly kept the old sprint would report
     * success for a move it did not make.
     */
    private suspend fun resolveSprint(
        projectId: Long,
        arguments: JsonObject,
        current: Long?,
    ): Result<Long?> {
        if (!arguments.containsKey(SPRINT_ARGUMENT)) return Result.success(current)
        // JSON null — and ONLY JSON null — means the backlog. `string()` also
        // returns null for a blank string and for anything that is not a
        // primitive, and collapsing those into "un-schedule this" is exactly the
        // silent destruction the `current` default above exists to prevent: a
        // model that emits `"sprint": ""` while fixing a typo would take the
        // issue out of the active sprint and be told it succeeded. `isPresent`
        // exists in this file to keep absent and garbage apart; this is what it
        // is for.
        if (!arguments.isPresent(SPRINT_ARGUMENT)) return Result.success(null)
        val named = arguments.string(SPRINT_ARGUMENT)
            ?: return Result.failure(
                ResolutionRefusal(
                    "The `sprint` argument must be a sprint name from get_board, or null for the " +
                        "backlog. Omit it entirely to leave the issue where it is.",
                ),
            )

        val sprints = deps.sprints.forProject(projectId)
        val match = sprints.firstOrNull { it.name.equals(named.trim(), ignoreCase = true) }
            ?: return Result.failure(
                ResolutionRefusal(
                    if (sprints.isEmpty()) {
                        "This project has no sprints, so \"$named\" cannot be one. Issues here " +
                            "are not scheduled into sprints at all; omit the argument."
                    } else {
                        "\"$named\" is not a sprint in this project. Available: " +
                            sprints.joinToString(", ") { it.name } + "."
                    },
                ),
            )
        if (!match.isOpen && match.id != current) {
            return Result.failure(
                ResolutionRefusal(
                    "\"${match.name}\" has been completed, so work cannot be scheduled into it.",
                ),
            )
        }
        return Result.success(match.id)
    }

    /**
     * A version argument (planned or fixed) resolved to its id, by name from
     * get_board — [resolveSprint]'s twin, and it keeps the same three-way reading of
     * the argument (LNL-134): absent leaves [current], JSON null clears it, and a
     * name resolves or is refused with the list. One helper for both fields; the
     * caller passes which argument it is reading.
     *
     * The fix-version *requirement* is not enforced here — that is
     * [checkFixedVersionRequirementForMcp]'s job, run once after the resolution is
     * known, exactly as the route runs resolveFixedVersion after resolveResolution.
     */
    private suspend fun resolveVersion(
        projectId: Long,
        arguments: JsonObject,
        argument: String,
        current: Long?,
    ): Result<Long?> {
        if (!arguments.containsKey(argument)) return Result.success(current)
        if (!arguments.isPresent(argument)) return Result.success(null)
        val named = arguments.string(argument)
            ?: return Result.failure(
                ResolutionRefusal(
                    "The `$argument` argument must be a version name from get_board, or null to clear it. " +
                        "Omit it to leave the issue's version unchanged.",
                ),
            )
        val versions = deps.versions.forProject(projectId)
        val match = versions.firstOrNull { it.name.equals(named.trim(), ignoreCase = true) }
            ?: return Result.failure(
                ResolutionRefusal(
                    if (versions.isEmpty()) {
                        "This project has no versions, so \"$named\" cannot be one. Omit the argument."
                    } else {
                        "\"$named\" is not a version in this project. Available: " +
                            versions.joinToString(", ") { it.name } + "."
                    },
                ),
            )
        return Result.success(match.id)
    }

    /**
     * Read the `parent_id` argument into an intent, keeping absent, null and a
     * value apart (LNL-55) — the same three-way [resolveSprint] and [resolveVersion]
     * take, and for the same reason: an edit that says nothing about the parent must
     * leave it, and only an explicit JSON null detaches. It resolves the *intent*
     * only; applying it is [IssueRepository.setParent]'s job, which enforces the
     * same-project / one-level / no-cycle rules and is where a bad parent is refused
     * with the reason.
     */
    private fun parentIntent(arguments: JsonObject): Result<ParentIntent> {
        if (!arguments.containsKey(PARENT_ARGUMENT)) return Result.success(ParentIntent.Leave)
        // JSON null — and only it — detaches, exactly as resolveSprint reads null as
        // the backlog. `long()` returning null here means garbage (a string that is
        // not a number), which is a refusal about the request, not a silent detach.
        if (!arguments.isPresent(PARENT_ARGUMENT)) return Result.success(ParentIntent.Detach)
        val id = arguments.long(PARENT_ARGUMENT)
            ?: return Result.failure(
                ResolutionRefusal(
                    "The `$PARENT_ARGUMENT` argument must be an issue id (the `id` from get_board or " +
                        "get_issue, not the FOO-123 key), or null to detach. Omit it to leave the " +
                        "parent unchanged.",
                ),
            )
        return Result.success(ParentIntent.Attach(id))
    }

    /**
     * Refuse an MCP close that omits a required fixed version, so an agent has the
     * same constraint a person does (LNL-134). Delegates to the route's own
     * [se.soderbjorn.lunicle.resolveFixedVersion] so the rule lives in exactly one
     * place — the requirement is the project's, not the transport's.
     */
    private suspend fun checkFixedVersionRequirementForMcp(
        projectId: Long,
        resolutionId: Long?,
        fixedVersionId: Long?,
    ): Result<Unit> =
        deps.resolveFixedVersion(projectId, resolutionId, fixedVersionId).map { }

    private suspend fun resolveAssignee(
        projectId: Long,
        arguments: JsonObject,
        current: Long?,
    ): Result<Long?> {
        if (!arguments.containsKey(ASSIGNEE_ARGUMENT)) return Result.success(current)
        val named = arguments.string(ASSIGNEE_ARGUMENT) ?: return Result.success(null)

        val wanted = named.trim()
        val candidates = deps.assignableUsers(projectId)
        val byEmail = candidates.filter { it.email?.equals(wanted, ignoreCase = true) == true }
        val matches = byEmail.ifEmpty {
            candidates.filter { it.resolvedName.equals(wanted, ignoreCase = true) }
        }

        return when (matches.size) {
            1 -> Result.success(matches.single().id)
            // Refused rather than dropped: an edit that quietly kept the old
            // assignee would report success for a change it did not make.
            0 -> Result.failure(
                ResolutionRefusal(
                    "\"$wanted\" cannot be assigned issues in this project, so nothing was " +
                        "written. Either there is no such account or it has not been granted " +
                        "that on this project. Use the display name exactly as the board shows " +
                        "it, or the email address on the account.",
                ),
            )
            else -> Result.failure(
                ResolutionRefusal(
                    "${matches.size} people who can be assigned issues in this project are " +
                        "called \"$wanted\", so this would be a guess about who gets the work. " +
                        "Name the assignee by the email address on their account instead. " +
                        "Nothing was written.",
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
     * nonsense: a comment stamped in the year 3000 sits at the bottom of its
     * thread forever (Comments.sq orders by `created_at`), and both columns render
     * as a date nobody can explain from the UI. A bad value is admin-only to reach
     * — at creation, or through `update_comment` and `update_issue` — and every
     * path comes through here, so one set of bounds keeps it out at every door.
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
     *
     * @param argument which parameter is being read, so the refusals quote the name
     *   the caller actually sent. Both bounds are properties of the column rather
     *   than of `created_at`, so `updated_at` gets them by passing its own name
     *   rather than by a second copy of this function drifting from this one.
     */
    private fun resolveTimestamp(
        arguments: JsonObject,
        argument: String = CREATED_AT_ARGUMENT,
    ): Result<Long> {
        val at = arguments.long(argument)
            ?: return Result.failure(
                ResolutionRefusal(
                    "`$argument` must be a number of milliseconds since the Unix " +
                        "epoch, which \"${arguments[argument]}\" is not.",
                ),
            )
        if (at < 0) {
            return Result.failure(
                ResolutionRefusal("`$argument` cannot be negative; $at is before 1970."),
            )
        }
        val ceiling = System.currentTimeMillis() + MAX_MCP_BACKFILL_SKEW_MILLIS
        if (at > ceiling) {
            return Result.failure(
                ResolutionRefusal(
                    "`$argument` is in the future ($at). Backfilling is for history " +
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
     * Resolve a comment whose project the caller may read.
     *
     * A comment is only as readable as the issue it hangs off, which is only as
     * readable as its project — the same chain [readableIssue] walks, and the same
     * conflation [noSuchComment] makes: a comment in a project you cannot see is
     * indistinguishable from one that does not exist, so a comment id cannot be
     * used to probe a private board. The narrower "is it yours to edit" question
     * is [AccessControl.canEditComment], asked by the caller after this returns.
     */
    private suspend fun readableComment(user: UserRecord, arguments: JsonObject): CommentRecord? {
        val comment = arguments.long("comment_id")?.let { deps.comments.findById(it) } ?: return null
        val issue = deps.issues.findById(comment.issueId) ?: return null
        val project = deps.projects.findById(issue.projectId) ?: return null
        return comment.takeIf { deps.access.canReadProject(user, project) }
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

    /** "No such comment", for both "there isn't one" and "you may not see its project" — see [noSuchProject]. */
    private fun noSuchComment() = refuse("No such comment.")

    /** "No such history event", for both "there isn't one" and "you may not see its project" — see [noSuchProject]. */
    private fun noSuchHistoryEvent() = refuse("No such history event.")

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
private const val UPDATED_AT_ARGUMENT = "updated_at"
private const val AGENT_NAME_ARGUMENT = "agent_name"
private const val EVENT_ID_ARGUMENT = "event_id"
private const val WATCHING_ARGUMENT = "watching"
private const val USER_ARGUMENT = "user"
private const val ASSIGNEE_ARGUMENT = "assignee"
private const val SPRINT_ARGUMENT = "sprint"
private const val PLANNED_VERSION_ARGUMENT = "planned_version"
private const val FIXED_VERSION_ARGUMENT = "fixed_version"
private const val PARENT_ARGUMENT = "parent_id"

/** What an agent asked to happen to an issue's parent (LNL-55). See [McpTools.parentIntent]. */
private sealed interface ParentIntent {
    /** The argument was absent — leave the parent as it is. */
    object Leave : ParentIntent

    /** JSON null — detach the issue from its epic. */
    object Detach : ParentIntent

    /** Attach the issue under this parent id, subject to IssueRepository's rules. */
    data class Attach(val parentId: Long) : ParentIntent
}

private const val MOVE_UNFINISHED_ARGUMENT = "move_unfinished_to"

/**
 * How long an [AGENT_NAME_ARGUMENT] may be.
 *
 * A name, not a sentence. Generous enough for "Acme Support Assistant (v2)" and
 * short enough that a model which mistook the field for a changelog is refused
 * rather than filling a badge with a paragraph. See [McpTools.resolveAgentName].
 */
private const val MAX_AGENT_NAME_LENGTH = 80

/**
 * The bounds on an agent-sent e-mail. See [McpTools.sendEmail].
 *
 * Both are generous by design: they exist so that a model in a loop cannot turn
 * one tool call into a message nobody can open, not to police how much an agent
 * has to say. The subject bound is roughly where every mail client has truncated
 * it anyway; the body bound is a long report and nothing near a log.
 */
private const val MAX_AGENT_MAIL_SUBJECT_LENGTH = 200
private const val MAX_AGENT_MAIL_BODY_LENGTH = 20_000

/**
 * Shared by `create_issue` and `add_comment`: one description of `agent_name`.
 *
 * The one write parameter here that an agent is meant to send on the ordinary
 * path rather than avoid — so the description leads with *do*, and names the
 * override rather than leaving the model to infer there is one. Unlike `author`,
 * it is not admin-only and changes nothing about ownership, which the last line
 * says out loud so a model does not lump it in with the backfill parameters above
 * and shy away from it.
 */
/**
 * The half of the `assignee` description both tools share. Each appends what its
 * own absent case means, which is the only thing that differs: an edit that says
 * nothing keeps the current assignee, a create that says nothing has none.
 */
private const val ASSIGNEE_PROP_DESCRIPTION =
    "Who should hold this issue: a display name exactly as the board shows it, or the email " +
        "address on their account. They must be somebody who may be assigned issues in this " +
        "project — anyone else is refused rather than quietly ignored, and the refusal " +
        "deliberately does not distinguish an unknown name from an account that simply lacks " +
        "the right. An ambiguous name is refused too; use the email address to settle it. Send " +
        "null to leave the issue unassigned."

private const val SPRINT_PROP_DESCRIPTION =
    "A sprint name from get_board, scheduling this issue into that sprint. Most projects have " +
        "no sprints at all — get_board omits the field entirely for those — and an issue in " +
        "such a project simply is not scheduled, so leave this alone unless you saw sprints " +
        "listed. Send null to move the issue to the backlog, which is where an unscheduled " +
        "issue lives. A sprint that has been completed is refused as a destination."

private const val PLANNED_VERSION_PROP_DESCRIPTION =
    "A version name from get_board — the release this issue is planned for. Most projects have " +
        "no versions at all, so leave this alone unless you saw versions listed. Send null to " +
        "clear it."

private const val FIXED_VERSION_PROP_DESCRIPTION =
    "A version name from get_board — the release this issue was fixed in. Some projects require " +
        "one when closing an issue as a done resolution; you will be refused with the reason if " +
        "so. Send null to clear it."

private const val PARENT_PROP_DESCRIPTION =
    "The id of the issue this one belongs under — its epic. An issue that has children is an " +
        "epic; there is no separate epic type, just this link. The parent's id is the `id` field " +
        "get_board and get_issue report (not the FOO-123 key). It must be a published issue in " +
        "the SAME project, and epics are one level deep: you cannot parent an issue that already " +
        "has a parent, and you cannot give a parent to an issue that already has children of its " +
        "own — you will be refused with the reason. get_issue reports an issue's `parent` and its " +
        "`children`."

private const val AGENT_NAME_PROP_DESCRIPTION =
    "Your own name as the agent doing this on the user's behalf — for example the assistant " +
        "or product you are. NORMALLY SET IT: it is how the board shows, clearly, that an agent " +
        "filed this rather than a human typing by hand, and that is the expected default for a " +
        "write made through this MCP server. Omit it only when you have been explicitly asked to " +
        "act purely as the user with no agent attribution. This is NOT admin-only and does not " +
        "change who the issue or comment belongs to — it rides alongside the user's own account " +
        "as a label, nothing more."

/** Shared by every tool that takes it: one description of `author`, not several that drift. */
private const val AUTHOR_PROP_DESCRIPTION =
    "SYSTEM ADMINISTRATOR ONLY, for backfilling imported history. Who this should belong to: their " +
        "display name exactly as get_board and get_issue report it, or the email address on " +
        "their account when two people share a name. They must already have a Lunicle " +
        "account — naming somebody does not create one — and an ambiguous name is refused " +
        "rather than guessed at. If they have no account, use author_external instead; do not " +
        "pass both. Refused, not ignored, if you are not a system administrator. Defaults to you."

/** As [AUTHOR_PROP_DESCRIPTION]: one description of `author_external`, shared. */
private const val AUTHOR_EXTERNAL_PROP_DESCRIPTION =
    "SYSTEM ADMINISTRATOR ONLY, for backfilling imported history written by somebody with no Lunicle " +
        "account — a GitHub handle, say, from a tracker being migrated. Recorded as the name " +
        "itself and rendered as the author; it creates no account and grants nobody anything, " +
        "so the row is unowned and only a system administrator can edit it afterwards. Use `author` instead " +
        "when the person does have an account, and never pass both — they are two answers to " +
        "one question and the pair is refused. Not checked against existing accounts: if you " +
        "pass a name somebody here happens to share, you get an author who is not them. " +
        "Refused, not ignored, if you are not a system administrator."

/**
 * As [AUTHOR_PROP_DESCRIPTION]: one description of `updated_at`, shared.
 *
 * Says what it is *for* rather than only what it does. The tool that needs it is
 * the one an importer reaches for last — after an attachment exists and the body
 * has to be rewritten to point at it — and by then the reason the parameter is
 * there at all is easy to miss.
 */
private const val UPDATED_AT_PROP_DESCRIPTION =
    "SYSTEM ADMINISTRATOR ONLY, for backfilling. When this issue was last touched, in epoch milliseconds. " +
        "Cannot be in the future, and cannot be before the issue's own created_at — an issue " +
        "edited before it existed is not a history anyone can read. Every edit stamps this " +
        "column, so an import that uploads an attachment and then rewrites the description to " +
        "point at it would otherwise drag a years-old issue to the top of the board, dated " +
        "today: pass the date the history actually ended. Refused, not ignored, if you are not " +
        "a system administrator. Defaults to now."

/**
 * How far past this server's clock a backfilled timestamp may still land.
 *
 * A day. Not a tolerance for the future — backfilling is about the past — but for
 * the fact that "now" is measured on the agent's clock and checked on ours. See
 * [McpTools.resolveTimestamp].
 */
private const val MAX_MCP_BACKFILL_SKEW_MILLIS = 24L * 60 * 60 * 1000

/**
 * The forum tools, named once.
 *
 * A set rather than a prefix test on the tool name, deliberately: `list_forums`
 * and `create_forum_post` share no prefix, `delete_comment` and
 * `delete_forum_comment` differ by a word in the middle, and a filter that got
 * either wrong would silently offer an admin-only tool to everybody. It is
 * checked against [McpTools.tools] by the test suite, so a tool added to one and
 * forgotten in the other fails there rather than in production.
 */
private val FORUM_TOOL_NAMES = setOf(
    "list_forums",
    "create_forum",
    "update_forum",
    "delete_forum",
    "reorder_forums",
    "list_forum_posts",
    "get_forum_post",
    "create_forum_post",
    "update_forum_post",
    "delete_forum_post",
    "create_forum_comment",
    "update_forum_comment",
    "delete_forum_comment",
    "watch_forum",
    "watch_forum_post",
)

/**
 * The one refusal every forum tool gives a caller who is not a system
 * administrator.
 *
 * It says the capability does not exist for this account rather than that
 * something went wrong, and it says not to retry — an agent that reads "you
 * cannot" as "not yet" will spend a conversation rediscovering the same answer.
 * It also says what to do instead, because the person driving very often *can*
 * do this in the Discussion tab themselves.
 */
private const val FORUM_REFUSAL =
    "The discussion forums are not reachable over MCP by this account. They are restricted to " +
        "system administrators on this Lunicle server, and this is not a setting the user can " +
        "turn on themselves. Tell them what you would have done — they can do it in the " +
        "Discussion tab in Lunicle's web app — and do not try again."

/**
 * "No such forum", for both "there isn't one" and "you may not see its project".
 *
 * The same conflation [McpTools.noSuchProject] describes, one container down. A
 * forum id that named a project the caller cannot read must answer exactly as an
 * absent one does.
 */
private const val NO_SUCH_FORUM = "No such forum."

/** As [NO_SUCH_FORUM], and also what an unpublished draft answers — see `forumPostScope`. */
private const val NO_SUCH_POST = "No such post."

private const val NO_SUCH_FORUM_COMMENT = "No such forum comment."

/**
 * The four ways to say where an attachment is going, named once so
 * `start_attachment_upload`'s "exactly one of these" cannot drift from its schema
 * or from the sentence it refuses with.
 */
private val ATTACHMENT_TARGET_ARGUMENTS =
    listOf("issue_id", "comment_id", "forum_post_id", "forum_comment_id")

/**
 * `created_at` as the two forum create tools describe it.
 *
 * Separate from the issue tools' copies because it has to say the thing that is
 * only true here: passing it also silences the notification. That is the
 * consequence an agent most needs to know before choosing whether to send it, and
 * burying it in the tool description alone would mean a model that read the
 * parameter list and not the prose gets it wrong in the direction that mails
 * everybody.
 */
private const val FORUM_CREATED_AT_PROP_DESCRIPTION =
    "SYSTEM ADMINISTRATOR ONLY, for backfilling imported discussions. When this was written, in epoch " +
        "milliseconds. Cannot be in the future. NOTE THE SIDE EFFECT: sending this — or `author`, " +
        "or `author_external` — marks the write as imported history, so nobody is e-mailed about " +
        "it. Omit all three and the post or comment is announced to watchers exactly as a " +
        "person's would be. Defaults to now."

/**
 * Whether this call is a backfill: does it claim another author, or another time?
 *
 * The question `create_forum_post` and `create_forum_comment` ask to decide
 * whether anybody is e-mailed. By presence, matching [JsonObject.isPresent]'s rule
 * and `resolveAttribution`'s — a `null` an eager model filled in is not a claim.
 */
private fun JsonObject.isBackfill(): Boolean =
    isPresent(AUTHOR_ARGUMENT) || isPresent(AUTHOR_EXTERNAL_ARGUMENT) || isPresent(CREATED_AT_ARGUMENT)

/**
 * "1 post", "2 posts" — the plural of a count, for the sentences a delete returns.
 *
 * A function rather than the inline `if` [McpTools.deleteIssue] uses, because
 * LNL-78 needs it in four places and four copies of an English rule is three too
 * many. Every word it is used with pluralises with an "s".
 */
private fun plural(count: Long, noun: String): String = if (count == 1L) noun else "${noun}s"
