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
 * `author`, `author_external` and `created_at`, so that the instance owner importing
 * another tracker's history ends up with a board that says who wrote what and when —
 * rather than one where every issue was filed by them, today. §3 of the plan says
 * admin operations are not exposed over MCP and that there is one flat `mcp` scope;
 * both still hold, and this is deliberately not a counterexample to either:
 *
 *  - **No new scope**, and the one new tool is not the owner's — see below.
 *    The token still says only who you are.
 *  - **The gate is [AccessControl.canAttributeWrites]**, asked of the *token's*
 *    user on every call, server-side. Never of anything in the arguments.
 *  - **Refused, never ignored.** Anyone but the owner who sends any of them gets an
 *    error. Silently dropping them would be the worst outcome available: the agent
 *    would report that it had backfilled history under Ada's name having actually
 *    written it under its own, and the person reading that report has no way to
 *    know. A refusal is a fact; a quiet substitution is a lie.
 *  - **Creation only for everyone but the owner.** Anybody else can never rewrite an
 *    existing row's author or timestamps — those are set once, at creation, and no
 *    tool here lets an ordinary editor near them. The owner can: `update_issue` and
 *    `update_comment` let one re-attribute and re-date an existing issue or comment
 *    in place, an external author included. They earn the exception the way
 *    `start_attachment_upload` does — this surface offers no deletion, so an import
 *    that put the wrong name or date on a row could otherwise never be repaired at
 *    all. The gate is unchanged ([AccessControl.canAttributeWrites]); what the owner
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
 * It is deliberately **not** the instance owner's alone. Attaching a file is an
 * ordinary thing an ordinary user does; attaching one *as somebody else* is the part
 * only the owner may do, and that is [resolveAttribution]'s job here exactly as it is
 * on `create_issue`.
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
 * See [AccessControl.canSendAgentMail] for why that answer is the instance owner
 * and what anybody else who wants it should get instead.
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
 * LNL-78 gave them to the system administrator alone, which made them the largest
 * departure in this file from "an agent gets what the person driving it has in the
 * web app". An ordinary user posts in forums; their agent does not. That audience
 * was never restated in rungs, because there is nobody to restate it for: see
 * [AccessControl.canUseForumTools], which answers false for every caller, the
 * instance owner included. The argument for keeping it narrow when discussions come
 * back is still the one it gives — a forum is a room full of other people's words
 * that records no history, and posting in one mails everybody watching it.
 *
 * Two consequences worth stating out loud rather than discovering:
 *
 *  - **Gated twice, and the second one enforces.** The thirteen are filtered out
 *    of `tools/list` for everybody, exactly as `send_email` is for anyone but the
 *    instance owner — and every one of them asks again, because `tools/call` never
 *    consults that list.
 *  - **A backfilled write announces nothing.** `create_forum_post` and
 *    `create_forum_comment` fire the same notifications the web routes fire, so
 *    an agent starting a genuine thread reaches the forum's watchers — *unless*
 *    the call carries `author`, `author_external` or `created_at`, which is the
 *    signature of an import. Mailing a project once per row while five thousand
 *    posts land is not a courtesy anybody wants, and a post dated 2019 is not
 *    news. See [announceForumPost].
 *
 * ── The project's own structure, which an agent may now change (LNL-215) ────
 *
 * The five paragraphs above are about capability this surface *adds*. This one is
 * about capability it stopped withholding, and it is worth separating because it is
 * not an exception to anything: `list_vocabulary`, `add_vocabulary`,
 * `rename_vocabulary`, `reorder_vocabulary`, `delete_vocabulary` and
 * `set_estimate_mode` each map onto a route in [projectSettingsRoutes], ask the same
 * [AccessControl] question that route asks, and go through the same
 * [VocabularyRepository]. The first sentence of this file holds for all six.
 *
 * What changes is who the audience is. Everything else on this surface is work
 * *inside* a project — filing, editing, moving, closing — where these six are
 * decisions *about* one: which columns exist, which of them demand a resolution,
 * what the ways two issues can be related are called, whether the team estimates at
 * all. The gate is correspondingly higher and, crucially, is not one rung written
 * down here: [AccessControl.canEditVocabulary] answers per kind, because sprints and
 * versions are a maintainer's and the six that define what the board *is* are an
 * administrator's. This file asks that function and never the rung behind it, so
 * LNL-191's split — and the next one — reaches the agent without an edit here.
 *
 * The delete in `delete_vocabulary` is a real one, and the "no delete of anything"
 * sentence above is now three exceptions old. It is also the mildest of them: the
 * refusals that stop an administrator emptying a board of its last status, or
 * deleting a column three issues are sitting in, are [VocabularyRepository]'s and
 * are not restated here — which is exactly why the tool goes through it rather than
 * reaching for a store.
 *
 * ── Names, not ids, wherever a human would use a name ───────────────────────
 *
 * Statuses, priorities, resolutions, labels and components are all addressed by
 * name. The board already tells the agent what they are called, and an agent
 * asked to "close LUN-12 as fixed" should not first have to fetch a table to
 * learn that "Fixed" is 3. Issues and projects keep ids as well, because those
 * are what the ids are *for* — they are stable and a name is not.
 *
 * The vocabulary *editing* tools are the one deliberate departure, and the reason
 * is the thing being edited: renaming a row by its old name is a request that stops
 * making sense the moment it succeeds, and reordering one asks for the whole list
 * anyway. So `rename_vocabulary`, `reorder_vocabulary` and `delete_vocabulary` take
 * ids, and `list_vocabulary` exists to hand them out. Nothing about that widens what
 * a caller can see: every id it returns is already on the board response that every
 * reader of the project gets.
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
import kotlinx.serialization.json.JsonObjectBuilder
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
import se.soderbjorn.lunicle.clientserver.Estimate
import se.soderbjorn.lunicle.clientserver.EstimateMode
import se.soderbjorn.lunicle.clientserver.EstimateUnit
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
      • Issues can be LINKED to one another — "Blocked by", "Duplicate of", or
        whatever this project calls them; get_board lists its relation kinds and
        omits the field for a project that has none. get_issue reports one issue's
        links in both directions, already worded from that issue's side, and
        link_issues and unlink_issues change them. A card counts as BLOCKED when a
        link whose kind marks blocking points at an issue that is still open —
        get_board says so per issue and names the blockers.
      • Estimates are off unless a project turns them on. get_board reports
        "estimateMode": "none", "time" or "points". Under "time" an estimate is a
        number of whole MINUTES; under "points" it is whole points; under "none"
        any estimate at all is refused. estimate_amount and estimate_unit move
        together — send both or neither.
      • An issue is assigned to a person, and separately may be marked as work for
        that person's AGENT rather than for them in person (assignee_is_agent).
        Handing the issue to somebody else clears that flag unless the same call
        sets it again, and an issue nobody holds is never flagged.

    Omitting an argument means LEAVE THAT ALONE. That is true of every write tool
    here and it is deliberately not the same as sending the argument as null,
    which on the fields that accept one means "clear it": omit `assignee` and the
    current assignee stands, send it as null and the issue is unassigned. If you
    have nothing to say about a field, do not mention it — do not fill it in with
    null to be tidy.

    Permissions, and why a refusal is final: you are acting as a specific Lunicle
    user, and you have exactly their rights — no more. Those rights in a project
    are one rung on a ladder — viewer, contributor, maintainer, administrator,
    owner — and each rung contains the ones below it, so a refusal means the
    account you are acting as stands below the rung that action needs. A tool that
    returns an error saying you cannot do something is not a transient failure and
    retrying it will not help; the user you are acting as genuinely lacks that
    permission. Tell the person what happened rather than working around it.
    Reading is filtered the same way: a project you cannot see does not appear in
    list_projects, and there is no way to ask about it.

    One place you have LESS than the person you are acting as: a project where they
    stand at viewer — able to read it in their browser and nothing more — is not
    yours at all. It is missing from list_projects and every tool answers "no such
    project" for it, exactly as for a board they cannot see. So a person who is
    surprised that some project of theirs is not here is usually right about having
    access to it, and the answer is that they hold viewer there and an agent needs
    contributor. Say that rather than reporting the project as gone: it is a rung
    somebody can raise, not a mistake. This is the only respect in which you are
    narrower than them; everything else on this list you can do exactly to the
    extent they can.

    What is deliberately not here: you cannot create, rename or delete projects,
    or grant anybody a rung on one. Those tools do not exist, and deleting a
    stored attachment on its own exists only for the instance owner. If a task
    needs one of them, say so — do not approximate it.

    Changing what a project IS, as opposed to what is in it: list_vocabulary reads
    a project's statuses, priorities, resolutions, labels, components, sprints,
    versions and relation kinds with their ids, and add_vocabulary,
    rename_vocabulary, reorder_vocabulary and delete_vocabulary change them.
    set_estimate_mode decides whether the project estimates and in what unit.
    These ask for a rung most accounts do not hold on most projects — a project
    administrator for the six that define what the board is, a project maintainer
    for sprints and versions — so being refused here is an ordinary outcome and is
    final in the way described above. They are also the tools most worth confirming
    before you use: adding a status changes every board view for everybody, and
    renaming a priority rewrites what every card means. Deleting is refused while
    issues still hold the row, and refused for the last status or priority a
    project has, so those are two mistakes you cannot make — the rest you can.

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

    Backfilling history, if and only if you are acting as the instance owner:
    create_issue, add_comment, update_issue and update_comment take an optional
    `author`, an optional `author_external` and an optional `created_at`, for
    importing issues from somewhere else so that they keep the name and date they
    had — and, on the two update tools, for fixing one after the fact. All three
    are the owner's alone and are refused outright — not ignored — for anyone
    else, so do not send them speculatively: a refusal costs a round-trip, and the
    alternative you might imagine (it silently files under your own name) is
    exactly what the refusal exists to prevent.

    Which author parameter to use is a question about the person, not a fallback
    chain. `author` is for somebody who has a Lunicle account: name them by their
    display name as it appears on the board, or by the email address on their
    account if two people share a name. It is refused if no account matches, and
    that refusal is load-bearing — it is how a misspelled name gets caught instead
    of quietly becoming a stranger. `author_external` is for somebody who has no
    account at all, which is the ordinary case when importing from another tracker:
    it records the name as written, creates nothing, and grants nobody anything.
    Passing both is refused. `created_at` is epoch milliseconds and cannot be in
    the future. As the instance owner you can also correct all three after the fact
    — update_issue on an issue, update_comment on a comment — so a name or date
    that came in wrong is fixable in place rather than only at creation; for anyone
    else they are set once, when the row is written.

    `created_at` also sets the issue's last-touched time, which is what the board
    sorts on — but only at creation. Every later edit re-stamps that column with
    the wall clock, and an import usually ends in an edit: an inline image can only
    be attached once the issue exists, so the description has to be rewritten
    afterwards to point at the uploaded file. Done naively that lands a years-old
    issue at the top of the board dated today. `update_issue` therefore takes an
    owner-only `updated_at` — pass the date the imported history actually ended.
    It cannot be in the future, and cannot precede the issue's own `created_at`.

    An issue or comment filed under `author_external` is unowned: nobody inherits
    it by sharing the name it was filed under, so editing it afterwards takes a
    rung rather than authorship — a maintainer in its project for an issue, an
    instance administrator for a comment. That is a consequence of there being no
    account to own it, not an oversight, and it applies to imported attachments
    too.

    Saying you are an agent: create_issue and add_comment take an optional
    `agent_name`, and unlike the backfill parameters above this is one you should
    NORMALLY SEND. Put your own name in it — the assistant or product you are — so
    that the board shows clearly an agent filed the issue or wrote the comment
    rather than a human typing it by hand. Sending it needs no standing of any kind
    and does not change who the issue belongs to: it rides alongside the user's own
    account as a label.
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
 * Conditional rather than a sentence saying "the instance owner only", which is
 * how the backfill parameters above handle the same problem. The difference is
 * that those parameters live on tools everybody is offered, so an ordinary
 * caller's agent has to be told they exist in order to be told not to reach for
 * them. `send_email` is not in their `tools/list` at all, and a paragraph
 * explaining a tool that is not in the list is worse than silence: the model's
 * options are to hallucinate the call or to tell the person about a capability
 * they do not have. This text is also paid for on every conversation — see
 * [MCP_INSTRUCTIONS] — and almost none of them are the owner's.
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
// The text below predates LNL-191 and still speaks in system administrators — its
// first line tells the reader they reach forums by being one. It is appended for no
// caller at all, so nothing reads it, and it is left whole rather than
// half-corrected: whoever puts discussions back has to decide which rung they
// belong to first, and then this needs rewording to that rung.
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
 * whole of the permission check in [McpTools.resolveAttribution]. A caller who may
 * not backfill, sending `"created_at": "last Tuesday"`, must be refused for having
 * asked, not quietly given today's date because the value was unreadable. Asking
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
                "can be large enough to be awkward to read in one piece.\n\n" +
                "Two project-level fields are worth knowing before you write anything: " +
                "`estimateMode` (\"none\", \"time\" or \"points\") says whether this project " +
                "estimates and in what unit — always present, and \"none\" for most projects, " +
                "which is your signal that any estimate you send will be refused. " +
                "`relationKinds` names the ways two of its issues can be linked and is ABSENT " +
                "when there are none, exactly as `sprints` and `versions` are: nothing to see " +
                "means nothing to reason about. Per " +
                "issue you also get `assigneeIsAgent` when the work is flagged for the " +
                "assignee's agent, `estimate` when one is set, and `isBlocked` with " +
                "`blockedBy` naming the open issues holding it up. The blocked answer is " +
                "computed over the WHOLE project even when you filtered to one column, so a " +
                "blocker sitting somewhere you did not ask about still counts.\n\n" +
                "It does not report the vocabulary's ids, positions or usage counts — " +
                "list_vocabulary does, and that is the call to make before changing any of it.",
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
            description = "Get one issue in full: its description, all its comments, its links to " +
                "other issues, and its " +
                "history — what changed, who changed it, and when. " +
                "get_board returns titles only, so use this when the detail matters.\n\n" +
                "`relations` is this issue's links, in BOTH directions and already worded from " +
                "this issue's side: the one stored row that reads \"Blocked by FOO-9\" here " +
                "reads \"Blocks FOO-4\" over there, and you are given the sentence for the issue " +
                "you asked about rather than the raw row. Each entry carries `relationId` (what " +
                "unlink_issues takes), `label` (this side's wording), `kind` (the kind's own " +
                "from-side name, which is what link_issues takes) and the other issue's `id`, " +
                "`key` and `title`. Absent entirely when the issue has no links.\n\n" +
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
                "closing column does not say why it was closed. Newer builds may add kinds. The " +
                "instance owner can correct an entry's author or date — but never what it " +
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
                "assignee_is_agent" to boolProp(
                    ASSIGNEE_IS_AGENT_PROP_DESCRIPTION + " Omit it and the issue starts unflagged, " +
                        "which is what an issue filed in the web app gets.",
                ),
                "estimate_amount" to integerProp(
                    ESTIMATE_AMOUNT_PROP_DESCRIPTION + " Omit it — and `estimate_unit` with it — and " +
                        "the issue starts with no estimate.",
                ),
                "estimate_unit" to stringProp(ESTIMATE_UNIT_PROP_DESCRIPTION),
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
                    "INSTANCE OWNER ONLY, for backfilling. When this issue was written, in epoch " +
                        "milliseconds. Cannot be in the future. Also becomes the issue's " +
                        "last-touched time, which is what the board sorts on — the two are one " +
                        "value and cannot be set apart. Refused, not ignored, if you are not the " +
                        "instance owner. Defaults to now.",
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
                "assignee_is_agent" to boolProp(
                    ASSIGNEE_IS_AGENT_PROP_DESCRIPTION + " Omit it to leave the flag as it is — with " +
                        "one consequence worth knowing: handing the issue to somebody ELSE in this " +
                        "same call clears it, because the previous holder's agent is not on this any " +
                        "more. Send it as true alongside the new `assignee` if the work is going to " +
                        "their agent.",
                ),
                "estimate_amount" to integerProp(
                    ESTIMATE_AMOUNT_PROP_DESCRIPTION + " Omit it — and `estimate_unit` with it — to " +
                        "leave the current estimate alone. Send BOTH as null to clear it.",
                ),
                "estimate_unit" to stringProp(ESTIMATE_UNIT_PROP_DESCRIPTION),
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
                        "restricted to the instance owner, and it does not change who the issue " +
                        "belongs to. To REMOVE the badge — so a purely-human or migrated issue " +
                        "wears none — the instance owner sends an empty string; anyone else who " +
                        "tries is refused.",
                ),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(
                    "INSTANCE OWNER ONLY, for correcting backfilled history. When this issue should claim " +
                        "to have been written, in epoch milliseconds. Cannot be in the future, and " +
                        "cannot land after `updated_at`. Refused, not ignored, if you are not the " +
                        "instance owner. Leaves the existing date if omitted.",
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
                "unwatch ON THEIR BEHALF; that is INSTANCE OWNER ONLY, since it decides " +
                "another person's inbox. Idempotent either way — watching what you already watch, " +
                "or unwatching what you do not, changes nothing and still reports where it left " +
                "things. get_issue lists an issue's current watchers.",
            inputSchema = schema(
                "issue_id" to integerProp("The issue to watch or unwatch."),
                "watching" to boolProp("true to watch — the default — or false to stop watching."),
                "user" to stringProp(
                    "Whose watch to change: a display name exactly as the board shows it, or the " +
                        "email address on their account. Omit it to change your own. Naming " +
                        "somebody else is INSTANCE OWNER ONLY — refused, not ignored, if you are " +
                        "not the owner — and an ambiguous name is refused rather than guessed at; " +
                        "use the email address to settle it.",
                ),
                required = listOf("issue_id"),
            ),
        ),
        McpTool(
            name = "link_issues",
            description = "Link one issue to another under one of the project's relation kinds — " +
                "\"Blocked by\", \"Duplicate of\", whatever this project calls them. get_board's " +
                "`relationKinds` is the list; a project with none there links nothing, and there is " +
                "nothing to add.\n\n" +
                "READ THE DIRECTION CAREFULLY. The link is stated FROM `issue_id` and the kind's " +
                "own name is the sentence about it: `issue_id` 4, `to_issue_id` 9, `relation` " +
                "\"Blocked by\" says \"issue 4 is blocked by issue 9\". Naming the opposite label " +
                "instead — \"Blocks\", where that is the kind's inverse — is refused rather than " +
                "quietly reversed, and the refusal tells you the call to make instead, which is the " +
                "same one with the two issues swapped.\n\n" +
                "One link is stored once and read from both ends, so there is no second call to " +
                "make from the other issue and doing it anyway is refused as a duplicate. Both " +
                "issues must be in the same project. The right this needs is edit on `issue_id` " +
                "alone; the far issue's is deliberately not asked, exactly as it is not for an epic.",
            inputSchema = schema(
                "issue_id" to integerProp(
                    "The issue the link is stated FROM — the one the `relation` label describes.",
                ),
                "to_issue_id" to integerProp(
                    "The issue at the other end, by its `id` from get_board or get_issue (not the " +
                        "FOO-123 key). It must be a published issue in the same project.",
                ),
                "relation" to stringProp(
                    "A relation kind's name, exactly as get_board's `relationKinds` gives its " +
                        "`name`. Case-insensitive. A kind that reads the same in both directions " +
                        "has only that one name; a kind with an `inverseName` has two, and this " +
                        "argument takes the first of them — see the note on direction above.",
                ),
                "agent_name" to stringProp(AGENT_NAME_PROP_DESCRIPTION),
                required = listOf("issue_id", "to_issue_id", "relation"),
            ),
        ),
        McpTool(
            name = "unlink_issues",
            description = "Remove one link between two issues. Takes the `relationId` from " +
                "get_issue's `relations` — not the pair and the kind, because a link is stored " +
                "once and naming the pair would have to say which direction it meant.\n\n" +
                "`issue_id` is either end: the link is readable and removable from both, and a " +
                "`relation_id` belonging to some other pair of issues is refused rather than " +
                "silently removing something else. Needs edit rights on `issue_id`.",
            inputSchema = schema(
                "issue_id" to integerProp("Either issue the link touches — usually the one you are looking at."),
                "relation_id" to integerProp("The link to remove: `relationId`, from get_issue's `relations`."),
                "agent_name" to stringProp(AGENT_NAME_PROP_DESCRIPTION),
                required = listOf("issue_id", "relation_id"),
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
                    "INSTANCE OWNER ONLY, for backfilling. When this comment was written, in epoch " +
                        "milliseconds. Cannot be in the future. Refused, not ignored, if you are " +
                        "not the instance owner. Defaults to now.",
                ),
                required = listOf("issue_id", "body"),
            ),
        ),
        McpTool(
            name = "update_comment",
            description = "Change an existing comment. Every field is optional; omit one to " +
                "leave it as it is. You may edit a comment that is your own, or any comment if " +
                "you are acting as an instance administrator — the same rule the web app applies.",
            inputSchema = schema(
                "comment_id" to integerProp("The comment to change."),
                "body" to stringProp("A new body, in markdown. Replaces the old one entirely."),
                "agent_name" to stringProp(
                    "Set or change the agent label on this comment — your own name as the agent " +
                        "making the edit. Omitting it leaves whatever is already there. Not " +
                        "restricted to the instance owner, and it does not change who the comment " +
                        "belongs to — it only labels the row. To REMOVE the badge, the instance " +
                        "owner sends an empty string; anyone else who tries is refused.",
                ),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(
                    "INSTANCE OWNER ONLY. When this comment should claim to have been written, in epoch " +
                        "milliseconds. Cannot be in the future. Refused, not ignored, if you are " +
                        "not the instance owner. Leaves the existing date if omitted.",
                ),
                required = listOf("comment_id"),
            ),
        ),
        McpTool(
            name = "update_history_event",
            description = "INSTANCE OWNER ONLY. Correct who made one history entry, or when — and " +
                "nothing else about it. Each entry in an issue's `history` (from get_issue) carries " +
                "an `id`; pass that here to re-attribute that one entry.\n\n" +
                "This exists for one job. History imported from another tracker lands under a " +
                "placeholder author, and when that person later gets a real Lunicle account their " +
                "CREATED, STATUS_CHANGED and other entries can be moved onto it rather than naming a " +
                "stranger forever. WHAT happened — the `kind` of change and the value or values it " +
                "carries — cannot be edited here or by any tool; only the entry's author, its date " +
                "and its agent label can. A history whose events you could re-word would not be one. " +
                "Refused, not ignored, if you are not the instance owner.",
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
                        "(Clearing is the instance owner's, but this whole tool already is.)",
                ),
                "created_at" to integerProp(
                    "INSTANCE OWNER ONLY. When this entry should claim to have happened, in epoch " +
                        "milliseconds. Cannot be in the future. The history is ordered by entry, not " +
                        "by date, so re-dating an entry does not move it. Refused, not ignored, if " +
                        "you are not the instance owner. Leaves the existing date if omitted.",
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
                    "Attach to this forum post's body. RETIRED (LNL-190), like the rest of the " +
                        "forum targets, and refused for everybody. Give exactly one target.",
                ),
                "forum_comment_id" to integerProp(
                    "Attach to this forum comment. RETIRED (LNL-190) and refused. Give exactly one target.",
                ),
                "filename" to stringProp(
                    "The name to store it under, as it should appear to someone downloading it. " +
                        "Fixed now: the upload cannot rename it.",
                ),
                "author" to stringProp(AUTHOR_PROP_DESCRIPTION),
                "author_external" to stringProp(AUTHOR_EXTERNAL_PROP_DESCRIPTION),
                "created_at" to integerProp(
                    "INSTANCE OWNER ONLY, for backfilling. When this file was uploaded, in epoch " +
                        "milliseconds. Cannot be in the future. Refused, not ignored, if you are " +
                        "not the instance owner. Defaults to whenever the bytes land.",
                ),
                required = listOf("filename"),
            ),
        ),
        McpTool(
            name = "delete_attachment",
            description = "INSTANCE OWNER ONLY. Delete one stored attachment — its row and the " +
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
                "A PROJECT-MAINTAINER action: you can do this only in a project you maintain, " +
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
            description = "Point a project's board at a sprint, or at none. A PROJECT-MAINTAINER " +
                "action, in a project you maintain.\n\n" +
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
            description = "Finish a sprint and roll its unfinished work forward. A PROJECT-MAINTAINER " +
                "action, in a project you maintain. Permanent: a completed sprint cannot be " +
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
        // ── The project's own structure ──────────────────────────────────────
        //
        // Five tools for eight vocabularies rather than four tools apiece; see the
        // section banner above `vocabularyScope` for the whole argument and what it
        // costs. Offered to everybody and refused per project, exactly as the sprint
        // tools are and for the same reason: the rung is a fact about one project, so
        // hiding them would hide them from a caller who administers a different one.

        McpTool(
            name = "list_vocabulary",
            description = "Read what a project is made of, WITH IDS: its statuses, priorities, " +
                "resolutions, labels, components, sprints, versions and relation kinds, each row " +
                "with its `id`, its `name`, its `position` in the order and its `usageCount`.\n\n" +
                "get_board names the same vocabulary and is the right call for ordinary work — you " +
                "address a status by name everywhere else. This is the call to make before " +
                "CHANGING any of it, because rename, reorder and delete all take ids: a name is not " +
                "a stable handle for a row you are about to rename, and a reorder wants the whole " +
                "list anyway.\n\n" +
                "Per-kind extras come back only on the kinds that have them: `requiresResolution` on " +
                "a status, `isDone` on a resolution, `inverseName` and `marksBlocked` on a relation " +
                "kind, `completedAt` on a finished sprint. `usageCount` counts published issues " +
                "holding the row — for a status or a priority a non-zero count means a delete will " +
                "be refused, and for a label it means that many issues would simply lose it.\n\n" +
                "Reading needs no more than being able to see the project. Changing any of it does; " +
                "see add_vocabulary.",
            inputSchema = schema(
                "project_id" to integerProp("The project's id, from list_projects."),
                "project_name" to stringProp("The project's name, if you do not have its id."),
                "kind" to stringProp(
                    VOCABULARY_KIND_PROP_DESCRIPTION + " Omit it to get all eight, which is one call " +
                        "and usually what you want.",
                ),
            ),
        ),
        McpTool(
            name = "add_vocabulary",
            description = "Add one row to one of a project's vocabularies — a new status, priority, " +
                "resolution, label, component, sprint, version or relation kind. It lands at the END " +
                "of that kind's order, always: putting a new column at the front would silently " +
                "change where every future issue lands, so \"add\" would quietly be \"change the " +
                "default\". Move it afterwards with reorder_vocabulary, which says what it does.\n\n" +
                "A PROJECT-ADMINISTRATOR action for the six kinds that define what the board is, and " +
                "a PROJECT-MAINTAINER one for sprints and versions — the same split the web app " +
                "applies. A refusal is about the rung the account you are acting as holds on THIS " +
                "project, and retrying will not change it.\n\n" +
                "The name must be unique within its kind in that project, compared case-insensitively " +
                "— a clash is refused with the name it collided with. A new status never demands a " +
                "resolution and a new relation kind never marks blocking unless you say so here: both " +
                "flags decide how every card on everybody's board reads, so they are armed " +
                "deliberately rather than by default. A sprint is created NOT active; start it with " +
                "set_active_sprint.",
            inputSchema = schema(
                "project_id" to integerProp("Which project to add it to."),
                "project_name" to stringProp("The project's name, if you do not have its id."),
                "kind" to stringProp(VOCABULARY_KIND_PROP_DESCRIPTION),
                "name" to stringProp(
                    "What the new row is called, at most $MAX_MCP_VOCABULARY_NAME_LENGTH characters. " +
                        "For a relation kind this is the FROM-side label — the sentence about the " +
                        "issue the link is stated from, so \"Blocked by\" rather than \"Blocks\".",
                ),
                "inverse_name" to stringProp(INVERSE_NAME_PROP_DESCRIPTION),
                "marks_blocked" to boolProp(MARKS_BLOCKED_PROP_DESCRIPTION),
                required = listOf("kind", "name"),
            ),
        ),
        McpTool(
            name = "rename_vocabulary",
            description = "Rename one vocabulary row, or change one of its flags, addressing it by " +
                "the `id` from list_vocabulary. Renaming rewrites nothing else: every issue points " +
                "at the row by id, so a renamed status keeps its issues and a renamed relation kind " +
                "keeps its links.\n\n" +
                "EVERY FIELD IS OPTIONAL AND OMITTING ONE LEAVES IT ALONE — including the flags, " +
                "which is worth saying because the underlying write sets them all at once. Omit " +
                "`name` and the row keeps its name, so this is also how you flip a flag without " +
                "touching what the row is called. A flag that does not belong to the kind you named " +
                "is ignored rather than refused; a priority has nothing to put in " +
                "`requires_resolution`.\n\n" +
                "Same rungs as add_vocabulary, and the same refusal when you do not hold them.",
            inputSchema = schema(
                "project_id" to integerProp("The project the row belongs to."),
                "project_name" to stringProp("The project's name, if you do not have its id."),
                "kind" to stringProp(VOCABULARY_KIND_PROP_DESCRIPTION),
                "id" to integerProp(
                    "The row to change — its `id` from list_vocabulary. An id belonging to another " +
                        "project is refused, not applied there.",
                ),
                "name" to stringProp(
                    "A new name, still unique within its kind here. Omit it to leave the name as it " +
                        "is and change only a flag.",
                ),
                "requires_resolution" to boolProp(
                    "STATUSES ONLY: whether landing in this column demands a resolution saying why " +
                        "the issue is closed. Omit it to leave the flag as it is. Turning it ON does " +
                        "not go back and demand one from the issues already there; turning it OFF " +
                        "does not strip the resolutions they carry.",
                ),
                "is_done" to boolProp(
                    "RESOLUTIONS ONLY: whether this resolution means the work was actually done, as " +
                        "opposed to \"Won't fix\" or \"Duplicate\". Some projects require a fixed " +
                        "version when closing under a done resolution. Omit it to leave it as it is.",
                ),
                "inverse_name" to stringProp(
                    INVERSE_NAME_PROP_DESCRIPTION + " Omit it to leave the kind's opposite label as " +
                        "it is; send it as null to make the kind read the SAME in both directions.",
                ),
                "marks_blocked" to boolProp(
                    MARKS_BLOCKED_PROP_DESCRIPTION + " Omit it to leave the flag as it is.",
                ),
                required = listOf("kind", "id"),
            ),
        ),
        McpTool(
            name = "reorder_vocabulary",
            description = "Put one of a project's vocabularies in a given order, first to last. For " +
                "statuses that is the order of the board's COLUMNS, and the first of them is where a " +
                "new issue lands; for priorities it is the scale, high to low.\n\n" +
                "`ids` must name EXACTLY that kind's rows in that project — all of them, each once, " +
                "and none from anywhere else. Anything short of that is refused rather than partly " +
                "applied, because a half-applied reorder leaves two rows sharing a position and the " +
                "board ordering them arbitrarily. So read the current order from list_vocabulary and " +
                "send it back rearranged.\n\n" +
                "Same rungs as add_vocabulary.",
            inputSchema = schema(
                "project_id" to integerProp("Whose vocabulary is being ordered."),
                "project_name" to stringProp("The project's name, if you do not have its id."),
                "kind" to stringProp(VOCABULARY_KIND_PROP_DESCRIPTION),
                "ids" to buildJsonObject {
                    put("type", "array")
                    putJsonObject("items") { put("type", "integer") }
                    put(
                        "description",
                        "Every row id of that kind in that project, in the order you want them. " +
                            "From list_vocabulary.",
                    )
                },
                required = listOf("kind", "ids"),
            ),
        ),
        McpTool(
            name = "delete_vocabulary",
            description = "Delete one vocabulary row, permanently. There is no trash, and this is " +
                "the one write here whose consequences reach issues you are not looking at.\n\n" +
                "What it will REFUSE, so you do not have to guess: a status, priority or resolution " +
                "that published issues still hold — you are told how many, and moving them is the " +
                "fix — and the last status or the last priority a project has, because a project " +
                "with none of either cannot take an issue and cannot be repaired by filing one about " +
                "it.\n\n" +
                "What it will NOT refuse, and you should think about first: deleting a label or a " +
                "component simply removes it from every issue that had it. Deleting a version or a " +
                "sprint un-schedules the issues that named it. Deleting a RELATION KIND deletes " +
                "every link that used it — list_vocabulary's `usageCount` says how many, and after " +
                "the fact nothing records what was linked. CONFIRM WITH THE PERSON FIRST unless they " +
                "have already named the exact row they want gone.\n\n" +
                "Same rungs as add_vocabulary.",
            inputSchema = schema(
                "project_id" to integerProp("The project the row belongs to."),
                "project_name" to stringProp("The project's name, if you do not have its id."),
                "kind" to stringProp(VOCABULARY_KIND_PROP_DESCRIPTION),
                "id" to integerProp("The row to delete — its `id` from list_vocabulary."),
                required = listOf("kind", "id"),
            ),
        ),
        McpTool(
            name = "set_estimate_mode",
            description = "Decide whether a project estimates its issues, and in what unit. A " +
                "PROJECT-ADMINISTRATOR action — it sits with the vocabulary rather than with the " +
                "sprints, because whether a team estimates at all is a decision about what the board " +
                "is.\n\n" +
                "Three modes: \"none\" (the default — no estimate field anywhere, and any estimate " +
                "sent to create_issue or update_issue is refused), \"time\" (whole MINUTES, rendered " +
                "as days, hours and minutes with a day fixed at eight hours) and \"points\" (whole " +
                "points, on whatever scale the team means by them).\n\n" +
                "Switching mode REINTERPRETS NOTHING. Every estimate already stored carries its own " +
                "unit, so a project moved from points to time keeps reading its old rows as points; " +
                "the mode governs only what may be written next. Switching to \"none\" likewise " +
                "hides the field rather than erasing what is there. get_board reports the mode in " +
                "force as `estimateMode`.",
            inputSchema = schema(
                "project_id" to integerProp("Whose estimate setting is being changed."),
                "project_name" to stringProp("The project's name, if you do not have its id."),
                "mode" to stringProp(
                    "\"none\", \"time\" or \"points\", exactly. Anything else is refused with the " +
                        "three listed rather than folded to \"none\" — a typo here would otherwise " +
                        "switch a live project's estimates off and report that as success.",
                ),
                required = listOf("mode"),
            ),
        ),

        McpTool(
            name = "delete_issue",
            description = "Delete an issue, permanently. Its comments, its attachments and its " +
                "history go with it — this is not a status change and there is no trash to " +
                "recover it from.\n\n" +
                "You may delete an issue that is your own, or any issue in a project you " +
                "administer — the same rule the web app's Delete button applies. It is STRICTER " +
                "than the rule for editing one: a maintainer may rewrite anybody's issue here " +
                "and still not make it stop existing. Nothing here is deletable that you could " +
                "not already have emptied of every word it said.\n\n" +
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
                "an instance administrator — the same rule that governs editing one. Note this " +
                "is NARROWER than delete_issue: a project administrator may delete anybody's " +
                "issue there, and still not a word anybody wrote here.\n\n" +
                "CONFIRM WITH THE PERSON FIRST unless they have already asked for this exact " +
                "comment to go. If the point is to correct something rather than erase it, " +
                "update_comment keeps the thread readable.",
            inputSchema = schema(
                "comment_id" to integerProp("The comment to delete."),
                required = listOf("comment_id"),
            ),
        ),

        // ── Forums, retired (LNL-190) ────────────────────────────────────────
        //
        // Thirteen tools, filtered out of tools/list for everybody — see
        // toolsFor, canUseForumTools, and this file's fourth exception.

        McpTool(
            name = "list_forums",
            description = "RETIRED (LNL-190). List a project's discussion forums, in the order " +
                "its administrator put them. Start here for anything to do with the Discussion " +
                "tab: a forum's id is what list_forum_posts takes.",
            inputSchema = schema(
                "project_id" to integerProp("The project's id, from list_projects."),
                "project_name" to stringProp("The project's name, if you do not have its id."),
            ),
        ),
        McpTool(
            name = "create_forum",
            description = "RETIRED (LNL-190). Make a new forum in a project. It lands at the end " +
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
            description = "RETIRED (LNL-190). Rename a forum or change its description. Omit a " +
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
            description = "RETIRED (LNL-190). Delete a forum AND EVERYTHING IN IT: every post, " +
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
            description = "RETIRED (LNL-190). Put a project's forums in a given order, 0 first. " +
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
            description = "RETIRED (LNL-190). A forum's posts, newest first, with each one's " +
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
            description = "RETIRED (LNL-190). One post in full — its whole markdown body — with " +
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
            description = "RETIRED (LNL-190). Watch a forum, or stop — the notification bell on " +
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
            description = "RETIRED (LNL-190). Watch a single post's thread, or stop. A watcher is " +
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
            description = "RETIRED (LNL-190). Start a new post in a forum. Written and published " +
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
            description = "RETIRED (LNL-190). Rewrite an existing post: its title, its body, and " +
                "— because this is the import-repair path — who is recorded as having written it " +
                "and when. Omit a field to leave it as it is.\n\n" +
                "This is also how an uploaded image gets into a post: start_attachment_upload " +
                "hands you a url, and putting it into the body is this call.\n\n" +
                "Nothing is notified by an update, and nothing records that it happened. The web " +
                "app has no edit button for a post at all, so an edit here is invisible to " +
                "everyone — which is exactly why it was kept to a narrow audience and is for " +
                "correcting imports rather than for changing what somebody said.",
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
                    "INSTANCE OWNER ONLY. When this post should claim to have been written, in epoch " +
                        "milliseconds. Cannot be in the future. Leaves the existing date if omitted.",
                ),
                required = listOf("post_id"),
            ),
        ),
        McpTool(
            name = "delete_forum_post",
            description = "RETIRED (LNL-190). Delete a post, every comment on it, and every file " +
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
            description = "RETIRED (LNL-190). Comment on a forum post. Comments are flat — there " +
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
            description = "RETIRED (LNL-190). Rewrite a comment on a forum post — its body, and " +
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
                    "INSTANCE OWNER ONLY. When this comment should claim to have been written, in " +
                        "epoch milliseconds. Cannot be in the future. Leaves the existing date if " +
                        "omitted.",
                ),
                required = listOf("comment_id"),
            ),
        ),
        McpTool(
            name = "delete_forum_comment",
            description = "RETIRED (LNL-190). Delete one comment on a forum post, and any files " +
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
            // Whole-tool owner-only, exactly as send_email: editing history is an
            // act nobody but the instance owner can perform, so everybody else is
            // not offered it rather than shown a tool that will only refuse them.
            // See updateHistoryEvent, and the ordinary-vs-owner tool list
            // assertion in McpSendEmailTest.
            "update_history_event" -> deps.access.canAttributeWrites(user)
            // Deleting a stored attachment out of band is the owner's cleanup —
            // the web gives no one a standalone attachment delete, so this is
            // offered only to the one account answerable for the deployment. See
            // AccessControl.canDeleteAttachment and deleteAttachment.
            "delete_attachment" -> deps.access.canDeleteAttachment(user)
            // Offered to nobody, the instance owner included: discussions are
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
        "link_issues" -> linkIssues(user, arguments)
        "unlink_issues" -> unlinkIssues(user, arguments)
        "list_vocabulary" -> listVocabulary(user, arguments)
        "add_vocabulary" -> addVocabulary(user, arguments)
        "rename_vocabulary" -> renameVocabulary(user, arguments)
        "reorder_vocabulary" -> reorderVocabulary(user, arguments)
        "delete_vocabulary" -> deleteVocabulary(user, arguments)
        "set_estimate_mode" -> setEstimateMode(user, arguments)
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
        // Filtered, in the same spirit GET /api/projects is filtered: a project out of
        // reach does not appear — it is not returned and hidden, because there is no UI
        // here to do the hiding and there never should have been.
        //
        // The filter is the agent floor and NOT canReadProject, so this list is narrower
        // than the project rail in the person's own browser: boards they can only look at
        // are simply not here. That is the point rather than an inconsistency — see
        // AccessControl.canAgentReachProject — and it is why the server instructions say
        // so out loud. An agent that finds fewer projects than its user expects has to be
        // able to explain why without guessing.
        val visible = deps.projects.selectAll().filter { deps.access.canAgentReachProject(user, it) }
        return ok(
            buildJsonArray {
                visible.forEach { project ->
                    add(
                        buildJsonObject {
                            put("id", project.id)
                            put("name", project.name)
                            put("keyPrefix", project.namePrefix)
                            // What this agent's user holds here, as the rung key
                            // (LNL-194). It replaces `isPublic`/`visibleToAllSignedIn`,
                            // which were two booleans about the project that had read
                            // false since LNL-191 — an agent is better told what it may
                            // do than what the world may see. Never null: the filter
                            // above is what proved a rung exists.
                            put("yourRole", deps.access.effectiveRole(user, project.id)?.key ?: "")
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
        // Every issue on the board, before the column filter — kept because the
        // blocked projection below is a fact about the whole project and cannot be
        // computed from a slice of it. See `blockersByIssue`.
        val allIssues = deps.issues.forProject(project.id)
        // Narrowed here rather than at the JSON, so the per-issue lookups below —
        // authors, assignees, and one canEditIssue apiece — are paid for the column
        // asked about instead of the whole board.
        val issues = allIssues.filter { statusFilter == null || it.statusId == statusFilter.id }
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

        // ── The blocked projection, computed exactly as the web board computes it ──
        //
        // A card is blocked when it is on the FROM side of a link whose kind marks
        // blocking and the issue on the TO side is still open. Both halves are read
        // over the WHOLE project rather than over the filtered `issues`, and that is
        // the one thing this must not get wrong: a blocker sitting in a column the
        // caller filtered out is absent from the response, so deriving this from what
        // is returned would report a blocked card as clear — silently, and in the
        // direction that loses information. `boardState`'s comment says the same at
        // more length; this is the second reader of that rule and not a second copy of
        // it, since both go through the same two stores.
        //
        // "Open" is read off the STATUS's requiresResolution and never off a
        // resolution's isDone: any closure stops the blocking, "Won't fix" included,
        // because a blocker nobody will ever do is not blocking anything.
        val relationKinds = deps.issueRelationKinds.forProject(project.id)
        val blockingKindIds = relationKinds.filter { it.marksBlocked }.map { it.id }.toSet()
        val closingStatusIds = statuses.filter { it.requiresResolution }.map { it.id }.toSet()
        val openById = allIssues.associate { it.id to (it.statusId !in closingStatusIds) }
        val numberById = allIssues.associate { it.id to it.number }
        // Issue id → the KEYS of the open issues blocking it. Keys rather than the bare
        // numbers the web board carries, because an agent addresses an issue as
        // FOO-123 or by id and has no prefix in hand to build one from. Empty for every
        // card on a project that has never made a blocking link, which is most of them,
        // and the whole map is skipped when no kind marks blocking at all.
        val blockersByIssue: Map<Long, List<String>> =
            if (blockingKindIds.isEmpty()) {
                emptyMap()
            } else {
                deps.issueRelations.forProject(project.id)
                    .filter { it.kindId in blockingKindIds && openById[it.toIssueId] == true }
                    .groupBy({ it.fromIssueId }, { numberById[it.toIssueId] })
                    .mapValues { (_, numbers) ->
                        numbers.filterNotNull().sorted().map { "${project.namePrefix}-$it" }
                    }
                    .filterValues { it.isNotEmpty() }
            }

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
                // Absent — not empty — when the project links nothing, joining
                // `sprints` and `versions` in reading as "this board has never heard
                // of the idea" rather than "it has an empty list of them" (LNL-215).
                // An agent that sees nothing here has nothing to reason about and
                // link_issues has nothing to accept.
                if (relationKinds.isNotEmpty()) {
                    putJsonArray("relationKinds") {
                        relationKinds.forEach { kind ->
                            add(
                                buildJsonObject {
                                    // The from-side label, and the one link_issues takes.
                                    put("name", kind.name)
                                    // Absent when the kind reads the same in both
                                    // directions, which is the whole encoding of
                                    // symmetry — there is no isSymmetric flag that
                                    // could disagree with a stale second name. See
                                    // IssueRelationKindRecord.inverseName.
                                    kind.inverseName?.let { put("inverseName", it) }
                                    // Always present as a bool, unlike the name above:
                                    // it is the field that decides whether `isBlocked`
                                    // can ever be true on this board, and absent-when-
                                    // false would read as "unknown" rather than "no".
                                    put("marksBlocked", kind.marksBlocked)
                                },
                            )
                        }
                    }
                }
                // Always present, alone among the optional features here, and
                // deliberately: "none" is an answer an agent needs rather than a
                // silence it can ignore. A missing key would leave "this project does
                // not estimate" and "this server is older than estimates" reading
                // identically, and the first of those is exactly what an agent asked to
                // estimate something must be told before it tries and is refused.
                put("estimateMode", project.estimateMode.key)
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
                                // Present only when it is true, and it can only be
                                // true beside an assignee — the repository forces it
                                // false otherwise, so this never appears on a card with
                                // nobody on it (LNL-215). Absent-when-false rather than
                                // a bool on every card, because the flag is rare and an
                                // agent reading a board should have to notice it rather
                                // than skip past it forty times.
                                if (issue.assigneeIsAgent) put("assigneeIsAgent", true)
                                // Present only on issues an agent filed. Says a
                                // human did not type this — see resolveAgentName.
                                issue.agentName?.let { put("agentName", it) }
                                // Absent when unestimated, like every optional field
                                // here. The unit rides WITH the amount rather than being
                                // read off the project's mode, because an estimate
                                // written before the mode changed still means what it
                                // said — see EstimateUnit.
                                issue.estimate?.let { estimate ->
                                    putJsonObject("estimate") { putEstimate(estimate) }
                                }
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
                                // Both absent on a card nothing is holding up, which is
                                // almost every card. Two fields rather than one because
                                // they answer two questions an agent asks separately —
                                // "may I start this" and "on what" — and because
                                // `isBlocked` false everywhere would be the noisiest key
                                // on the response. See the blocked projection above for
                                // why this cannot be derived from the issues returned.
                                blockersByIssue[issue.id]?.let { blockers ->
                                    put("isBlocked", true)
                                    putJsonArray("blockedBy") { blockers.forEach { add(it) } }
                                }
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
        // ── This issue's links, resolved to THIS issue's side (LNL-215) ──────────
        //
        // One stored row per link, read from both ends and turned into a sentence about
        // the issue that was asked for: the same row reads "Blocked by FOO-9" here and
        // "Blocks FOO-4" over there. Which end the reader is on is decided by comparing
        // against `fromIssueId`, once, here — so the agent is handed the wording rather
        // than the raw row and has no direction left to get wrong. That is the same
        // resolution `buildIssueDetail` does for the web issue window, over the same two
        // stores; what differs is only the shape it is written into.
        //
        // Assembled before the JSON, like everything else above, because
        // buildJsonObject's lambda is not a coroutine body. Skipped entirely for a
        // draft, whose links cannot exist — IssueRepository.addRelation refuses to make
        // one — so this is a query guaranteed to come back empty on a half-written
        // issue.
        val relationRows = if (issue.isDraft) emptyList() else deps.issueRelations.forIssue(issue.id)
        val relationKindsById = deps.issueRelationKinds.forProject(issue.projectId).associateBy { it.id }
        val relations = relationRows.mapNotNull { relation ->
            // A row whose kind or whose far issue cannot be resolved is dropped rather
            // than reported wordless, on buildIssueDetail's reasoning: it should be
            // unreachable — deleting a kind takes its links with it — and a link with no
            // word for what it is would be a row saying nothing.
            val kind = relationKindsById[relation.kindId] ?: return@mapNotNull null
            val other = deps.issues.findById(relation.otherThan(issue.id)) ?: return@mapNotNull null
            buildJsonObject {
                // What unlink_issues takes. The relation's own id, never the far
                // issue's: a pair can be linked under two different kinds at once, so
                // "the link to FOO-9" is not a unique thing to remove.
                put("relationId", relation.id)
                put("label", kind.labelFor(isFromSide = relation.fromIssueId == issue.id))
                // The kind's own from-side name beside this side's wording, because the
                // two differ exactly when this issue is on the TO side of a kind that is
                // not symmetric — and an agent holding only "Blocks" would then have no
                // way to name the kind back to link_issues, which takes "Blocked by".
                put("kind", kind.name)
                put("id", other.id)
                put("key", "${project.namePrefix}-${other.number}")
                put("title", other.title)
            }
        }

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
                // Present only when true, and only ever beside an assignee — see
                // getBoard's copy, whose reasoning this is (LNL-215).
                if (issue.assigneeIsAgent) put("assigneeIsAgent", true)
                // Absent when unestimated. The unit is the issue's own, not the
                // project's current mode; see EstimateUnit.
                issue.estimate?.let { estimate -> putJsonObject("estimate") { putEstimate(estimate) } }
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
                // Absent when this issue is linked to nothing, as `children` and
                // `watchers` are — an empty key would be a line of noise on the great
                // majority of issues, which are linked to nothing at all.
                if (relations.isNotEmpty()) {
                    putJsonArray("relations") { relations.forEach { add(it) } }
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
        // writes is the ordinary case, not the owner-only act of writing as someone
        // else. See [resolveAgentName].
        val agentName = resolveAgentName(arguments)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }

        val title = arguments.string("title") ?: return refuse("An issue needs a title.")
        if (title.length > MAX_MCP_TITLE_LENGTH) return refuse("That title is too long.")
        // The same cap the HTTP route applies (LUS-30). An agent is authenticated and
        // above the Contributor floor, so this is a bound on the volume rather than a
        // defence against a stranger — but a field with a cap on one path and none on
        // the other has no cap.
        val description = arguments.string("description").orEmpty()
        tooLongMessage("description", description)?.let { return refuse(it) }

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
        // Null for absent, which is this flag's spelling of "say nothing" rather than
        // "false" — see resolveAssigneeIsAgent, and IssueRepository.save, which is
        // where the whole lifecycle of the flag is decided. On a create the repository
        // resolves an unstated flag to false, and a stated one on an unassigned issue
        // to false as well, so nothing here has to know those rules.
        val assigneeIsAgent = resolveAssigneeIsAgent(arguments)
            .getOrElse { return refuse(it.message ?: "That agent-assignee flag cannot be used.") }

        // Through the route's own resolveEstimate, so a project's estimate mode governs
        // an agent's write exactly as it governs the editor's: a project on "none"
        // refuses an estimate rather than quietly dropping it, and a project on points
        // refuses minutes. current = null, because an issue created saying nothing about
        // an estimate has none.
        val estimate = resolveEstimateArgument(project.id, arguments, current = null)
            .getOrElse { return refuse(it.message ?: "That estimate cannot be used.") }

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
                description = description,
                statusId = status.id,
                priorityId = priority.id,
                resolutionId = resolutionId,
                assigneeId = assigneeId,
                assigneeIsAgent = assigneeIsAgent,
                sprintId = sprintId,
                // Full access, the same the web editor has (LNL-134): the agent may
                // set both versions by name, or leave them null.
                plannedVersionId = plannedVersionId,
                fixedVersionId = fixedVersionId,
                estimate = estimate,
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
                // only when the owner is backfilling — and there the whole point is
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

        // author / author_external / created_at are the owner-only levers, defaulted
        // to the issue's current values so an ordinary edit leaves attribution exactly
        // as it was. The same gate and the same function as create and update_comment —
        // this is where "the instance owner may change everything" lands for an issue.
        val attribution = resolveAttribution(
            user,
            arguments,
            default = Attribution(issue.author, issue.createdAt),
            removalOutcome = "the issue keeps its current author and date",
        ).getOrElse { return refuse(it.message ?: "That attribution cannot be used.") }
        val createdAt = attribution.at ?: issue.createdAt

        // updated_at is the issue's second stamp, owner-only like the rest but kept its
        // own check because it is validated against the RESULTING created_at, not the
        // old one: the owner may move both in a single call, and "edited before it
        // existed" has to be judged on where created_at ends up.
        val updatedAt = if (arguments.isPresent(UPDATED_AT_ARGUMENT)) {
            if (!deps.access.canAttributeWrites(user)) {
                return refuse(
                    "Only the instance owner can set $UPDATED_AT_ARGUMENT, and you are not acting as the owner. " +
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

        // Absent leaves the badge untouched; a real name sets it, neither of them the
        // owner's — self-labelling is the norm. An empty value clears it, which only
        // the owner may do. See resolveAgentNameEdit.
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
        // The one deliberate exception is the owner naming an `author` — that is the
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
        val description = arguments.string("description") ?: issue.description
        tooLongMessage("description", description)?.let { return refuse(it) }

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
        // The one field here NOT defaulted to the issue's current value, and the
        // exception is the point. Every neighbour above passes the stored value back
        // when the argument is absent, because `publish` overwrites its column
        // unconditionally — but doing that with this flag would drag it across a
        // handover, so that an agent fixing a typo on an issue reassigned to somebody
        // else would silently re-mark the new person's work as their robot's. Null
        // means "not editing this", and IssueRepository.save decides: it keeps the flag
        // when the assignee is unchanged and clears it when they are not. See its
        // `resolvedAgentFlag`.
        val assigneeIsAgent = resolveAssigneeIsAgent(arguments)
            .getOrElse { return refuse(it.message ?: "That agent-assignee flag cannot be used.") }

        // Current-as-default, and this line is load-bearing: `publish` writes both
        // estimate columns on every save, so an edit that said nothing about an
        // estimate and passed nothing here would clear it. Sending both arguments as
        // null is how an agent actually clears one. See resolveEstimateArgument.
        val estimate = resolveEstimateArgument(issue.projectId, arguments, current = issue.estimate)
            .getOrElse { return refuse(it.message ?: "That estimate cannot be used.") }

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
            description = description,
            statusId = statusId,
            priorityId = priorityId,
            resolutionId = resolutionId,
            assigneeId = assigneeId,
            assigneeIsAgent = assigneeIsAgent,
            sprintId = sprintId,
            // Full access, the same the web editor has (LNL-134): set either version
            // by name, null to clear, or omit to keep — resolveVersion above.
            plannedVersionId = plannedVersionId,
            fixedVersionId = fixedVersionId,
            estimate = estimate,
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
        // Written after the content save, gated above: owner-only for the author and
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
     * Watch an issue, or stop — for yourself, or (as the owner) for somebody else.
     *
     * ── Why the gate is "readable", not "editable" ────────────────────────────
     *
     * This is not a write to the issue; it is the caller managing an inbox. The web
     * route this mirrors (`POST /api/issues/{id}/notification`) asks only that the
     * caller be signed in and the issue readable, on the reasoning that anyone who
     * may *see* an issue may ask to hear about it. So there is no `canEditIssue`
     * here — a watcher who cannot edit is the ordinary case, not an anomaly.
     *
     * ── The two subjects, and why one of them is the owner's ──────────────────
     *
     * Absent `user` means the caller's own watch, which is the whole of the web
     * feature and needs no more right than reading. Naming somebody else is a
     * different act — a decision about *their* inbox — and the owner's alone for
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
        // is the owner's alone when it is not the caller.
        val subject: UserRecord = if (arguments.isPresent(USER_ARGUMENT)) {
            val named = arguments.string(USER_ARGUMENT)
                ?: return refuse("`$USER_ARGUMENT` was given as an empty name.")
            val id = resolveAuthor(named).getOrElse { return refuse(it.message ?: "No such person.") }
            if (id != user.id && !deps.access.canAttributeWrites(user)) {
                return refuse(
                    "Only the instance owner can change somebody else's watch, and you are not " +
                        "acting as the owner. Omit `$USER_ARGUMENT` to change your own. Nothing was written.",
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

    // ── Relations: what one issue says about another (LNL-215) ───────────────

    /**
     * Link two issues under one of the project's relation kinds.
     *
     * ── The permission, which is about ONE of the two issues ──────────────────
     *
     * [AccessControl.canEditIssue] on `issue_id` — the issue the link is stated *from*
     * — and nothing is asked about the far side. That is not this tool going soft: it
     * is the rule `POST /api/issues/{id}/relations` already applies, for the reason
     * that route and [IssueRepository.addRelation] both give at length. Belonging in a
     * relation is a fact somebody states about the issue they are looking at, and an
     * issue does not own who points at it. The consequence worth being sure about is
     * that somebody who may edit FOO-4 can make FOO-9 show "Blocks FOO-4" without
     * being able to edit FOO-9 — which is the same power the epic route already grants
     * and is bounded the same way, since both issues are in a project the caller can
     * already read and write in.
     *
     * ── Why naming the opposite label is refused rather than reversed ─────────
     *
     * A kind has two labels — "Blocked by" and "Blocks" — and only one of them is a
     * sentence about the from side. An agent that sends the inverse plainly means the
     * link the other way round, and swapping the two issues for it was the tempting
     * alternative. It is rejected because it would silently move the permission
     * check: after the swap the from issue is the *other* one, so the same fact would
     * be allowed or refused depending on which of its two names the agent happened to
     * use. A surface where "A blocks B" is refused and "B is blocked by A" succeeds,
     * for the same caller and the same two issues, is worse than one extra
     * round-trip — so this refuses, and the refusal spells out the call to make
     * instead.
     *
     * Everything else — same project, no self-link, no duplicate pair in either
     * direction, both published — is [IssueRepository.addRelation]'s, and comes back
     * as a [Result] failure whose message names which rule.
     */
    private suspend fun linkIssues(user: UserRecord, arguments: JsonObject): McpToolResult {
        val issue = readableIssue(user, arguments) ?: return noSuchIssue()
        if (!deps.access.canEditIssue(user, issue)) {
            return refuse("You cannot link this issue to others.")
        }
        val toIssueId = arguments.long(TO_ISSUE_ARGUMENT)
            ?: return refuse(
                "`$TO_ISSUE_ARGUMENT` must be an issue id — the `id` from get_board or get_issue, " +
                    "not the FOO-123 key.",
            )
        val named = arguments.string(RELATION_ARGUMENT)
            ?: return refuse("`$RELATION_ARGUMENT` must name one of this project's relation kinds.")
        val agentName = resolveAgentName(arguments)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }

        val kinds = deps.issueRelationKinds.forProject(issue.projectId)
        val wanted = named.trim()
        val kind = kinds.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
            ?: return refuseRelationKind(kinds, wanted, issue, toIssueId)

        deps.issueRepository
            .addRelation(issue, toIssueId, kind.id, user.asAuthor(), agentName)
            .getOrElse { return refuse(it.message ?: "That link is not allowed.") }

        // Read after the write rather than before it, so the sentence describes a link
        // that exists — and read at all because an answer naming FOO-4 and FOO-9 is one
        // the person can check, where "linked 412 to 507" is not.
        val here = issueKey(issue)
        val there = deps.issues.findById(toIssueId)?.let { issueKey(it) } ?: "issue $toIssueId"
        return ok(
            "$here is now \"${kind.labelFrom}\" $there. The link is stored once and read from both " +
                "ends: from $there it reads \"${kind.labelTo}\" $here.",
        )
    }

    /**
     * Unlink two issues, by the relation's own id.
     *
     * [linkIssues]' gate, on either end: the link is readable and removable from both
     * issues, and the repository proves the id actually touches the one named — so a
     * `relation_id` from another board removes nothing rather than being removable by
     * anybody who can edit any issue at all.
     */
    private suspend fun unlinkIssues(user: UserRecord, arguments: JsonObject): McpToolResult {
        val issue = readableIssue(user, arguments) ?: return noSuchIssue()
        if (!deps.access.canEditIssue(user, issue)) {
            return refuse("You cannot change this issue's links.")
        }
        val relationId = arguments.long(RELATION_ID_ARGUMENT)
            ?: return refuse(
                "`$RELATION_ID_ARGUMENT` must be a link id — the `relationId` from get_issue's " +
                    "`relations`, not an issue id.",
            )
        val agentName = resolveAgentName(arguments)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }

        // Read BEFORE the delete, for deleteForum's reason in miniature: afterwards
        // there is an id and nothing to say about it, and "removed link 88" is not
        // something anybody can check. The repository reads it again to validate; that
        // second read is the one that enforces, and this one only writes the sentence.
        val record = deps.issueRelations.findById(relationId)
        val farKey = record?.let { row ->
            deps.issues.findById(row.otherThan(issue.id))?.let { issueKey(it) }
        }

        deps.issueRepository
            .removeRelation(issue, relationId, user.asAuthor(), agentName)
            .getOrElse { return refuse(it.message ?: "That link could not be removed.") }

        val here = issueKey(issue)
        return ok(
            if (farKey != null) "$here and $farKey are no longer linked."
            else "Removed link $relationId from $here.",
        )
    }

    /**
     * "There is no relation kind called…", with the one case that is not a typo
     * separated out.
     *
     * An agent that named a kind's *inverse* label knows exactly what it wants and got
     * the direction backwards, which is a different mistake from misspelling a name —
     * so it gets a different sentence, carrying the call that would work. See
     * [linkIssues] for why this is a refusal rather than a swap.
     */
    private fun refuseRelationKind(
        kinds: List<IssueRelationKindRecord>,
        named: String,
        issue: IssueRecord,
        toIssueId: Long,
    ): McpToolResult {
        val reversed = kinds.firstOrNull { it.inverseName?.equals(named, ignoreCase = true) == true }
        if (reversed != null) {
            return refuse(
                "\"$named\" is the OTHER side of this project's \"${reversed.name}\" link, so this " +
                    "call states it backwards. A link is stored once, from one issue to the other, " +
                    "and \"${reversed.name}\" is the sentence about the issue it is stored from. To " +
                    "say what you meant, swap the two: link_issues with issue_id $toIssueId, " +
                    "to_issue_id ${issue.id}, relation \"${reversed.name}\". Nothing was written.",
            )
        }
        if (kinds.isEmpty()) {
            return refuse(
                "This project has no relation kinds, so its issues cannot be linked at all. A " +
                    "project administrator can add one with add_vocabulary. Nothing was written.",
            )
        }
        return refuse(
            "There is no relation kind called \"$named\" in this project. Available: " +
                kinds.joinToString(", ") { kind ->
                    if (kind.inverseName != null) "\"${kind.name}\" (the far side reads \"${kind.inverseName}\")"
                    else "\"${kind.name}\" (the same in both directions)"
                } + ". Name the first of each pair; see link_issues on direction.",
        )
    }

    /**
     * "FOO-123" for an issue, or its id when the project has gone.
     *
     * One read per call rather than a map, because the two callers name at most two
     * issues apiece — this is not `getBoard`, where the same lookup per card would be
     * the thing to avoid.
     */
    private suspend fun issueKey(issue: IssueRecord): String =
        deps.projects.findById(issue.projectId)?.let { "${it.namePrefix}-${issue.number}" }
            ?: "issue ${issue.id}"

    /**
     * Delete one stored attachment — its row and its bytes — by the id in its URL.
     *
     * The instance owner's alone (see [toolsFor] and
     * [AccessControl.canDeleteAttachment]): the web app gives nobody a standalone
     * attachment delete, so this is a new destructive power rather than a mirror of an
     * existing web right, and it is offered only to the account answerable for the
     * deployment, as the rest of the instance-wide ones are.
     *
     * [AttachmentRepository.delete] takes the row first and the file second, so the
     * failure this can leave is a collectable orphaned file, never a row pointing at
     * nothing — see its doc.
     */
    private suspend fun deleteAttachment(user: UserRecord, arguments: JsonObject): McpToolResult {
        if (!deps.access.canDeleteAttachment(user)) {
            return refuse(
                "Deleting a stored attachment is an instance-owner action, and you are not " +
                    "acting as the owner. Nothing was deleted.",
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
     * Readable first and maintainable second, exactly as SprintRoutes' `sprintScope`
     * (which was `adminSprintScope` until LNL-199 renamed it to match the rung it
     * actually asks for): an id the caller cannot see answers "no such project"
     * rather than confirming it exists by refusing on rights.
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

    // ── The project's own structure: vocabulary, and the estimate switch ──────
    //
    // ── One generic tool per verb, not one tool per kind ──────────────────────
    //
    // There are eight vocabularies and four verbs, so the surface here could have been
    // thirty-two tools — `add_status`, `add_priority`, `rename_label`, `delete_sprint`
    // — or five that take the kind as an argument. It is five, and the reasoning is
    // worth writing down because the other answer is the one that reads better in
    // isolation.
    //
    // **What was chosen.** `list_vocabulary`, `add_vocabulary`, `rename_vocabulary`,
    // `reorder_vocabulary` and `delete_vocabulary`, each taking `kind` as one of the
    // eight [VocabularyKind.key]s.
    //
    // **Why.** Because the thing underneath is already generic and has been since
    // LNL-28: [VocabularyKind] is one enum, [AccessControl.canEditVocabulary] is one
    // function that answers per kind, [VocabularyRepository] is one class whose every
    // method takes the kind, and the web app has one dialog. Thirty-two tools would be
    // thirty-two wrappers differing by a constant, over code that would immediately
    // fold them back into one call — and every one of them would be paid for on every
    // `tools/list` of every conversation, which is the cost this file already weighs in
    // [MCP_INSTRUCTIONS]. It would also be thirty-two places to forget a kind: the
    // ninth vocabulary would need four new tools, four new handlers and four new
    // dispatch lines, where here it needs a line in an enum that already exists.
    //
    // **The alternative, and it is not a straw man.** A per-kind surface has schemas
    // that cannot express nonsense. `add_status(name, requires_resolution)` never
    // offers `inverse_name`; `rename_priority` has no flags at all. Here, every flag is
    // on every call, and the tool descriptions have to say "STATUSES ONLY" and
    // "RELATION KINDS ONLY" in prose rather than in structure — which is weaker,
    // because prose is what a model skims. A tool named after the thing it changes is
    // also easier for a model to reach for: `add_status` is one hop from "add a
    // column", where `add_vocabulary` needs the extra step of knowing that a column is
    // a status is a vocabulary.
    //
    // **The trade-off accepted.** Precision at the schema, in exchange for a surface
    // that stays the size of the concept rather than the size of the enum, and for one
    // implementation of each verb rather than eight. The precision is bought back
    // where it can be: the per-kind extras are ignored for the kinds they do not belong
    // to rather than refused (the repository's rule, not a new one), an unknown kind is
    // refused with all eight listed, and every extra names its kind in the first three
    // words of its description. What is genuinely lost is that a model can send
    // `is_done` to a label and hear nothing back about it, which is the same silence
    // the web dialog produces for the same reason.
    //
    // Note the one overlap this leaves standing: `create_sprint` predates all of this
    // and does what `add_vocabulary(kind: "sprint")` now also does, through the same
    // repository and the same gate. It stays, because it is where the thing that is
    // *not* generic about a sprint gets said — that a new one is not activated, and
    // that starting it is a separate call.

    /**
     * The `kind` argument as a [VocabularyKind], or a refusal listing all eight.
     *
     * Matched against the enum's own [VocabularyKind.key] rather than `valueOf`, for
     * the reason `ProjectSettingsRoutes.vocabularyKind` gives: the keys are wire format
     * and the constant names are not, so this is one of the two places they are allowed
     * to be told apart. The agent reads keys off this file's descriptions and off the
     * URLs nothing here exposes; either way it is the key that is public.
     */
    private fun resolveVocabularyKind(arguments: JsonObject): Result<VocabularyKind> {
        val named = arguments.string(KIND_ARGUMENT)?.trim()
            ?: return Result.failure(
                ResolutionRefusal(
                    "`$KIND_ARGUMENT` must name one of this project's vocabularies: " +
                        VOCABULARY_KIND_KEYS + ".",
                ),
            )
        val kind = VocabularyKind.entries.firstOrNull { it.key.equals(named, ignoreCase = true) }
            ?: return Result.failure(
                ResolutionRefusal(
                    "There is no vocabulary called \"$named\". The eight are: $VOCABULARY_KIND_KEYS.",
                ),
            )
        return Result.success(kind)
    }

    /**
     * A project this caller may edit [kind] in, or the refusal that says why not.
     *
     * ── The gate is a question, never a rung written down here ────────────────
     *
     * [AccessControl.canEditVocabulary] decides, and it is asked with the kind because
     * that is what the answer depends on: sprints and versions are a maintainer's and
     * the six that define what the board *is* are an administrator's (LNL-191). This
     * file must not know which is which — the day that split moves, it should move in
     * AccessControl and reach every caller, this one included, without an edit. The
     * only thing read from the rung here is its *name*, and only to write the refusal;
     * [ProjectRole.prose] exists for that and for nothing else.
     *
     * Readable first and editable second, exactly as [sprintAdminProject] and the HTTP
     * routes' `adminProject`: an id the caller cannot see answers "no such project"
     * rather than confirming one exists by refusing on rights.
     *
     * The refusal itself is [sprintAdminProject]'s sentence with the kind swapped in.
     * That shape is the one this surface already uses for a per-project rung — it says
     * which action, which project, that the account lacks the rung, and that nothing
     * was written — and the whole point of reusing it is that an agent which has
     * learned to read one of these has learned to read all of them. See
     * [MCP_INSTRUCTIONS] on why a refusal here is final.
     */
    private suspend fun vocabularyScope(
        user: UserRecord,
        arguments: JsonObject,
        kind: VocabularyKind,
    ): Result<ProjectRecord> {
        val project = resolveProject(user, arguments)
            ?: return Result.failure(ResolutionRefusal("No such project."))
        if (!deps.access.canEditVocabulary(user, project.id, kind)) {
            return Result.failure(
                ResolutionRefusal(
                    "You cannot change the ${kind.plural} in ${project.name} — that is a " +
                        "${kind.minimumRole.prose} action, and the account you are acting as does " +
                        "not hold that rung on this project. Nothing was changed.",
                ),
            )
        }
        return Result.success(project)
    }

    /**
     * Run a vocabulary write, turning its two refusals into a [Result].
     *
     * [refusable]'s twin for the other repository, and the same argument for it: the
     * sentences [VocabularyConflict] and [VocabularyRefusal] carry are written for a
     * human — "3 issues are still in that status" — and the HTTP routes show them
     * verbatim. Showing them verbatim here too is what makes the two front doors give
     * one answer, and it is why every write below goes through the repository rather
     * than a store. Anything else thrown is a bug and propagates to [McpServer]'s
     * catch, which logs it.
     *
     * The 409/400 distinction the routes draw is dropped, and nothing is lost: an MCP
     * refusal has one shape, `isError` with a sentence, and both of these are exactly
     * that. See [refuse].
     */
    private suspend fun <T> vocabularyWrite(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (conflict: VocabularyConflict) {
        Result.failure(ResolutionRefusal(conflict.userMessage))
    } catch (refusal: VocabularyRefusal) {
        Result.failure(ResolutionRefusal(refusal.userMessage))
    }

    /**
     * A boolean argument that defaults to what the row already says.
     *
     * The absent-argument convention, applied to a flag: absent leaves [current], and
     * so does an explicit JSON null, for [JsonObject.isPresent]'s reason — models fill
     * in every property of a schema and write null for the ones they have nothing to
     * say about, and a rename that flipped a status's closing flag because of that
     * would be the silent substitution this whole surface refuses to make.
     *
     * A present value that is neither a boolean nor `"true"`/`"false"` is refused
     * rather than treated as absent. That asymmetry is deliberate: null is a model
     * being tidy, where `"maybe"` is a request nobody can honour, and answering the
     * second with "done" would report a change that was not made.
     */
    private fun flag(arguments: JsonObject, name: String, current: Boolean): Result<Boolean> {
        if (!arguments.containsKey(name) || !arguments.isPresent(name)) return Result.success(current)
        val value = arguments.bool(name)
            ?: return Result.failure(
                ResolutionRefusal("`$name` must be true or false, which \"${arguments[name]}\" is not."),
            )
        return Result.success(value)
    }

    /**
     * Read a project's vocabularies, with the ids the editing tools need.
     *
     * ── Why reading is gated on seeing the project and nothing more ───────────
     *
     * Every field this returns is already in the board response that
     * [AccessControl.canReadProject] admits somebody to: `BoardState` carries each
     * status, priority, resolution, label, component, sprint, version and relation kind
     * with its **id** and its position, and the relation kinds with their inverse names
     * and blocking flags. The one thing added is `usageCount`, which is a count of
     * issues the same caller can already list card by card.
     *
     * So a stricter gate here would withhold nothing and would break something real: an
     * agent that can see a column but cannot ask what its id is could not report on the
     * board it is looking at. The write tools below are where the rung is asked, which
     * is the same place the web app asks it — the Structure section is an
     * administrator's, and the board it configures is everybody's.
     */
    private suspend fun listVocabulary(user: UserRecord, arguments: JsonObject): McpToolResult {
        val project = resolveProject(user, arguments) ?: return noSuchProject()
        // Absent `kind` means all eight, which is one round-trip and almost always what
        // a caller about to change something wants. A named one narrows — unlike
        // get_board's `status` filter, which narrows the issues and never the
        // vocabulary, because there the vocabulary IS the orientation being asked for.
        val kinds = if (arguments.containsKey(KIND_ARGUMENT)) {
            listOf(resolveVocabularyKind(arguments).getOrElse { return refuse(it.message ?: "No such vocabulary.") })
        } else {
            VocabularyKind.entries.toList()
        }
        // Every read before the builder, as everywhere in this file: buildJsonObject's
        // lambda is not a coroutine body.
        val rows = kinds.associateWith { deps.vocabularies.rows(project.id, it) }
        // Joined back in for the one field [VocabularyRow] deliberately drops. A
        // sprint's completion is not a property of "a named row an admin can rename",
        // so it does not ride on the row — and it is the field that decides whether work
        // can be scheduled into that sprint, so an agent reading the list needs it. The
        // settings pane does exactly this join for the same reason; see
        // `ProjectSettingsRoutes.sprintEntries`.
        val completedAt = if (VocabularyKind.SPRINT in kinds) {
            deps.sprints.forProject(project.id).associate { it.id to it.completedAt }
        } else {
            emptyMap()
        }

        return ok(
            buildJsonObject {
                putJsonObject("project") {
                    put("id", project.id)
                    put("name", project.name)
                    put("keyPrefix", project.namePrefix)
                }
                putJsonObject("vocabularies") {
                    kinds.forEach { kind ->
                        putJsonArray(kind.key) {
                            rows.getValue(kind).forEach { row ->
                                add(
                                    buildJsonObject {
                                        put("id", row.id)
                                        put("name", row.name)
                                        put("position", row.position)
                                        put("usageCount", row.usageCount)
                                        // Per-kind extras, present only on the kinds
                                        // that carry them — a priority with a
                                        // `requiresResolution: false` on it would be a
                                        // field inviting a write that does nothing.
                                        when (kind) {
                                            VocabularyKind.STATUS ->
                                                put("requiresResolution", row.requiresResolution)
                                            VocabularyKind.RESOLUTION -> put("isDone", row.isDone)
                                            VocabularyKind.RELATION_KIND -> {
                                                // Absent when the kind reads the same
                                                // both ways: null IS symmetry, and a
                                                // second field claiming so could
                                                // disagree with it.
                                                row.inverseName?.let { put("inverseName", it) }
                                                put("marksBlocked", row.marksBlocked)
                                            }
                                            // Present only on a sprint that has been
                                            // completed, and its presence is the whole
                                            // signal: work cannot be scheduled into one
                                            // that has it.
                                            VocabularyKind.SPRINT ->
                                                completedAt[row.id]?.let { put("completedAt", it) }
                                            VocabularyKind.LABEL,
                                            VocabularyKind.COMPONENT,
                                            VocabularyKind.PRIORITY,
                                            VocabularyKind.VERSION,
                                            -> {}
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    /** Add a row to one of a project's vocabularies, at the end of the order. */
    private suspend fun addVocabulary(user: UserRecord, arguments: JsonObject): McpToolResult {
        // The kind first and the gate second, which is the reverse of every other tool
        // here and has to be: the rung depends on the kind, so there is nothing to check
        // until it is known. The HTTP route's handlers were rearranged for exactly this
        // when LNL-191 split the vocabulary in two.
        val kind = resolveVocabularyKind(arguments).getOrElse { return refuse(it.message ?: "No such vocabulary.") }
        val project = vocabularyScope(user, arguments, kind)
            .getOrElse { return refuse(it.message ?: "No such project.") }
        val name = arguments.string(NAME_ARGUMENT) ?: return refuse("A ${kind.noun} needs a name.")
        if (name.trim().length > MAX_MCP_VOCABULARY_NAME_LENGTH) {
            return refuse("That name is too long — $MAX_MCP_VOCABULARY_NAME_LENGTH characters at most.")
        }
        // Both relation-kind extras, accepted on the add rather than left to the rename
        // that would follow: a kind whose opposite label could only be set by a second
        // write would be briefly, and visibly, symmetric when it is not. Ignored by the
        // repository for the other seven kinds. Defaults are the safe states — symmetric,
        // and not blocking — so an agent adding a label never has to think about either.
        val inverseName = arguments.string(INVERSE_NAME_ARGUMENT)
        val marksBlocked = flag(arguments, MARKS_BLOCKED_ARGUMENT, current = false)
            .getOrElse { return refuse(it.message ?: "That flag cannot be used.") }

        val row = vocabularyWrite {
            deps.vocabularies.add(project.id, kind, name, inverseName, marksBlocked)
        }.getOrElse { return refuse(it.message ?: "That ${kind.noun} could not be added.") }

        return ok(
            buildString {
                append("Added the ${kind.noun} \"${row.name}\" (id ${row.id}) to ${project.name}, ")
                append("at the end of its ${kind.plural}. Use reorder_vocabulary to move it.")
                if (kind == VocabularyKind.SPRINT) {
                    append(" It is not active — start it with set_active_sprint.")
                }
                if (kind == VocabularyKind.STATUS) {
                    append(
                        " It does not require a resolution; rename_vocabulary with " +
                            "requires_resolution is how a column becomes a closing one.",
                    )
                }
            },
        )
    }

    /**
     * Rename a row, or change one of its flags.
     *
     * Every field defaults to what the row already says, and that is the whole of this
     * function's care. [VocabularyStore.rename] writes the name and all three flags in
     * one statement — it has to, since the web dialog sends back the row it is
     * rendering — so a value forgotten here is not "unchanged", it is *false*, and an
     * agent fixing a spelling mistake would silently turn off the flag that makes a
     * column demand a resolution. The MCP convention and the underlying write disagree
     * about what an omission means; this is where they are reconciled, and it is the
     * same reconciliation [updateIssue] performs for the assignee and the versions.
     */
    private suspend fun renameVocabulary(user: UserRecord, arguments: JsonObject): McpToolResult {
        val kind = resolveVocabularyKind(arguments).getOrElse { return refuse(it.message ?: "No such vocabulary.") }
        val project = vocabularyScope(user, arguments, kind)
            .getOrElse { return refuse(it.message ?: "No such project.") }
        val row = namedVocabularyRow(project, kind, arguments)
            .getOrElse { return refuse(it.message ?: "No such row.") }

        // Absent leaves the name, so this tool doubles as "flip one flag" — which is
        // what the web dialog's checkboxes are, and there is no reason an agent should
        // have to re-send a name it is not changing.
        val name = arguments.string(NAME_ARGUMENT) ?: row.name
        if (name.trim().length > MAX_MCP_VOCABULARY_NAME_LENGTH) {
            return refuse("That name is too long — $MAX_MCP_VOCABULARY_NAME_LENGTH characters at most.")
        }
        val requiresResolution = flag(arguments, REQUIRES_RESOLUTION_ARGUMENT, row.requiresResolution)
            .getOrElse { return refuse(it.message ?: "That flag cannot be used.") }
        val isDone = flag(arguments, IS_DONE_ARGUMENT, row.isDone)
            .getOrElse { return refuse(it.message ?: "That flag cannot be used.") }
        val marksBlocked = flag(arguments, MARKS_BLOCKED_ARGUMENT, row.marksBlocked)
            .getOrElse { return refuse(it.message ?: "That flag cannot be used.") }
        // The three-way reading `sprint`, `planned_version` and `parent_id` all take:
        // absent keeps the current opposite label, an explicit null makes the kind
        // symmetric, and a name sets it. A blank string collapses into the null case,
        // since the repository normalises blank to null anyway — "I cleared the field"
        // and "I ticked same-in-both-directions" must not become two stored states that
        // render identically.
        val inverseName = if (arguments.containsKey(INVERSE_NAME_ARGUMENT)) {
            arguments.string(INVERSE_NAME_ARGUMENT)
        } else {
            row.inverseName
        }

        vocabularyWrite {
            deps.vocabularies.rename(
                project.id, kind, row, name, requiresResolution, isDone, inverseName, marksBlocked,
            )
        }.getOrElse { return refuse(it.message ?: "That ${kind.noun} could not be changed.") }

        return ok(
            if (name == row.name) "Updated the ${kind.noun} \"${row.name}\" (id ${row.id}) in ${project.name}."
            else "Renamed the ${kind.noun} \"${row.name}\" to \"$name\" (id ${row.id}) in ${project.name}.",
        )
    }

    /** Put one vocabulary in the order given — the whole list, or nothing. */
    private suspend fun reorderVocabulary(user: UserRecord, arguments: JsonObject): McpToolResult {
        val kind = resolveVocabularyKind(arguments).getOrElse { return refuse(it.message ?: "No such vocabulary.") }
        val project = vocabularyScope(user, arguments, kind)
            .getOrElse { return refuse(it.message ?: "No such project.") }
        // longs() refuses the whole argument when any element is unreadable rather than
        // dropping that one, which is exactly what this call needs: the repository
        // demands the complete set, so a silently dropped element would become a refusal
        // about a list the caller did not send. See JsonObject.longs.
        val ids = arguments.longs(IDS_ARGUMENT)
            ?: return refuse(
                "`$IDS_ARGUMENT` must be an array of row ids, as numbers — every ${kind.noun} in " +
                    "this project, in the order you want them. list_vocabulary gives them.",
            )
        vocabularyWrite { deps.vocabularies.reorder(project.id, kind, ids) }
            .getOrElse { return refuse(it.message ?: "That order was refused.") }
        return ok("Put ${project.name}'s ${kind.plural} in the order given (${ids.size} rows).")
    }

    /**
     * Delete a row, or explain why not.
     *
     * The refusals — in use, and the last status or priority — are
     * [VocabularyRepository.delete]'s and are not restated here, which is the point of
     * going through it. What this adds is the *consequence* sentence for the deletes
     * that are allowed: the count is read before the row goes, because afterwards there
     * is nothing left to count, and an agent that has just removed a relation kind
     * should be told how many links went with it rather than discovering it later.
     */
    private suspend fun deleteVocabulary(user: UserRecord, arguments: JsonObject): McpToolResult {
        val kind = resolveVocabularyKind(arguments).getOrElse { return refuse(it.message ?: "No such vocabulary.") }
        val project = vocabularyScope(user, arguments, kind)
            .getOrElse { return refuse(it.message ?: "No such project.") }
        val row = namedVocabularyRow(project, kind, arguments)
            .getOrElse { return refuse(it.message ?: "No such row.") }
        val uses = row.usageCount

        vocabularyWrite { deps.vocabularies.delete(project.id, kind, row) }
            .getOrElse { return refuse(it.message ?: "That ${kind.noun} could not be deleted.") }

        return ok(
            buildString {
                append("Deleted the ${kind.noun} \"${row.name}\" from ${project.name}.")
                if (uses > 0) {
                    append(" ")
                    append(
                        when (kind) {
                            // The kinds whose rows cascade or release rather than
                            // restricting — the delete succeeded, so this is a
                            // consequence to report, never a warning to have given.
                            VocabularyKind.RELATION_KIND ->
                                "$uses ${plural(uses, "link")} that used it went with it, and " +
                                    "nothing records what was linked."
                            VocabularyKind.LABEL, VocabularyKind.COMPONENT ->
                                "$uses ${plural(uses, "issue")} no longer ${if (uses == 1L) "carries" else "carry"} it."
                            VocabularyKind.SPRINT ->
                                "$uses ${plural(uses, "issue")} " +
                                    "${if (uses == 1L) "is" else "are"} back in the backlog."
                            VocabularyKind.VERSION ->
                                "$uses ${plural(uses, "issue")} no longer " +
                                    "${if (uses == 1L) "names" else "name"} it."
                            // Unreachable: these three restrict on use, so a non-zero
                            // count would have been refused above. Spelled out rather
                            // than left to an else, so a schema change that relaxes one
                            // of them is a compile error here.
                            VocabularyKind.STATUS, VocabularyKind.PRIORITY, VocabularyKind.RESOLUTION -> ""
                        },
                    )
                }
                append(" This cannot be undone.")
            },
        )
    }

    /** The `id` argument as a row of [kind] *in this project*, or a refusal. */
    private suspend fun namedVocabularyRow(
        project: ProjectRecord,
        kind: VocabularyKind,
        arguments: JsonObject,
    ): Result<VocabularyRow> {
        val id = arguments.long(VOCABULARY_ID_ARGUMENT)
            ?: return Result.failure(
                ResolutionRefusal(
                    "`$VOCABULARY_ID_ARGUMENT` must be a row id, as a number — the `id` from " +
                        "list_vocabulary.",
                ),
            )
        // Project-scoped, exactly as the HTTP route's `vocabularyRow` is, and for the
        // reason [VocabularyRepository.find] gives: an administrator is an administrator
        // in their own project only, and a client that sent the id it had lying around
        // must not rename another project's "Closed".
        val row = deps.vocabularies.find(project.id, kind, id)
            ?: return Result.failure(
                ResolutionRefusal(
                    "There is no ${kind.noun} with id $id in ${project.name}. Ids are per project; " +
                        "list_vocabulary gives this one's.",
                ),
            )
        return Result.success(row)
    }

    /**
     * Turn a project's estimates on, off, or over to the other unit.
     *
     * [AccessControl.canAdministerProject] — the same gate the `/estimates` route asks,
     * and the one the vocabulary's six board-defining kinds sit behind. Deciding whether
     * a team estimates at all is a decision about what the board is, not work inside it.
     *
     * ── The one place this is deliberately stricter than the HTTP route ───────
     *
     * That route folds an unrecognised mode to [EstimateMode.NONE] rather than refusing,
     * and it is right to: it is protecting a *browser* from a version skew nobody can
     * see, where degrading to the state that renders nothing beats a screen that will
     * not open. Here the same fold would mean a typo in one argument silently switching
     * a live project's estimates off — a write nobody asked for, reported as success,
     * which is the silent substitution this whole surface exists to refuse. So an
     * unknown mode is refused with the three that exist.
     */
    private suspend fun setEstimateMode(user: UserRecord, arguments: JsonObject): McpToolResult {
        val project = resolveProject(user, arguments) ?: return noSuchProject()
        if (!deps.access.canAdministerProject(user, project.id)) {
            return refuse(
                "You cannot change whether ${project.name} estimates — that is a " +
                    "${ProjectRole.ADMIN.prose} action, and the account you are acting as does not " +
                    "hold that rung on this project. Nothing was changed.",
            )
        }
        val named = arguments.string(MODE_ARGUMENT)
            ?: return refuse("`$MODE_ARGUMENT` must be one of: $ESTIMATE_MODE_KEYS.")
        val mode = EstimateMode.entries.firstOrNull { it.key.equals(named.trim(), ignoreCase = true) }
            ?: return refuse(
                "There is no estimate mode called \"$named\". The three are: $ESTIMATE_MODE_KEYS. " +
                    "Nothing was changed.",
            )
        val previous = project.estimateMode
        deps.projects.setEstimateMode(project.id, mode)
        return ok(
            buildString {
                append("${project.name} now estimates in \"${mode.key}\"")
                if (previous != mode) append(", where it was \"${previous.key}\"") else append(" (unchanged)")
                append(". ")
                append(
                    when (mode) {
                        EstimateMode.NONE ->
                            "No estimate can be written here now, and any estimate sent to " +
                                "create_issue or update_issue is refused. The estimates already " +
                                "stored are untouched — this hides the field, it does not erase them."
                        EstimateMode.TIME -> "New estimates are whole minutes (estimate_unit \"minutes\")."
                        EstimateMode.POINTS -> "New estimates are whole points (estimate_unit \"points\")."
                    },
                )
                append(
                    " Nothing already estimated is reinterpreted: every issue carries the unit its " +
                        "estimate was written in.",
                )
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
        tooLongMessage("comment", body)?.let { return refuse(it) }

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
     * you are an instance administrator — and only then does the owner-only backfill
     * gate decide whether you may also re-attribute or re-date it.
     *
     * ── Why this rewrites author and timestamp at all ────────────────────────
     *
     * The rest of this file holds that backfill is creation-only (see the preamble).
     * `update_comment` is the deliberate exception: the instance owner may change
     * everything about a comment, an external author included, so an import that got
     * a name or a date wrong can be corrected in place rather than deleted and
     * refiled — and deletion is the one thing this surface does not offer. It is the
     * owner's for exactly the reason create-time attribution is, gated by the same
     * [AccessControl.canAttributeWrites] through [resolveAttribution].
     */
    private suspend fun updateComment(user: UserRecord, arguments: JsonObject): McpToolResult {
        val comment = readableComment(user, arguments) ?: return noSuchComment()
        if (!deps.access.canEditComment(user, comment)) {
            return refuse("That is not your comment.")
        }

        // author / author_external / created_at are the owner-only backfill levers,
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

        // Absent leaves the badge untouched and a real name sets it, neither of them
        // the owner's — self-labelling is the norm, see resolveAttribution's
        // preamble. An empty value clears it, which only the owner may do. See
        // resolveAgentNameEdit.
        val agentName = resolveAgentNameEdit(user, arguments, comment.agentName)
            .getOrElse { return refuse(it.message ?: "That agent name cannot be used.") }

        // Blank or absent keeps the current body, as update_issue does with a title:
        // an edit is not how a comment gets emptied.
        val body = arguments.string("body") ?: comment.body
        tooLongMessage("comment", body)?.let { return refuse(it) }

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
     * which is the deployment owner's job start to finish.
     *
     * So the owner gate IS the gate, and it is asked first — before the row is even
     * resolved — so that anybody else who somehow reached this tool (it is absent
     * from their tool list) cannot use an `event_id` to probe which entries, and so
     * which private boards, exist. The readable-project chain below is then
     * belt-and-braces, since the owner can read every board anyway.
     *
     * WHAT the entry records is never touched: `kind`, its `value` and its `values`
     * are not parameters, here or in the store. Only who, when, and the agent label
     * move — the same three levers [resolveAttribution] and [resolveAgentName]
     * carry everywhere else, gated the same way.
     */
    private suspend fun updateHistoryEvent(user: UserRecord, arguments: JsonObject): McpToolResult {
        if (!deps.access.canAttributeWrites(user)) {
            return refuse(
                "Only the instance owner can edit an issue's history, and you are not acting " +
                    "as the owner. A history is append-only for everyone else — its entries record " +
                    "what happened and are not yours to rewrite. Nothing was written.",
            )
        }
        // Null only in test deployments that wire up no history; a production
        // server always has one. An owner who reached here on such a deployment is
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
        // they were. The owner gate above has already passed, so resolveAttribution's
        // own check is a no-op here — but it is the check, and going around it would
        // be the one copy that could drift.
        val attribution = resolveAttribution(
            user,
            arguments,
            default = Attribution(event.author, event.createdAt),
            removalOutcome = "the entry keeps its current author and date",
        ).getOrElse { return refuse(it.message ?: "That attribution cannot be used.") }

        // Absent leaves the badge as it is; a real name sets it; an empty value
        // clears it. Clearing is the owner's alone — but this whole tool already is, so
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
     * Note what is deliberately NOT here: no instance-wide gate, and no "are you
     * sure" argument. The first because deletion is a rung on the project rather
     * than a fact about the deployment — `canDeleteIssue` asks for
     * [ProjectRole.ADMIN], or authorship, which is one rung ABOVE the
     * [ProjectRole.MAINTAINER] `canEditIssue` takes. LNL-191 split the two on the
     * reasoning AccessControl gives: a maintainer can already empty an issue of
     * every word it said, and what they cannot do is make it stop existing. The
     * second because a confirmation flag a caller sets itself confirms nothing; the
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
     * NARROWER than [deleteIssue]'s gate on purpose. A project rung is a grant over
     * the board's issues; it has never meant "and you may also delete what other
     * people wrote", and reading it that way here would quietly widen every
     * [ProjectRole.ADMIN] a project has handed out already. Somebody else's words go
     * on the comment author's say, or an instance administrator's, and on nobody's by
     * way of a board.
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
     * issue may attach to it, and only the instance owner may attach *as somebody
     * else*. That is the same split `create_issue` already has, and it is why this
     * tool is offered to everybody despite carrying the owner's parameters.
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
            // forum surface is refused as a whole — canUseForumTools answers
            // false for everybody since LNL-190 — so nobody reaches these two
            // branches at all, and whoever does when discussions come back is
            // whoever could rewrite the post's body outright with
            // update_forum_post. A narrower check here would refuse nobody and
            // would imply the forum tools have a permission model they do not.
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
                "Sending e-mail over MCP is restricted to the owner of this Lunicle server, and " +
                    "this account is not the owner. This is not a missing setting the user can " +
                    "turn on themselves — tell them what you would have written instead, and do " +
                    "not try again.",
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
     * It runs before anything is resolved, so a refusal costs no query and tells the
     * caller nothing about which projects have forums.
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
        tooLongMessage("post", body)?.let { return refuse(it) }

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

        val body = arguments.string("body") ?: scope.post.body
        tooLongMessage("post", body)?.let { return refuse(it) }

        refusable {
            deps.forumPosts.editPost(
                post = scope.post,
                title = arguments.string("title") ?: scope.post.title,
                body = body,
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
        tooLongMessage("comment", body)?.let { return refuse(it) }

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
     * [forumScope] enforces — which since LNL-190 lets nobody through at all. The
     * subject rule is still [watchSubject]'s — the caller's own inbox by default,
     * another's the instance owner's — because "who may reach the forum tools" and
     * "changing another person's subscription is an attribution" are two rules, and
     * the second should read the same here as it does on an issue.
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
     * it identically: absent `user` is the caller; a named user is the instance
     * owner's alone via [AccessControl.canAttributeWrites], refused by name if
     * unknown; and a subscribe onto an address-less account is refused, since it
     * would notify nobody. Unwatching needs no address.
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
                        "Only the instance owner can change somebody else's watch. Omit " +
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
     * The gate comes before the lookup so that a refused caller's answer is about
     * the surface rather than about the id, and cannot be used to learn whether a
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
     * Anybody but the owner who asked is refused with the parameter named, rather
     * than served as if they had not asked. See this file's preamble for why that
     * is the whole feature: the alternative is an agent truthfully reporting a
     * backfill that silently went in under the wrong name.
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
     * to now. [removalOutcome] is the tail of the not-the-owner refusal, since
     * "remove these and it files under you, now" is only true at creation.
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
                    "Only the instance owner can set $asked, and you are not acting as the owner. " +
                        "Nothing was written. Remove $asked and $removalOutcome — but that is a " +
                        "different thing from what you asked for, so decide rather than assume.",
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
     * else* is the owner-only act that [resolveAttribution] guards. So there is no
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
     * The agent label for an EDIT: set it, leave it, or — as the owner — clear it.
     *
     * A create can only ever ADD a badge, so [resolveAgentName] is all it needs:
     * absent, null and blank alike mean "no badge", and there is nothing yet to
     * remove. An edit is the one place a badge can already exist and need to *go* —
     * a migration forces the case, because a row imported from another tracker was
     * not made by an agent and must not wear one. So the edit tools carry one state
     * more than a create does, and it is the owner's alone for [resolveAttribution]'s
     * reason: the badge is the mark that says "an agent did this", and removing it
     * rewrites the record of who did, which is [AccessControl.canAttributeWrites]'s
     * gate and no lighter an act than re-authoring the row.
     *
     * The four states, by how `agent_name` arrives:
     *
     *  - **Absent, or explicit null → leave [current] alone.** Null counts as absent
     *    for [isPresent]'s reason: models null-fill fields they have nothing to say
     *    about, and that must not silently strip a badge.
     *  - **Present and blank (`""` or whitespace) → CLEAR the badge.** The instance
     *    owner's alone: anybody else who asks is refused with the parameter named,
     *    never quietly left as-is — the silent substitution this whole surface
     *    refuses to make.
     *  - **Present and a real name → set it,** length-checked by [resolveAgentName].
     *    Open to everybody; labelling a row you are already allowed to edit is the
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
        // string() reports blank as null — and only the instance owner may.
        if (arguments.string(AGENT_NAME_ARGUMENT) == null) {
            if (!deps.access.canAttributeWrites(user)) {
                return Result.failure(
                    ResolutionRefusal(
                        "Only the instance owner can clear an agent label, and you are not " +
                            "acting as the owner. An empty `$AGENT_NAME_ARGUMENT` asks to remove " +
                            "the badge; omit the parameter entirely to leave it exactly as it is. " +
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
     * [UserRecord.resolvedName] and nothing more — and the alternative was a
     * `list_users` tool for the instance owner. Rejected: §3's instinct is that a
     * capability with no tool cannot be abused, and "every account on this
     * instance, on request" is exactly the kind of tool that gets called for no
     * reason once it exists. Matching the name already on the board costs nothing
     * and adds no enumeration primitive — the agent can only confirm names it was
     * told.
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
     * It could — the caller is the instance owner — but an error message is a bad
     * place to decide to start disclosing emails, and the sentence is just as
     * actionable without: the human driving this import knows which Anna they mean.
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
     * edit an issue is not the same as being assignable on the project, and a
     * project administrator qualifies either way.
     *
     * So MCP assignment is strictly the editor's path: it needs write rights, where
     * the web app's button needs only the contributor rung. Deliberate. The button
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
     * Whether the work is going to the assignee's *agent* rather than to them — or
     * null, which is this flag's spelling of "say nothing" (LNL-215).
     *
     * ── Null is "leave it alone", and it is the ONLY field here where that is so ──
     *
     * Every other optional field on `update_issue` defaults to the issue's current
     * value, because [IssueStore.publish] overwrites its column unconditionally and a
     * forgotten value would silently clear it. This one deliberately does not, and
     * [IssueRepository.save]'s `assigneeIsAgent` exists to make that possible: passing
     * the stored flag back would drag it across a handover, so that an agent fixing a
     * typo on an issue that has just been reassigned would re-mark the new holder's
     * work as their robot's. Null means "not editing this", and the repository decides
     * — keep the flag when the assignee is unchanged, clear it when they are not, and
     * never set it on an issue nobody holds.
     *
     * That is also why this is not simply [JsonObject.bool]: `bool` answers null for
     * absent and for garbage alike, which would make `"assignee_is_agent": "yes"` a
     * silent no-op reported as success. Absent and explicit-null say nothing (models
     * null-fill a schema, per [isPresent]); anything else that is not a boolean is
     * refused.
     */
    private fun resolveAssigneeIsAgent(arguments: JsonObject): Result<Boolean?> {
        if (!arguments.containsKey(ASSIGNEE_IS_AGENT_ARGUMENT) ||
            !arguments.isPresent(ASSIGNEE_IS_AGENT_ARGUMENT)
        ) {
            return Result.success(null)
        }
        val value = arguments.bool(ASSIGNEE_IS_AGENT_ARGUMENT)
            ?: return Result.failure(
                ResolutionRefusal(
                    "`$ASSIGNEE_IS_AGENT_ARGUMENT` must be true or false, which " +
                        "\"${arguments[ASSIGNEE_IS_AGENT_ARGUMENT]}\" is not. It says whether the " +
                        "work goes to the assignee's agent rather than to them in person; omit it " +
                        "to leave the flag exactly as it is.",
                ),
            )
        return Result.success(value)
    }

    /**
     * How much work an issue is, as two arguments that are one value (LNL-215).
     *
     * ── Why the pair is enforced rather than defaulted ────────────────────────
     *
     * An amount with no unit is not an estimate this application can render, and a unit
     * with no amount is not an estimate at all — see [Estimate], which is one type for
     * exactly that reason rather than two nullable fields. The tempting shortcut is to
     * default the unit from the project's [EstimateMode], which would work today and
     * would be wrong tomorrow: the whole point of stamping the unit on the issue is
     * that the project's mode governs what may be *written*, never what a stored row
     * *means*, and a tool that silently borrowed the mode would be the one place those
     * two got conflated. So both or neither, and one alone is refused by name.
     *
     * ── The four readings of the pair ─────────────────────────────────────────
     *
     *  - **Neither key present → [current].** Absent means leave alone, as everywhere.
     *  - **Both present, both values → that estimate,** validated below.
     *  - **Both present, both JSON null → cleared.** The way to remove an estimate,
     *    matching how `assignee` and the versions read an explicit null.
     *  - **Anything else → refused.** One key without the other, or one value beside
     *    one null, is a half-stated intention and guessing at it is how a save reports
     *    success for a change it did not make.
     *
     * The unit and the project's mode are checked by
     * [se.soderbjorn.lunicle.resolveEstimate] — the route's own function, so the rule
     * lives in one place and an agent is bound by it exactly as the editor is. Its
     * sentence is passed through with one extra line naming the tool that would change
     * the mode, which is the next step an agent has and a browser does not.
     */
    private suspend fun resolveEstimateArgument(
        projectId: Long,
        arguments: JsonObject,
        current: Estimate?,
    ): Result<Estimate?> {
        val hasAmount = arguments.containsKey(ESTIMATE_AMOUNT_ARGUMENT)
        val hasUnit = arguments.containsKey(ESTIMATE_UNIT_ARGUMENT)
        if (!hasAmount && !hasUnit) return Result.success(current)
        if (hasAmount != hasUnit) {
            val sent = if (hasAmount) ESTIMATE_AMOUNT_ARGUMENT else ESTIMATE_UNIT_ARGUMENT
            val missing = if (hasAmount) ESTIMATE_UNIT_ARGUMENT else ESTIMATE_AMOUNT_ARGUMENT
            return Result.failure(
                ResolutionRefusal(
                    "`$sent` was sent without `$missing`, and the two are one value: an amount with " +
                        "no unit is a number nothing can render, and a unit with no amount is not " +
                        "an estimate. Send both, or neither. get_board's `estimateMode` says which " +
                        "unit this project takes. Nothing was written.",
                ),
            )
        }
        val statedAmount = arguments.isPresent(ESTIMATE_AMOUNT_ARGUMENT)
        val statedUnit = arguments.isPresent(ESTIMATE_UNIT_ARGUMENT)
        if (!statedAmount && !statedUnit) return Result.success(null)
        if (statedAmount != statedUnit) {
            return Result.failure(
                ResolutionRefusal(
                    "`$ESTIMATE_AMOUNT_ARGUMENT` and `$ESTIMATE_UNIT_ARGUMENT` move together: send " +
                        "both as values to set an estimate, or both as null to clear it. One of " +
                        "each is neither. Nothing was written.",
                ),
            )
        }

        val amount = arguments.long(ESTIMATE_AMOUNT_ARGUMENT)
            ?: return Result.failure(
                ResolutionRefusal(
                    "`$ESTIMATE_AMOUNT_ARGUMENT` must be a whole number, which " +
                        "\"${arguments[ESTIMATE_AMOUNT_ARGUMENT]}\" is not. Minutes or points — " +
                        "never a fraction, and never \"2h\": send 120 with " +
                        "`$ESTIMATE_UNIT_ARGUMENT` \"minutes\".",
                ),
            )
        val unitName = arguments.string(ESTIMATE_UNIT_ARGUMENT)
            ?: return Result.failure(
                ResolutionRefusal("`$ESTIMATE_UNIT_ARGUMENT` must be one of: $ESTIMATE_UNIT_KEYS."),
            )
        val unit = EstimateUnit.entries.firstOrNull { it.mcpKey.equals(unitName.trim(), ignoreCase = true) }
            ?: return Result.failure(
                ResolutionRefusal(
                    "There is no estimate unit called \"$unitName\". The two are: $ESTIMATE_UNIT_KEYS. " +
                        "Nothing was written.",
                ),
            )

        val resolved = deps.resolveEstimate(projectId, Estimate(amount, unit))
            .getOrElse { failure ->
                return Result.failure(
                    ResolutionRefusal(
                        // The route's own sentence, and then the one thing it has no
                        // reason to say to a browser: which tool moves the setting it
                        // just refused against, and who may use it.
                        (failure.message ?: "That estimate cannot be used.") +
                            " get_board reports the mode in force as `estimateMode`; a project " +
                            "administrator can change it with set_estimate_mode. Nothing was written.",
                    ),
                )
            }
        return Result.success(resolved)
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
     * as a date nobody can explain from the UI. A bad value is the owner's alone to
     * reach — at creation, or through `update_comment` and `update_issue` — and every
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
     * Resolve a project this agent may reach, by id or by name.
     *
     * Both answer identically when the project is out of reach — see [noSuchProject].
     *
     * ── Why this asks the agent question and not the read one (LNL-217) ─────
     *
     * [AccessControl.canAgentReachProject] rather than `canReadProject`, which is the
     * whole of the agent floor on this path: a project where the caller is only a Viewer
     * is not "visible but read-only" to an agent, it is **absent**. See that function for
     * why the line falls at Contributor, and [AGENT_PROJECT_FLOOR] for why it is a
     * constant.
     *
     * That it lands in [noSuchProject] with private projects rather than in a refusal of
     * its own is deliberate, and it is the same conflation the HTTP routes make: an agent
     * that could tell "you are only a Viewer here" from "no such project" could enumerate
     * every board on the deployment by name, which is exactly what a caller below the
     * floor must not be able to do. The person reading the agent's report is told about
     * the floor by the server instructions instead, where it belongs — see
     * [MCP_INSTRUCTIONS], which says it once rather than at every refusal.
     */
    private suspend fun resolveProject(user: UserRecord, arguments: JsonObject): ProjectRecord? {
        val project = arguments.long("project_id")?.let { deps.projects.findById(it) }
            ?: arguments.string("project_name")?.let { deps.projects.findByName(it) }
            ?: return null
        return project.takeIf { deps.access.canAgentReachProject(user, it) }
    }

    /**
     * Resolve an issue whose project this agent may reach.
     *
     * Every issue tool starts here, exactly as every issue route starts at
     * `readableIssue`: an issue is only as reachable as its project. The floor applies
     * here for [resolveProject]'s reason and by the same call — an issue id must not be
     * the way around a project that is out of reach.
     */
    private suspend fun readableIssue(user: UserRecord, arguments: JsonObject): IssueRecord? {
        val issue = arguments.long("issue_id")?.let { deps.issues.findById(it) } ?: return null
        val project = deps.projects.findById(issue.projectId) ?: return null
        return issue.takeIf { deps.access.canAgentReachProject(user, project) }
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
        return comment.takeIf { deps.access.canAgentReachProject(user, project) }
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

// LNL-215: the agent-assignee flag, the two halves of an estimate, the two ends and
// the kind of a link, and the five arguments the vocabulary tools share.
private const val ASSIGNEE_IS_AGENT_ARGUMENT = "assignee_is_agent"
private const val ESTIMATE_AMOUNT_ARGUMENT = "estimate_amount"
private const val ESTIMATE_UNIT_ARGUMENT = "estimate_unit"
private const val TO_ISSUE_ARGUMENT = "to_issue_id"
private const val RELATION_ARGUMENT = "relation"
private const val RELATION_ID_ARGUMENT = "relation_id"
private const val KIND_ARGUMENT = "kind"
private const val NAME_ARGUMENT = "name"
private const val VOCABULARY_ID_ARGUMENT = "id"
private const val IDS_ARGUMENT = "ids"
private const val INVERSE_NAME_ARGUMENT = "inverse_name"
private const val MARKS_BLOCKED_ARGUMENT = "marks_blocked"
private const val REQUIRES_RESOLUTION_ARGUMENT = "requires_resolution"
private const val IS_DONE_ARGUMENT = "is_done"
private const val MODE_ARGUMENT = "mode"

/**
 * How long a vocabulary row's name may be, over MCP.
 *
 * The same bound `ProjectSettingsRoutes` applies, and it has to be: an agent's status
 * is rendered in the same column header a human's is. Duplicated as a constant rather
 * than widening the route's private one, which is [MAX_MCP_TITLE_LENGTH]'s trade and
 * the same smaller wrong.
 */
private const val MAX_MCP_VOCABULARY_NAME_LENGTH = 60

/**
 * The eight vocabulary keys, in one string, for the refusals that list them.
 *
 * Built off the enum rather than written out, so a ninth kind appears in every
 * refusal the moment it exists — which is exactly the property the generic tool
 * surface was chosen for. See the section banner above `vocabularyScope`.
 */
private val VOCABULARY_KIND_KEYS = VocabularyKind.entries.joinToString(", ") { "\"${it.key}\"" }

/** The three estimate modes, and the two units, for the same reason. */
private val ESTIMATE_MODE_KEYS = EstimateMode.entries.joinToString(", ") { "\"${it.key}\"" }
private val ESTIMATE_UNIT_KEYS = EstimateUnit.entries.joinToString(", ") { "\"${it.mcpKey}\"" }

/**
 * What an [EstimateUnit] is called on this surface.
 *
 * Lowercase, matching how [EstimateMode] spells its own [EstimateMode.key] and how
 * every other vocabulary word reaches an agent. Deliberately NOT the enum's
 * serialized name, which is `MINUTES` and is wire format for the database column —
 * the two happen to differ only in case today, and this exists so that a rename on
 * either side is not silently a rename on the other. Accepted case-insensitively on
 * the way in; see `resolveEstimateArgument`.
 */
private val EstimateUnit.mcpKey: String get() = name.lowercase()

/**
 * An estimate as the two fields an agent reads back.
 *
 * The unit rides with the amount rather than being left to the project's mode,
 * because a stored estimate keeps meaning what it meant when it was written even
 * after an administrator switches the project to the other unit. See [EstimateUnit],
 * where that is the whole design.
 */
private fun JsonObjectBuilder.putEstimate(estimate: Estimate) {
    put("amount", estimate.amount)
    put("unit", estimate.unit.mcpKey)
}

/**
 * A rung as it is named in a refusal — "a project-administrator action".
 *
 * Phrasing, not policy. It exists so that `vocabularyScope` can name the rung
 * [AccessControl.canEditVocabulary] actually asked for without this file deciding
 * what that rung is: the decision stays one function call, and this only turns its
 * answer into English. Exhaustive over [ProjectRole] rather than falling back to
 * [ProjectRole.label], so a new rung is a compile error here and not a sentence
 * reading "a project-Whatever action".
 */
private val ProjectRole.prose: String
    get() = when (this) {
        ProjectRole.VIEWER -> "project-viewer"
        ProjectRole.CONTRIBUTOR -> "project-contributor"
        ProjectRole.MAINTAINER -> "project-maintainer"
        ProjectRole.ADMIN -> "project-administrator"
        ProjectRole.OWNER -> "project-owner"
    }

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
 * override rather than leaving the model to infer there is one. Unlike `author`, it
 * is not the instance owner's and changes nothing about ownership, which the last
 * line says out loud so a model does not lump it in with the backfill parameters
 * above and shy away from it.
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

/**
 * The half of `assignee_is_agent` both issue tools share (LNL-215).
 *
 * It leads with what the flag is *for*, because the name alone reads as though it
 * might mean "the assignee is a bot account" — which is precisely what it does not
 * mean, and getting that backwards would put a robot badge on a person.
 */
private const val ASSIGNEE_IS_AGENT_PROP_DESCRIPTION =
    "Whether the work is going to the assignee's AGENT rather than to them in person — the flag " +
        "behind the small robot badge beside their name. It is a statement about who does the " +
        "work, not about what kind of account the assignee has: the issue still belongs to the " +
        "person, and they are still who gets asked about it. It can only ever be true beside an " +
        "assignee, so setting it on an unassigned issue is quietly nothing; and handing the issue " +
        "to somebody else clears it, because the previous holder's agent is not on this any more."

/**
 * As above, for the two halves of an estimate. One description apiece, shared by both
 * issue tools, with the "what does absent mean" tail appended per tool — the same
 * arrangement [ASSIGNEE_PROP_DESCRIPTION] uses and for the same reason.
 */
private const val ESTIMATE_AMOUNT_PROP_DESCRIPTION =
    "How much work this is, as a WHOLE NUMBER — minutes when the project's estimateMode is " +
        "\"time\", points when it is \"points\". Never a fraction and never a formatted string: " +
        "two hours is 120 with estimate_unit \"minutes\", not \"2h\" and not 2. Zero is allowed " +
        "and means estimated at nothing, which is a real answer for a trivial ticket; a negative " +
        "number is refused. Must be sent together with estimate_unit — one without the other is " +
        "refused, not half-applied."

private const val ESTIMATE_UNIT_PROP_DESCRIPTION =
    "\"minutes\" or \"points\", saying what estimate_amount counts. It must match what the " +
        "project currently offers — get_board's `estimateMode`: \"time\" takes minutes, " +
        "\"points\" takes points, and \"none\" takes no estimate at all and refuses both " +
        "arguments. Sent together with estimate_amount, always. The unit is stored on the issue, " +
        "so an estimate keeps meaning what it meant even if an administrator later switches the " +
        "project to the other unit."

/** One description of the `kind` argument, shared by all five vocabulary tools. */
private const val VOCABULARY_KIND_PROP_DESCRIPTION =
    "Which of the project's eight vocabularies: \"status\" (the board's columns), \"priority\", " +
        "\"resolution\", \"label\", \"component\", \"sprint\", \"version\" or \"relation-kind\" " +
        "(the ways two issues can be linked). Case-insensitive; anything else is refused with the " +
        "eight listed."

/**
 * One description of `inverse_name`, shared by add and rename.
 *
 * It says what null means in the same breath as what a value means, because null here
 * is not "unset" but a positive statement — the kind reads the same in both directions
 * — and an agent that read it as "unset" would leave a "Blocked by" with no "Blocks".
 */
private const val INVERSE_NAME_PROP_DESCRIPTION =
    "RELATION KINDS ONLY: the TO-side label, the sentence about the issue at the other end. " +
        "\"Blocks\" beside a name of \"Blocked by\", \"Duplicated by\" beside \"Duplicate of\". " +
        "Leave it out — or send null — when the kind reads the SAME in both directions, which is " +
        "what \"Related to\" is: that is not an unset field, it is how symmetry is spelled. A " +
        "kind may not be its own opposite, and neither of its two labels may collide with either " +
        "label of another kind in the project, since they all appear in one picker."

/** One description of `marks_blocked`, shared by add and rename. */
private const val MARKS_BLOCKED_PROP_DESCRIPTION =
    "RELATION KINDS ONLY: whether an issue on the FROM side of one of these counts as blocked. " +
        "Defaults to false, and arming it is a deliberate act — it decides which cards read as " +
        "blocked on everybody's board. Note what it does not say: whether any given issue is " +
        "blocked right now, which also needs the issue at the other end to still be open. " +
        "get_board answers that per issue with `isBlocked`."

private const val AGENT_NAME_PROP_DESCRIPTION =
    "Your own name as the agent doing this on the user's behalf — for example the assistant " +
        "or product you are. NORMALLY SET IT: it is how the board shows, clearly, that an agent " +
        "filed this rather than a human typing by hand, and that is the expected default for a " +
        "write made through this MCP server. Omit it only when you have been explicitly asked to " +
        "act purely as the user with no agent attribution. This is NOT restricted to the instance " +
        "owner and does not change who the issue or comment belongs to — it rides alongside the " +
        "user's own account as a label, nothing more."

/** Shared by every tool that takes it: one description of `author`, not several that drift. */
private const val AUTHOR_PROP_DESCRIPTION =
    "INSTANCE OWNER ONLY, for backfilling imported history. Who this should belong to: their " +
        "display name exactly as get_board and get_issue report it, or the email address on " +
        "their account when two people share a name. They must already have a Lunicle " +
        "account — naming somebody does not create one — and an ambiguous name is refused " +
        "rather than guessed at. If they have no account, use author_external instead; do not " +
        "pass both. Refused, not ignored, if you are not the instance owner. Defaults to you."

/** As [AUTHOR_PROP_DESCRIPTION]: one description of `author_external`, shared. */
private const val AUTHOR_EXTERNAL_PROP_DESCRIPTION =
    "INSTANCE OWNER ONLY, for backfilling imported history written by somebody with no Lunicle " +
        "account — a GitHub handle, say, from a tracker being migrated. Recorded as the name " +
        "itself and rendered as the author; it creates no account and grants nobody anything, " +
        "so the row is unowned: afterwards it is editable by a rung rather than by its author — " +
        "a project maintainer for an issue, an instance administrator for a comment. Use " +
        "`author` instead when the person does have an account, and never pass both — they are " +
        "two answers to one question and the pair is refused. Not checked against existing " +
        "accounts: if you pass a name somebody here happens to share, you get an author who is " +
        "not them. Refused, not ignored, if you are not the instance owner."

/**
 * As [AUTHOR_PROP_DESCRIPTION]: one description of `updated_at`, shared.
 *
 * Says what it is *for* rather than only what it does. The tool that needs it is
 * the one an importer reaches for last — after an attachment exists and the body
 * has to be rewritten to point at it — and by then the reason the parameter is
 * there at all is easy to miss.
 */
private const val UPDATED_AT_PROP_DESCRIPTION =
    "INSTANCE OWNER ONLY, for backfilling. When this issue was last touched, in epoch milliseconds. " +
        "Cannot be in the future, and cannot be before the issue's own created_at — an issue " +
        "edited before it existed is not a history anyone can read. Every edit stamps this " +
        "column, so an import that uploads an attachment and then rewrites the description to " +
        "point at it would otherwise drag a years-old issue to the top of the board, dated " +
        "today: pass the date the history actually ended. Refused, not ignored, if you are not " +
        "the instance owner. Defaults to now."

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
 * either wrong would silently offer a retired tool to everybody. It is
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
 * The one refusal every forum tool gives, to every caller — the instance owner
 * included.
 *
 * It says the capability does not exist at all rather than that something went
 * wrong, and it says not to retry: an agent that reads "you cannot" as "not yet"
 * will spend a conversation rediscovering the same answer. It no longer offers the
 * Discussion tab as the thing to do instead, because LNL-190 took that away too —
 * there is nowhere to send the person, and saying so is the honest answer.
 */
private const val FORUM_REFUSAL =
    "The discussion forums are gone. LNL-190 retired them, so these tools are offered to " +
        "nobody at all — this is not a permission the account lacks and not a setting anybody " +
        "can turn on, and there is no Discussion tab left to do it in either. Tell the person " +
        "what you would have done, and do not try again."

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
    "INSTANCE OWNER ONLY, for backfilling imported discussions. When this was written, in epoch " +
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
