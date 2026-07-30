/**
 * Turning things that happen into notification e-mails.
 *
 * This is the half that decides *who* to mail and *what to say*; the actual
 * sending is the [EmailTransport]'s job — the Resend- or SMTP-backed transport
 * built alongside this, whose own doc anticipated exactly this hand-off. So there
 * is no "dummy": when a transport is present (the deployment configured Resend or
 * SMTP), a message really leaves; when it is absent (local dev, or a deploy with
 * no mail configured), the
 * composed message is logged instead of sent. Sending is therefore gated on the
 * same deliberate configuration the "Send test email" diagnostic verified, and
 * nothing goes out by accident.
 *
 * The [IssueNotifier] seam stays: [IssueRepository] fires events without
 * depending on any of the mail machinery, so a test — or any path with no
 * notification concern — uses [NoNotifications] and constructs nothing.
 *
 * ── The shape to copy, for the third and fourth features that need mail ──────
 *
 * LNL-30 flagged that [IssueNotifier] is typed on [IssueRecord] throughout, that
 * LNL-60 (private messages) and LNL-63 (watching a forum or a post) would both
 * hit that wall, and that whichever landed first should solve it reusably.
 * LNL-60 landed first. The generalisation it made is deliberately *not* a
 * notifier interface wide enough for three kinds of thing — that would be one
 * interface with three unrelated methods and a `NoNotifications` that has to
 * implement all of them. It is a split:
 *
 *  - **[NotificationDispatcher] is the plumbing, and it is feature-agnostic.**
 *    Who has an address, who the actor is called, dropping the actor from their
 *    own notification, and send-or-log. That is the whole of what the three
 *    features share, and none of it mentions an issue, a message or a post.
 *  - **A narrow notifier interface per feature, with a no-op object beside it.**
 *    Three of them now: [IssueNotifier]/[NoNotifications],
 *    [MessageNotifier]/[NoMessageNotifications], and — since LNL-63 —
 *    [ForumNotifier]/[NoForumNotifications], each implemented by a service that
 *    holds a [NotificationDispatcher] and a base URL.
 *
 * The prediction held: LNL-63 added its interface, its two bodies and its link
 * builder ([forumUrl], beside [issueUrl] and [conversationUrl]) and **changed not
 * one line of [NotificationDispatcher]**. It did need one thing the other two did
 * not, and it is worth knowing about before writing a fourth: a forum watcher's
 * *visibility of the project* is re-checked at send time, because a subscription
 * outlives the membership that justified it. That check lives in
 * [ForumNotificationService], not in the plumbing and not in SQL.
 *
 * The reason the interfaces stay narrow is the one [IssueNotifier] already gives:
 * a repository depends on the *events it fires*, not on the mail machinery, and
 * an interface it does not fully use is one whose unused half a test still has to
 * stub.
 *
 * @see EmailTransport
 * @see SubscriptionStore
 */
package se.soderbjorn.lunicle

import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.clientserver.NotificationKind
import se.soderbjorn.lunicle.clientserver.mentionedNames
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The site e-mail links point at, without a trailing slash, or null when the
 * deployment has not set one.
 *
 * ── Why this is configured and [ApplicationCall.serverOrigin] is not ─────────
 *
 * Everywhere else that needs this server's address derives it from the request,
 * and that doc argues against a config value. A notification has no request to
 * derive it from — [NotificationService] is built once at startup and sees only
 * an [IssueRecord] — but that is not the real reason this is different.
 *
 * The real reason is that the *request's* origin is often the wrong answer. A
 * deployment may answer on more than one hostname — for instance one that exists
 * to be framed by another site, and a standalone one. A mail sent because of
 * something someone did inside an embed would otherwise carry a link to the
 * embedded origin — a bare, chromeless tracker opened outside the page it was
 * meant to sit in. A link in an e-mail is always followed in a fresh tab, so it
 * should always land on the standalone site, whatever origin happened to trigger
 * it — which is why the operator names it rather than the server guessing.
 *
 * The two-tier lookup mirrors [resolveAllowedFrameAncestors]: a system property
 * for `:server:run` (the Gradle daemon's environment cannot be trusted), and the
 * `LUNICLE_PUBLIC_BASE_URL` environment variable for the deployed container.
 * There is no default — an unset base URL is null, which yields relative links
 * and which `Application.module` warns about when mail is configured. Blank is
 * absent, as everywhere else. The trailing slash is trimmed so a value pasted
 * with one does not produce `https://host//?issue=…`.
 */
fun resolvePublicBaseUrl(): String? =
    (
        System.getProperty("lunicle.publicBaseUrl")?.takeIf { it.isNotBlank() }
            ?: System.getenv("LUNICLE_PUBLIC_BASE_URL")?.takeIf { it.isNotBlank() }
        )?.trimEnd('/')

/**
 * The deep link that opens one issue, for [baseUrl] and a `LNL-18`-style
 * [reference].
 *
 * `?issue=` is the web client's own deep-link parameter — see `preferredTicket`
 * in the client's `main.kt`, which reads exactly this and opens the issue window
 * on load. The path is `/` rather than empty so the result is a whole URL even
 * when [baseUrl] is bare.
 *
 * The reference is percent-encoded rather than interpolated. A project's ticket
 * prefix is only required to be non-blank — see `ProjectRepository.validate` —
 * so a prefix containing a space or an `&` is possible, and would otherwise
 * produce a link that quietly points somewhere else.
 */
internal fun issueUrl(baseUrl: String, reference: String): String =
    "$baseUrl/?issue=" + URLEncoder.encode(reference, StandardCharsets.UTF_8)

/**
 * The deep link that opens one conversation, scrolled to one message.
 *
 * ── Why this carries four parameters where [issueUrl] carries one ───────────
 *
 * Because the Messages tab does not exist unless it is asked for. `?forums=1` is
 * the feature's master toggle and `?tab=messages` selects the tab; without both,
 * the client renders the issue tracker exactly as it did before LNL-30 and a bare
 * `?conversation=9` names nothing. So the link has to say all four things, and a
 * link that said only the last two would open the board — a mail whose link
 * silently does nothing being precisely the failure `NotificationEmailTest`
 * exists to catch for issues.
 *
 * This is also the one place the *client-side* master toggle reaches the server.
 * That is not a gate — the API is deliberately not gated on it, see LNL-30 — it
 * is this server writing a URL for a client it knows the shape of, exactly as
 * [issueUrl] writes `?issue=` because `preferredTicket()` reads it. The day the
 * toggle goes away, this line loses a parameter and nothing else changes.
 *
 * @param messageId the message to land on, or null to open the conversation at
 *   the bottom, which is where an unqualified conversation link belongs. Every
 *   caller passes one today; the parameter is nullable so that a link to a
 *   conversation as a whole is expressible without a second function.
 *
 * Nothing here is percent-encoded because nothing here can need it: both values
 * are decimal ids and the two literals are this file's own.
 */
internal fun conversationUrl(baseUrl: String, conversationId: Long, messageId: Long?): String =
    buildString {
        append(baseUrl).append("/?forums=1&tab=messages&conversation=").append(conversationId)
        if (messageId != null) append("&message=").append(messageId)
    }

/**
 * The deep link that opens one forum, and optionally one post inside it.
 *
 * [conversationUrl]'s twin one tab over, and it carries the master toggle and the
 * tab for exactly that function's reason — see it for the argument, which is not
 * repeated here. The tab is `discussion` rather than `messages`, and the ids are
 * the client's `?forum=` and `?post=`; both are read at load by `main.kt` and
 * handed to `ForumBackingViewModel.start`.
 *
 * The forum is never optional and the post always is. A post is addressed
 * *through* its forum — [ForumPostBackingViewModel] takes all three ids because
 * the route does — so a link naming only a post would open the Discussion tab on
 * whichever forum happened to come first and quietly show the wrong room.
 *
 * `?comment=` is deliberately absent, even from the comment notification. It
 * exists and would work, and it would be the wrong thing to send: a mail about a
 * new comment should land the reader at the post, which is the thing they are
 * following. LNL-62 also settled that `?comment=` is a position inside a view
 * rather than a view, and it is never written back — so a reader who scrolled
 * would be carrying a URL that still claimed a comment they had left behind.
 *
 * Nothing here is percent-encoded because nothing here can need it: both values
 * are decimal ids and the literals are this file's own.
 */
internal fun forumUrl(baseUrl: String, forumId: Long, postId: Long? = null): String =
    buildString {
        append(baseUrl).append("/?forums=1&tab=discussion&forum=").append(forumId)
        if (postId != null) append("&post=").append(postId)
    }

/**
 * Minimal HTML escaping for the dynamic parts of a message body.
 *
 * Names, titles and project names are user-controlled and end up inside an HTML
 * e-mail. Mail clients are far more forgiving than a browser, but an unescaped
 * `<` still breaks the markup and a title is exactly where someone would put
 * one. Escaped here rather than trusted.
 *
 * Quotes are escaped too, which text nodes do not need — [anchor] does, and one
 * function that is safe in both places beats two that differ by a character and
 * are chosen correctly only most of the time.
 *
 * `internal` rather than private for that same "one function" reason: the agent
 * mail in AgentMail.kt escapes text an agent wrote, which is if anything the
 * least trustworthy string that reaches a body here. A second escaper living one
 * file away is how the two drift by a character.
 */
internal fun esc(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

/**
 * The issue's reference and title, as one link.
 *
 * The line every notification is really about, so it is written once. [href]
 * lands inside a double-quoted attribute, where a stray `"` would end the
 * attribute and turn the rest into markup; the same [esc] covers that and the
 * text node.
 *
 * `target`/`rel` are deliberately absent: a mail client decides for itself how
 * to open a link, and the attributes are noise it mostly ignores.
 */
private fun anchor(href: String, reference: String, title: String): String =
    "<a href=\"${esc(href)}\">${esc(reference)}: ${esc(title)}</a>"

/**
 * The body of the "somebody filed a new issue" mail.
 *
 * File-level and internal, like the two below, so the *wording* — and above all
 * the link, which is the point of the whole thing — can be asserted without a
 * database, a subscription or a network. The class keeps the part that is
 * genuinely about plumbing: who to send to, and whether there is anywhere to
 * send. See NotificationEmailTest.
 */
internal fun newIssueBody(
    recipientName: String,
    actor: String?,
    projectName: String,
    link: String,
    reference: String,
    title: String,
): String = buildString {
    append("<p>Hi ").append(esc(recipientName)).append(",</p>")
    append("<p>").append(esc(actor ?: "Someone"))
        .append(" created a new issue in ").append(esc(projectName)).append(":</p>")
    append("<p><strong>").append(anchor(link, reference, title)).append("</strong></p>")
    append("<p>You are receiving this because you asked to be notified about new ")
        .append("issues in ").append(esc(projectName))
        .append(". Open the project settings to stop.</p>")
}

/** The body of the "an issue you watch changed" mail. See [newIssueBody]. */
internal fun issueUpdateBody(
    recipientName: String,
    actor: String?,
    summary: String,
    link: String,
    reference: String,
    title: String,
): String = buildString {
    append("<p>Hi ").append(esc(recipientName)).append(",</p>")
    append("<p>").append(esc(actor ?: "Someone")).append(" ").append(esc(summary))
        .append(" ").append(anchor(link, reference, title)).append("</p>")
    append("<p>You are receiving this because you are watching this issue. ")
        .append("Open it to stop.</p>")
}

/** The body of the "this is yours now" mail. See [newIssueBody]. */
internal fun issueAssignedBody(
    assigneeName: String,
    actor: String?,
    projectName: String,
    link: String,
    reference: String,
    title: String,
): String = buildString {
    append("<p>Hi ").append(esc(assigneeName)).append(",</p>")
    append("<p>").append(esc(actor ?: "Someone"))
        .append(" assigned this to you in ").append(esc(projectName)).append(":</p>")
    append("<p><strong>").append(anchor(link, reference, title)).append("</strong></p>")
    // Deliberately no "open the settings to stop" line, unlike the other two.
    // There is nothing to stop: this is not a subscription, and offering an off
    // switch that does not exist would be worse than saying nothing.
    append("<p>You are receiving this because the issue was assigned to you.</p>")
}

/** The body of the "somebody wrote your name" mail. See [newIssueBody]. */
internal fun issueMentionedBody(
    recipientName: String,
    actor: String?,
    context: String,
    projectName: String,
    link: String,
    reference: String,
    title: String,
): String = buildString {
    append("<p>Hi ").append(esc(recipientName)).append(",</p>")
    append("<p>").append(esc(actor ?: "Someone"))
        .append(" mentioned you in ").append(esc(context))
        .append(" on ").append(esc(projectName)).append(":</p>")
    append("<p><strong>").append(anchor(link, reference, title)).append("</strong></p>")
    // No "open the settings to stop", for issueAssignedBody's reason: there is
    // no switch, because being named is not a subscription.
    append("<p>You are receiving this because someone wrote your name with an @ in front of it.</p>")
}

/**
 * The body of the "somebody sent you a private message" mail.
 *
 * ── What is deliberately not in it: the message ─────────────────────────────
 *
 * Every other body in this file quotes the thing it is about — a title, a
 * summary, the reference — and this one carries who wrote it, where it is, and
 * nothing they said. That is not an oversight and it is not for want of the text:
 * the caller is holding the whole body.
 *
 * A private message is private *in Lunicle*, where its readers are exactly the
 * people in the conversation and the routes enforce it. An e-mail is a copy of it
 * in a mailbox nobody chose as the confidential store, forwarded by a chain of
 * servers, sitting in a notification preview on a lock screen. Copying the words
 * out to announce them defeats the thing the feature is for. LNL-60 asks for "an
 * e-mail with a deep link", and a deep link is exactly the right amount: it says
 * there is something to read and takes you to where reading it is protected.
 *
 * LNL-63's forum-watch mail should do the opposite and include the post body,
 * which LNL-30 asks for explicitly. That is not an inconsistency to tidy: a forum
 * post is already readable by everybody who can see the project, so the mail
 * copies nothing that was not public to that audience already.
 *
 * @param senderName who wrote it. Never null, unlike the `actor` the issue bodies
 *   take: a message is always from somebody — an unauthenticated write cannot
 *   reach a conversation it would have to be a participant of.
 * @param others the other people in the conversation besides the recipient and
 *   the sender, so a group message says who is in the room. Empty for a 1:1,
 *   which is the common case and renders as nothing at all.
 */
internal fun newMessageBody(
    recipientName: String,
    senderName: String,
    others: List<String>,
    link: String,
): String = buildString {
    append("<p>Hi ").append(esc(recipientName)).append(",</p>")
    append("<p>").append(esc(senderName)).append(" sent you a message on Lunicle")
    if (others.isNotEmpty()) {
        append(", along with ").append(esc(others.joinToString(", ")))
    }
    append(".</p>")
    append("<p><strong><a href=\"").append(esc(link)).append("\">Read it in Lunicle</a></strong></p>")
    // Deliberately no "open the settings to stop", for issueAssignedBody's
    // reason: there is no switch, because being messaged is not a subscription.
    // Somebody who does not want these has to be told by the person writing them.
    append("<p>You are receiving this because you are in that conversation. ")
        .append("The message itself is not in this e-mail; follow the link to read it.</p>")
}

/**
 * What somebody wrote, quoted into a mail.
 *
 * ── Why the markdown is escaped rather than rendered ────────────────────────
 *
 * LNL-30 asks the forum mails to carry the post body, and the body is markdown
 * from the same editor an issue uses. There is no renderer here: `renderMarkdown`
 * lives in the `client` module, which this one does not depend on and should not
 * start depending on to format an e-mail. The two alternatives were a second
 * markdown implementation on the server — a renderer whose output would drift
 * from the browser's, in the one place nobody would ever compare them — and
 * shipping the raw string into the HTML, which is an injection with extra steps.
 *
 * So it is escaped and put in a blockquote with `white-space: pre-wrap`, and the
 * reader sees the source: `**bold**` arrives as `**bold**`. That is honest, safe,
 * and legible for the thing this is actually for — a paragraph or two of prose,
 * read to decide whether to follow the link. Somebody who wants it rendered
 * clicks through, which is what the link under it is for.
 *
 * Inline styles rather than a class, because an e-mail has no stylesheet: mail
 * clients strip `<style>` blocks unpredictably and every one of them supports a
 * `style` attribute.
 */
private fun quoted(body: String): String =
    "<blockquote style=\"margin:0 0 1em;padding:.5em 1em;border-left:3px solid #ccc;white-space:pre-wrap\">" +
        esc(body) + "</blockquote>"

/**
 * The body of the "somebody posted in a forum you watch" mail.
 *
 * File-level and internal like every body above, so the wording and the link can
 * be asserted without a database or a network. See [newIssueBody].
 *
 * **This one quotes what was written, and [newMessageBody] deliberately does
 * not.** That is not an inconsistency between two mails that should agree: a
 * forum post is readable by everybody who can see the project, so quoting it into
 * a mail to somebody who asked to be told about it copies nothing that was not
 * already theirs to read. A private message is private, and the argument is made
 * at length on [newMessageBody].
 */
internal fun newForumPostBody(
    recipientName: String,
    actor: String?,
    forumName: String,
    title: String,
    body: String,
    link: String,
): String = buildString {
    append("<p>Hi ").append(esc(recipientName)).append(",</p>")
    append("<p>").append(esc(actor ?: "Someone"))
        .append(" posted in ").append(esc(forumName)).append(":</p>")
    append("<p><strong><a href=\"").append(esc(link)).append("\">").append(esc(title)).append("</a></strong></p>")
    append(quoted(body))
    append("<p>You are receiving this because you are watching ").append(esc(forumName))
        .append(". Open the forum to stop.</p>")
}

/**
 * The body of the "somebody commented on a post you watch" mail.
 *
 * The post's title is the link, not the comment's own text, because the post is
 * the thing being followed and a thread is read from the post down. See
 * [forumUrl] for why no `?comment=` is sent.
 */
internal fun newForumCommentBody(
    recipientName: String,
    actor: String?,
    title: String,
    body: String,
    link: String,
): String = buildString {
    append("<p>Hi ").append(esc(recipientName)).append(",</p>")
    append("<p>").append(esc(actor ?: "Someone")).append(" commented on ")
        .append("<strong><a href=\"").append(esc(link)).append("\">").append(esc(title))
        .append("</a></strong>:</p>")
    append(quoted(body))
    append("<p>You are receiving this because you are watching this post. Open it to stop.</p>")
}

// ── Mailbox-proof codes ──────────────────────────────────────────────────────
//
// The three below belong to [EmailCodeService] rather than to any notifier, and
// they live here because the *idiom* is this file's: buildString, [esc] over
// every dynamic part, and a body plain enough to survive a mail client. They are
// the one set of bodies that is not a courtesy — somebody is watching a spinner
// waiting for them — which is why they alone also have a plain-text twin.

/**
 * What one line of a code mail says it is about, given the purpose.
 *
 * One function rather than a `when` at each of the three call sites, so the two
 * purposes cannot drift into describing themselves differently in the subject
 * and the body of the same message.
 */
private fun codePurposeSummary(purpose: EmailCodePurpose): String = when (purpose) {
    EmailCodePurpose.SIGN_IN -> "sign in to Lunicle"
    EmailCodePurpose.EMAIL_CHANGE -> "confirm this address on your Lunicle account"
}

/**
 * The subject line of a code mail — **with the code in it**.
 *
 * Deliberately, and it is the one piece of wording here that is load-bearing. A
 * six-digit code in the subject is readable straight off a notification preview
 * or a lock screen, so the common case never involves opening the message at
 * all. Every other mail this server sends puts its payload in the body; this one
 * is being raced against a form on another screen.
 */
internal fun emailCodeSubject(code: String, purpose: EmailCodePurpose): String = when (purpose) {
    EmailCodePurpose.SIGN_IN -> "$code is your Lunicle sign-in code"
    EmailCodePurpose.EMAIL_CHANGE -> "$code confirms your Lunicle e-mail address"
}

/**
 * The HTML body of a code mail.
 *
 * No link, anywhere, and that is the point rather than an omission: this whole
 * mechanism is a typed code precisely so that nothing has to be clicked in a
 * different tab — see [EmailCodeService]'s preamble for the MCP authorize page
 * that makes it structural. [baseUrl] appears only as the plain name of the site
 * the code is for, so a reader can tell a code they asked for from one they did
 * not.
 *
 * The "if you did not ask" line is the only thing standing between a user and an
 * unexplained code, and it says do nothing rather than offering an action —
 * because there is no action, and an unrequested code expires by itself.
 */
internal fun emailCodeBody(code: String, purpose: EmailCodePurpose, baseUrl: String): String = buildString {
    append("<p>Use this code to ").append(esc(codePurposeSummary(purpose))).append(":</p>")
    append("<p style=\"font-size:28px;letter-spacing:6px;font-weight:bold\">").append(esc(code)).append("</p>")
    append("<p>It works once, and expires in 15 minutes.</p>")
    append("<p>If you did not ask for this, you can ignore this message — the code is useless ")
        .append("to anyone who does not have it, and nothing has changed on ").append(esc(baseUrl))
        .append(".</p>")
}

/**
 * The plain-text alternative to [emailCodeBody].
 *
 * Every message this app sent before LNL-76 was HTML-only with no alternative
 * part, which spam filters score against. That penalty was worth accepting for a
 * notification and is not worth accepting here: a spam-filed notification is an
 * annoyance, a spam-filed sign-in code is a lockout with no fallback. Same words,
 * no markup, and no escaping — [esc] would put `&amp;` in front of a reader.
 */
internal fun emailCodeText(code: String, purpose: EmailCodePurpose, baseUrl: String): String = buildString {
    append("Use this code to ").append(codePurposeSummary(purpose)).append(":\n\n")
    append("    ").append(code).append("\n\n")
    append("It works once, and expires in 15 minutes.\n\n")
    append("If you did not ask for this, you can ignore this message — the code is useless ")
        .append("to anyone who does not have it, and nothing has changed on ").append(baseUrl).append(".\n")
}

/**
 * The body of the "your address has been replaced" mail, sent to the **old** one.
 *
 * The only notification in this file whose recipient is not a subscriber, a
 * participant or a watcher — it goes to an address that is, by the time it
 * arrives, no longer on the account. That is the point: a change the user did not
 * make must be visible to the person it was made *away from*, and after the write
 * the old mailbox is the only place left that reaches them.
 *
 * It says what happened and nothing about how to undo it, because there is no
 * self-service undo — the new address is verified, so its holder controls the
 * account. "Get in touch" is the honest instruction, and inventing a recovery
 * link here would be inventing exactly the unverified back door LNL-71 removed.
 *
 * The new address is named. It is going to somebody who, in the bad case, did not
 * make this change and needs to be able to say what it was changed to.
 */
internal fun emailChangedBody(recipientName: String, newAddress: String): String = buildString {
    append("<p>Hi ").append(esc(recipientName)).append(",</p>")
    append("<p>The e-mail address on your Lunicle account has been changed to <strong>")
        .append(esc(newAddress)).append("</strong>, and confirmed from that mailbox. ")
        .append("This message is the last one this address will receive.</p>")
    append("<p>If that was you, there is nothing to do. If it was not, get in touch with whoever ")
        .append("runs this Lunicle — the new address has been proved, so it cannot be undone from ")
        .append("here.</p>")
}

// ── In-app notification lines (LNL-109) ──────────────────────────────────────
//
// The twin of each e-mail body above, adapted for the bell's sidebar: one plain
// line, names already resolved, and — the load-bearing difference — never a
// message or comment body. An e-mail is a copy in a mailbox and may quote what was
// said; a notification is a pointer that says *that* something was said and takes
// you to where reading it is in place. These are file-level and internal like the
// bodies, so the wording is asserted without a database (see NotificationEmailTest),
// and they return plain text: the client sets them as textContent, so nothing here
// is escaped and an unescaped `<` in a title is shown, not interpreted.

/** "Someone created LNL-42: Title" — the line for a new issue in a watched project. */
internal fun newIssueNotificationTitle(actor: String?, reference: String, title: String): String =
    "${actor ?: "Someone"} created $reference: $title"

/** "Someone edited LNL-42: Title" — [summary] is the same verb the e-mail subject uses. */
internal fun issueUpdateNotificationTitle(actor: String?, summary: String, reference: String, title: String): String =
    "${actor ?: "Someone"} $summary $reference: $title"

/** "Someone assigned LNL-42 to you: Title". */
internal fun issueAssignedNotificationTitle(actor: String?, reference: String, title: String): String =
    "${actor ?: "Someone"} assigned $reference to you: $title"

/** "Someone mentioned you on LNL-42: Title". */
internal fun issueMentionedNotificationTitle(actor: String?, reference: String, title: String): String =
    "${actor ?: "Someone"} mentioned you on $reference: $title"

/**
 * "Grace sent you a message" — deliberately no body and no participant list.
 *
 * The privacy argument on [newMessageBody] applies harder here: a notification is
 * even more glanceable than an e-mail, so it says who and nothing they wrote. The
 * sender is never null — a message is always from a participant.
 */
internal fun newMessageNotificationTitle(senderName: String): String =
    "$senderName sent you a message"

/** "Someone posted in General: Post title" — the room and the thread, no body. */
internal fun newForumPostNotificationTitle(actor: String?, forumName: String, title: String): String =
    "${actor ?: "Someone"} posted in $forumName: $title"

/** "Someone commented on Post title" — the thread, no body. */
internal fun newForumCommentNotificationTitle(actor: String?, title: String): String =
    "${actor ?: "Someone"} commented on $title"

/**
 * The plumbing every notification shares, with no opinion about what happened.
 *
 * ── The seam LNL-63 reuses ──────────────────────────────────────────────────
 *
 * Extracted out of [NotificationService] by LNL-60, which was the second feature
 * to need mail and therefore the first that could see which parts were about
 * issues and which were about *sending*. Everything here is the second kind:
 *
 *  - naming the actor, so a body can say who did it;
 *  - turning account ids into an *audience* — everyone but the actor, addressed or
 *    not — so a caller can write an in-app notification for all of them and mail
 *    the subset with an address;
 *  - sending an e-mail, or logging when the deployment configured no sender;
 *  - recording an in-app notification, or doing nothing when there is no store.
 *
 * Nothing in it mentions an issue, a conversation or a post, and nothing in it
 * needs to change for a third or fourth feature to use it. See this file's
 * preamble for the whole shape.
 *
 * Stateless, so sharing one instance between the notifiers is a convenience for
 * `Application.module`'s readability rather than a requirement.
 *
 * @param users to turn an id into a name and an address.
 * @param sender the active transport, or null when the deployment configured no
 *   mail — in which case a composed message is logged rather than sent. See
 *   [EmailTransport] and [resolveEmailTransport].
 * @param notifications the in-app notification store (LNL-109), or null in a test
 *   that constructs no database — in which case [record] does nothing, the same
 *   way a null [sender] logs rather than sends. In every real deployment it is
 *   present, and an in-app notification is written whether or not mail is
 *   configured: the bell needs no transport.
 */
open class NotificationDispatcher(
    private val users: se.soderbjorn.lunicle.store.UserStore,
    private val sender: EmailTransport?,
    private val notifications: se.soderbjorn.lunicle.store.NotificationStore? = null,
) {
    private val logger = LoggerFactory.getLogger("NotificationDispatcher")

    /** What to call whoever caused this, or null for an unauthenticated write. */
    suspend fun actorName(actorId: Long?): String? =
        actorId?.let { users.findById(it)?.resolvedName }

    /**
     * The audience among [userIds]: everyone but [exceptId], addressed or not.
     *
     * One narrowing, the one every notification in this server has always made:
     * **never [exceptId]** — you are not notified about your own action, the
     * subscription queries' `user_id != ?` for the callers whose set is not a
     * subscription. The *address* is no longer narrowed here (LNL-109): a member
     * with no e-mail is kept, carried as a [NotificationRecipient] with a null
     * [NotificationRecipient.email], so the caller can still write them an in-app
     * row. The mail split — [NotificationRecipient.asEmailRecipient] — is the
     * caller's.
     *
     * @return them in the order [userIds] were given, so a caller that sorted its
     *   ids gets a sorted audience.
     */
    suspend fun audience(userIds: Collection<Long>, exceptId: Long?): List<NotificationRecipient> =
        userIds.filter { it != exceptId }.mapNotNull { id ->
            val user = users.findById(id) ?: return@mapNotNull null
            NotificationRecipient(userId = user.id, name = user.resolvedName, email = user.email)
        }

    /**
     * Record one in-app notification for [userId], or do nothing when this
     * dispatcher was built with no store (a test with no database).
     *
     * Deliberately *not* gated on [sender]: an in-app notification is written even
     * on a deployment that configured no mail, because the bell does not need a
     * mailbox. The two side effects are independent — [send] mails, this records —
     * and a caller fires both, one per addressed recipient and one per audience
     * member.
     */
    suspend fun record(userId: Long, notification: NewNotification) {
        notifications?.record(userId, notification)
    }

    /**
     * Send one message, or log it when there is no sender.
     *
     * Per-recipient failures are swallowed: one address Resend rejects must not
     * stop the rest of a batch, and the send is a courtesy on top of a write that
     * already succeeded — see IssueRepository.notify. The provider's own words are
     * logged by the [EmailTransport]; here we only note which recipient was affected.
     *
     * `open`, and this is the one method in this file that is — for
     * `ForumWatchTest`, which needs the *real* [ForumNotificationService] over the
     * real subscription tables with only the last inch replaced, because the
     * recipient rules it is testing live inside that service. Passing a null
     * sender would log rather than send, which is nearly the same thing and gives
     * a test nothing to assert against but log output. An interface for the whole
     * dispatcher was the alternative and is more machinery than one overridable
     * method for one test; if a second reason ever appears, that is the change.
     */
    open suspend fun send(recipient: EmailRecipient, subject: String, html: String) {
        val sender = sender
        if (sender == null) {
            // No mail configured: the message is fully composed and would be sent,
            // but this deployment has nowhere to send it. Logged, not dropped
            // silently, so a dev can see the feature firing.
            logger.info("[email:unsent] to=<{}> subject=\"{}\" (email not configured)", recipient.email, subject)
            return
        }
        runCatching { sender.send(to = recipient.email, subject = subject, html = html) }
            .onFailure { logger.warn("Notification to <${recipient.email}> was not sent: ${it.message}") }
    }
}

/**
 * The one thing a private conversation does that produces an e-mail.
 *
 * An interface for [IssueNotifier]'s reason: the routes fire the *event* without
 * depending on the mail machinery, so a test uses [NoMessageNotifications] and
 * constructs nothing. Narrow on purpose — one method, because there is one event.
 * See this file's preamble for why this is a second interface rather than a wider
 * first one.
 */
interface MessageNotifier {
    /**
     * A message was published: e-mail everybody else in the conversation.
     *
     * **Not a subscription**, like [IssueNotifier.issueAssigned] and for a
     * stronger version of its reason. There is nothing to opt into: a message
     * addressed to you that you are not told about is a message that does not
     * arrive. So no [SubscriptionStore] is consulted, and the only thing that can
     * stop it is having no address on file.
     *
     * @param message the published message. Its `conversationId` is what the link
     *   is built from; its body is deliberately *not* put in the mail — see
     *   [newMessageBody].
     * @param participantIds everybody in the conversation, the sender included.
     *   Passed in rather than read here, because the route that fires this has
     *   already resolved them to answer the permission question, and re-reading
     *   them would be a second answer to "who is in this room" that could differ
     *   from the one the write was authorised against.
     * @param actorId who sent it. Excluded from the recipients: nobody needs an
     *   e-mail telling them what they just typed.
     */
    suspend fun messageSent(message: MessageRecord, participantIds: Set<Long>, actorId: Long?)
}

/** The no-op message notifier: the default for callers that do not send e-mail (tests). */
object NoMessageNotifications : MessageNotifier {
    override suspend fun messageSent(message: MessageRecord, participantIds: Set<Long>, actorId: Long?) {}
}

/**
 * Turns a sent message into the e-mails it should produce.
 *
 * Thin, which is the point: everything that is not "what does this mail say" is
 * [NotificationDispatcher]'s, and this class is the remaining forty lines that
 * are genuinely about conversations. [NotificationService] is the same shape,
 * seen after the extraction.
 *
 * @param users to name the sender and the other people in the room. The
 *   dispatcher resolves the *recipients* itself; this is for the body's prose.
 * @param dispatch the shared plumbing. See [NotificationDispatcher].
 * @param baseUrl the site the links point at. See [resolvePublicBaseUrl] for why
 *   this is configuration rather than the origin of the request that caused it.
 */
class MessageNotificationService(
    private val users: se.soderbjorn.lunicle.store.UserStore,
    private val dispatch: NotificationDispatcher,
    private val baseUrl: String = resolvePublicBaseUrl() ?: "",
) : MessageNotifier {
    /**
     * Everybody in the conversation except the sender gets one mail, naming the
     * sender and linking the message.
     *
     * The recipient's own name is left out of `others` as well as the sender's,
     * so a three-way conversation tells Ada that Grace wrote to her *and Charles*
     * — rather than to her, Charles and herself, which reads as a bug in a
     * sentence people will see often.
     */
    override suspend fun messageSent(message: MessageRecord, participantIds: Set<Long>, actorId: Long?) {
        val audience = dispatch.audience(participantIds.sorted(), actorId)
        if (audience.isEmpty()) return

        // "Someone" is unreachable here in practice — a message can only be
        // written by a participant, and a participant is an account — but the
        // fallback is kept rather than asserted away: the sender's account being
        // deleted between the write and this line is a race nobody should have to
        // reason about while reading a mailer.
        val senderName = dispatch.actorName(actorId) ?: "Someone"
        val link = conversationUrl(baseUrl, message.conversationId, message.id)
        val subject = "New message from $senderName"
        // The in-app line is the same for every recipient — who sent it, no body,
        // no participant list — so it is built once. See newMessageNotificationTitle.
        val notification = NewNotification(
            kind = NotificationKind.MESSAGE,
            title = newMessageNotificationTitle(senderName),
            conversationId = message.conversationId,
            messageId = message.id,
        )
        // Resolved once for the whole batch rather than per recipient: a group
        // conversation is the case this costs anything in, and it is the case
        // where every recipient needs the same list minus themselves.
        val names = participantIds.mapNotNull { id -> users.findById(id)?.let { id to it.resolvedName } }.toMap()
        audience.forEach { member ->
            // In-app for everyone in the room; see the audience/mail split in
            // NotificationDispatcher. A participant with no address still gets the bell.
            dispatch.record(member.userId, notification)
            member.asEmailRecipient()?.let { recipient ->
                dispatch.send(
                    recipient,
                    subject,
                    newMessageBody(
                        recipientName = recipient.name,
                        senderName = senderName,
                        others = participantIds
                            .filter { it != actorId && it != recipient.userId }
                            .mapNotNull { names[it] }
                            .sorted(),
                        link = link,
                    ),
                )
            }
        }
    }
}

/**
 * The two forum events that produce an e-mail.
 *
 * The third narrow interface in this file, added by LNL-63 exactly as LNL-60's
 * handover described: it sits beside [IssueNotifier] and [MessageNotifier] rather
 * than being folded into either, and it needed **no change to
 * [NotificationDispatcher]** to build. See this file's preamble.
 *
 * Two methods rather than one, unlike [MessageNotifier], because there really are
 * two events with two audiences: the people watching a *room*, and the people
 * watching a *thread*. They are as different as [IssueNotifier.issueCreated] and
 * [IssueNotifier.issueUpdated], and for the same reason — one is about a
 * container, the other about one thing inside it.
 *
 * Both take the resolved records rather than ids, deliberately. The routes that
 * fire these have already read every one of them to answer the permission
 * question, and re-reading them here would be a second answer to "which forum is
 * this, and whose project is it" that could differ from the one the write was
 * checked against. [MessageNotifier.messageSent] takes its participant set for
 * the same reason.
 */
interface ForumNotifier {
    /**
     * A post was published: e-mail everybody watching the forum, minus [actorId].
     *
     * Fired on the transition out of draft and never on a re-save, which is
     * [IssueNotifier.issueCreated]'s rule and is the caller's to enforce — see
     * ForumPostRoutes' `PUT`. A post is published once; a `PUT` may arrive many
     * times.
     *
     * @param project the post's project, carried because *visibility* is checked
     *   here at send time. A watcher who has since lost sight of the project is
     *   dropped rather than mailed — LNL-63's one acceptance criterion that a
     *   subscribe-time check could not satisfy. See [ForumNotificationService].
     */
    suspend fun postPublished(
        project: ProjectRecord,
        forum: ForumRecord,
        post: ForumPostRecord,
        actorId: Long?,
    )

    /**
     * A comment was published: e-mail everybody watching the post, minus [actorId].
     *
     * Not the forum's watchers. Watching a room is a request to hear when
     * somebody starts something, not to receive every reply in it — a busy forum
     * would otherwise make its own watch button the first thing anybody turns
     * off. Somebody who wants the whole thread watches the post, which is what
     * that control is for, and a post's author is subscribed to their own at
     * publish.
     */
    suspend fun commentPublished(
        project: ProjectRecord,
        forum: ForumRecord,
        post: ForumPostRecord,
        comment: ForumCommentRecord,
        actorId: Long?,
    )
}

/** The no-op forum notifier: the default for callers that do not send e-mail (tests). */
object NoForumNotifications : ForumNotifier {
    override suspend fun postPublished(
        project: ProjectRecord,
        forum: ForumRecord,
        post: ForumPostRecord,
        actorId: Long?,
    ) = Unit

    override suspend fun commentPublished(
        project: ProjectRecord,
        forum: ForumRecord,
        post: ForumPostRecord,
        comment: ForumCommentRecord,
        actorId: Long?,
    ) = Unit
}

/**
 * Turns a published post or comment into the e-mails it should produce.
 *
 * Thin, like [MessageNotificationService] and for its reason: everything that is
 * not "what does this mail say" is [NotificationDispatcher]'s. The one thing
 * genuinely new here is the visibility narrowing below, which no other notifier
 * in this file needs.
 *
 * @param subscriptions who is watching what. The two `audienceFor…` reads it
 *   uses already drop addressless accounts and the actor; see Subscriptions.sq.
 * @param audience "who can see this project", as a set. The other half of the
 *   visibility rule — see [recipientsIn].
 * @param dispatch the shared plumbing, unchanged by this feature.
 * @param baseUrl the site the links point at. See [resolvePublicBaseUrl].
 */
class ForumNotificationService(
    private val subscriptions: se.soderbjorn.lunicle.store.SubscriptionStore,
    private val audience: ProjectAudience,
    private val dispatch: NotificationDispatcher,
    private val baseUrl: String = resolvePublicBaseUrl() ?: "",
) : ForumNotifier {

    override suspend fun postPublished(
        project: ProjectRecord,
        forum: ForumRecord,
        post: ForumPostRecord,
        actorId: Long?,
    ) {
        val members =
            recipientsIn(project) { subscriptions.audienceForForumNewPost(forum.id, actorId) }
        if (members.isEmpty()) return

        val actor = dispatch.actorName(actorId)
        val link = forumUrl(baseUrl, forum.id, post.id)
        val subject = "[${forum.name}] New post: ${post.title}"
        val notification = NewNotification(
            kind = NotificationKind.FORUM_POST,
            title = newForumPostNotificationTitle(actor, forum.name, post.title),
            projectId = project.id,
            forumId = forum.id,
            postId = post.id,
        )
        members.forEach { member ->
            dispatch.record(member.userId, notification)
            member.asEmailRecipient()?.let { recipient ->
                dispatch.send(
                    recipient,
                    subject,
                    newForumPostBody(
                        recipientName = recipient.name,
                        actor = actor,
                        forumName = forum.name,
                        title = post.title,
                        body = post.body,
                        link = link,
                    ),
                )
            }
        }
    }

    override suspend fun commentPublished(
        project: ProjectRecord,
        forum: ForumRecord,
        post: ForumPostRecord,
        comment: ForumCommentRecord,
        actorId: Long?,
    ) {
        val members =
            recipientsIn(project) { subscriptions.audienceForForumPost(post.id, actorId) }
        if (members.isEmpty()) return

        val actor = dispatch.actorName(actorId)
        val link = forumUrl(baseUrl, forum.id, post.id)
        val subject = "[${forum.name}] New comment: ${post.title}"
        val notification = NewNotification(
            kind = NotificationKind.FORUM_COMMENT,
            title = newForumCommentNotificationTitle(actor, post.title),
            projectId = project.id,
            forumId = forum.id,
            postId = post.id,
        )
        members.forEach { member ->
            dispatch.record(member.userId, notification)
            member.asEmailRecipient()?.let { recipient ->
                dispatch.send(
                    recipient,
                    subject,
                    newForumCommentBody(
                        recipientName = recipient.name,
                        actor = actor,
                        title = post.title,
                        body = comment.body,
                        link = link,
                    ),
                )
            }
        }
    }

    /**
     * The watchers who may still see [project], out of whoever [watchers] names.
     *
     * ── Why the check is here and not at subscribe time ─────────────────────
     *
     * Because a subscription outlives the reason somebody was allowed to make it.
     * Since LNL-57, seeing a project is *membership*, and membership is revoked by
     * an administrator in the settings dialog — a gesture that has no idea any
     * subscription row exists anywhere. A check made only when the watch button
     * was pressed would keep mailing that person the contents of a project they
     * can no longer open, and every mail would carry a link that answers 404.
     * LNL-63 lists this as an acceptance criterion for exactly that reason.
     *
     * Deleting their subscription rows on revocation was the alternative, and it
     * is worse in the way that matters: it is destructive, it would have to be
     * remembered by every path that can remove a role, and re-granting access
     * would silently not restore what somebody had asked for.
     *
     * ── Why it costs nothing on the common path ─────────────────────────────
     *
     * The `watchers` lambda runs first and is usually empty — no watchers, no
     * question to ask, and [ProjectAudience.forProject] (which reads the whole
     * user table on a public project) is never called. On a project with
     * watchers it is one read of a list the forum pane fetches on every render
     * anyway.
     */
    private suspend fun recipientsIn(
        project: ProjectRecord,
        watchers: suspend () -> List<NotificationRecipient>,
    ): List<NotificationRecipient> {
        val candidates = watchers()
        if (candidates.isEmpty()) return emptyList()
        val visible = audience.forProject(project).mapTo(mutableSetOf()) { it.id }
        return candidates.filter { it.userId in visible }
    }
}

/**
 * The two issue events that produce an e-mail, as [IssueRepository] sees them.
 *
 * An interface so the repository can depend on the *events* without depending on
 * the mail machinery. [NotificationService] is the one real implementation.
 */
interface IssueNotifier {
    /** A new issue was published; e-mail the project's new-issue watchers, minus [actorId]. */
    suspend fun issueCreated(issue: IssueRecord, actorId: Long?)

    /** An issue changed; e-mail the issue's watchers, minus [actorId]. [summary] is what happened. */
    suspend fun issueUpdated(issue: IssueRecord, actorId: Long?, summary: String)

    /**
     * An issue was handed to [assigneeId]; tell them, unless they did it themselves.
     *
     * ── The one notification that is not a subscription ─────────────────────────
     *
     * The other two mail people who *asked* to hear. This one mails somebody
     * because work was put on their desk, which is not a thing you opt into — an
     * assignment nobody told you about is an assignment that does not happen. So
     * it deliberately does not consult [SubscriptionStore] at all, and the only
     * thing that can stop it is having no address on file.
     *
     * Fired only when the assignee actually *changes* to somebody, which is the
     * caller's job to determine — see [IssueRepository.save] and the assignee
     * route. Re-saving an issue without touching the field must not re-mail the
     * person who already holds it, and clearing an assignee mails nobody: the
     * point is to tell someone they have work, and "you no longer have this" is
     * not that.
     *
     * @param assigneeId who now holds it. Non-null by construction: an unassignment
     *   is not an event this fires for.
     * @param actorId who did it, or null for an unauthenticated write. **Equal to
     *   [assigneeId] is the self-assignment case and sends nothing** — somebody who
     *   just clicked "Assign to me" does not need an e-mail telling them so.
     */
    suspend fun issueAssigned(issue: IssueRecord, assigneeId: Long, actorId: Long?)

    /**
     * Text was written on an issue: mail everyone it `@mentions` who was not
     * mentioned by the text it replaced.
     *
     * ── Why the old text is a parameter ─────────────────────────────────────
     *
     * The second notification here that is not a subscription — being named is
     * not something you opt into — but unlike [issueAssigned] it is fired by a
     * write that repeats itself. The editor sends the whole description on every
     * save, so a typo fix six weeks later carries every mention the description
     * has ever held. Mailing on the *text* would re-summon everybody named in it
     * each time anyone touched it, which trains people to ignore the mail.
     *
     * So the rule is the same one [issueAssigned] uses, stated over a set
     * instead of a value: mail the mentions that are **new**. [previousBody] is
     * empty for text that did not exist before (a published draft, a first
     * comment), which makes every mention in it new — correct, and not a special
     * case here.
     *
     * @param body the text as it now stands.
     * @param previousBody the text it replaced, or "" if there was none.
     * @param actorId who wrote it. Never mailed: mentioning yourself in your own
     *   comment is a thing people do, and telling them about it is noise.
     * @param context a fragment naming where the mention is — "the description",
     *   "a comment" — dropped into the message.
     */
    suspend fun issueMentioned(
        issue: IssueRecord,
        body: String,
        previousBody: String,
        actorId: Long?,
        context: String,
    )
}

/** The no-op notifier: the default for callers that do not send e-mail (tests). */
object NoNotifications : IssueNotifier {
    override suspend fun issueCreated(issue: IssueRecord, actorId: Long?) {}
    override suspend fun issueUpdated(issue: IssueRecord, actorId: Long?, summary: String) {}
    override suspend fun issueAssigned(issue: IssueRecord, assigneeId: Long, actorId: Long?) {}
    override suspend fun issueMentioned(
        issue: IssueRecord,
        body: String,
        previousBody: String,
        actorId: Long?,
        context: String,
    ) {}
}

/**
 * Turns an issue event into the e-mails it should produce.
 *
 * The one place that knows both halves — who is subscribed (via
 * [SubscriptionStore]) and what the message says. An event with no subscribers
 * costs one indexed query and sends nothing.
 *
 * @param subscriptions who wants which e-mails.
 * @param projects to resolve an issue's project (for the reference and the name).
 * @param users to name the actor in the body — and, for [issueMentioned], to turn
 *   a name somebody typed back into an address.
 * @param roles with [users], the answer to "who may be mentioned on this
 *   project". Read through [mentionableUsersIn] rather than queried here, so this
 *   resolves against exactly the set the browser's autocomplete offered.
 * @param instanceSettings the third store [mentionableUsersIn] needs, for the owner's
 *   id (LNL-201). Here only to be passed through: an owner reads every project, so a
 *   mailer that could not see ownership would decline to resolve `@them` on a board
 *   they hold no row on — while the autocomplete, reading the same function, offered
 *   the name. Which is the disagreement that function exists to prevent.
 * @param dispatch the shared plumbing: naming the actor, and sending or logging.
 *   Was an [EmailTransport] and two private methods here until LNL-60 needed the
 *   same three things for a feature that is not about issues; see
 *   [NotificationDispatcher] and this file's preamble.
 * @param baseUrl the site the issue links point at. See [resolvePublicBaseUrl] for
 *   why this is configuration rather than the origin of the request that caused
 *   the mail.
 */
class NotificationService(
    private val subscriptions: se.soderbjorn.lunicle.store.SubscriptionStore,
    private val projects: se.soderbjorn.lunicle.store.ProjectStore,
    private val users: se.soderbjorn.lunicle.store.UserStore,
    private val roles: se.soderbjorn.lunicle.store.RoleStore,
    private val instanceSettings: se.soderbjorn.lunicle.store.InstanceSettingsStore,
    private val dispatch: NotificationDispatcher,
    private val baseUrl: String = resolvePublicBaseUrl() ?: "",
) : IssueNotifier {

    /**
     * A new issue was published in a project: e-mail everyone watching that
     * project for new issues, except [actorId] who created it.
     */
    override suspend fun issueCreated(issue: IssueRecord, actorId: Long?) {
        val project = projects.findById(issue.projectId) ?: return
        val audience = subscriptions.audienceForProjectNewIssue(project.id, actorId)
        if (audience.isEmpty()) return

        val actor = actorName(actorId)
        val reference = "${project.namePrefix}-${issue.number}"
        val link = issueUrl(baseUrl, reference)
        val subject = "[$reference] New issue: ${issue.title}"
        val notification = NewNotification(
            kind = NotificationKind.ISSUE_CREATED,
            title = newIssueNotificationTitle(actor, reference, issue.title),
            projectId = project.id,
            issueId = issue.id,
        )
        audience.forEach { member ->
            dispatch.record(member.userId, notification)
            member.asEmailRecipient()?.let { recipient ->
                dispatch(
                    recipient,
                    subject,
                    newIssueBody(
                        recipientName = recipient.name,
                        actor = actor,
                        projectName = project.name,
                        link = link,
                        reference = reference,
                        title = issue.title,
                    ),
                )
            }
        }
    }

    /**
     * An issue changed: e-mail everyone watching that issue, except [actorId].
     *
     * @param summary a short phrase for what happened — "edited", "moved",
     *   "commented on" — dropped into the subject and body.
     */
    override suspend fun issueUpdated(issue: IssueRecord, actorId: Long?, summary: String) {
        val project = projects.findById(issue.projectId) ?: return
        val audience = subscriptions.audienceForIssueUpdate(issue.id, actorId)
        if (audience.isEmpty()) return

        val actor = actorName(actorId)
        val reference = "${project.namePrefix}-${issue.number}"
        val link = issueUrl(baseUrl, reference)
        val subject = "[$reference] $summary: ${issue.title}"
        val notification = NewNotification(
            kind = NotificationKind.ISSUE_UPDATED,
            title = issueUpdateNotificationTitle(actor, summary, reference, issue.title),
            projectId = project.id,
            issueId = issue.id,
        )
        audience.forEach { member ->
            dispatch.record(member.userId, notification)
            member.asEmailRecipient()?.let { recipient ->
                dispatch(
                    recipient,
                    subject,
                    issueUpdateBody(
                        recipientName = recipient.name,
                        actor = actor,
                        summary = summary,
                        link = link,
                        reference = reference,
                        title = issue.title,
                    ),
                )
            }
        }
    }

    /**
     * Somebody was handed an issue: tell them, unless they handed it to themselves.
     *
     * The self-assignment guard is the first line and is the whole of the rule the
     * issue asks for. Note it compares ids rather than "did this request come from
     * the assignee", which is the same thing here and stays the same thing under
     * impersonation: [actorId] is the *effective* user, so an admin wearing
     * somebody's face and assigning that person's own issues to them sends no mail
     * — which is right, because as far as every other part of this server is
     * concerned that person did it.
     *
     * No subscription is consulted; see [IssueNotifier.issueAssigned]. The one
     * thing that silently stops this is an account that has since been deleted — a
     * return rather than a failure, an assignment not undone by being
     * unannounceable. A missing address no longer stops it: since LNL-109 the
     * in-app notification is written regardless, and only the e-mail waits on an
     * address.
     *
     * A watcher who is also the new assignee will receive this *and* the ordinary
     * "assigned" update mail the caller fires alongside it. Accepted rather than
     * fixed: excluding a second person from `recipientsForIssueUpdate` means a
     * second parameter through a SQL query used by every other update, to spare a
     * duplicate that needs somebody to be watching an issue *before* it was handed
     * to them — which the auto-watch (the author, at publish) does not produce.
     */
    override suspend fun issueAssigned(issue: IssueRecord, assigneeId: Long, actorId: Long?) {
        if (assigneeId == actorId) return
        val assignee = users.findById(assigneeId) ?: return
        val project = projects.findById(issue.projectId) ?: return

        val actor = actorName(actorId)
        val reference = "${project.namePrefix}-${issue.number}"
        val link = issueUrl(baseUrl, reference)
        dispatch.record(
            assignee.id,
            NewNotification(
                kind = NotificationKind.ISSUE_ASSIGNED,
                title = issueAssignedNotificationTitle(actor, reference, issue.title),
                projectId = project.id,
                issueId = issue.id,
            ),
        )
        val email = assignee.email ?: return
        dispatch(
            EmailRecipient(userId = assignee.id, email = email, name = assignee.resolvedName),
            "[$reference] Assigned to you: ${issue.title}",
            issueAssignedBody(
                assigneeName = assignee.resolvedName,
                actor = actor,
                projectName = project.name,
                link = link,
                reference = reference,
                title = issue.title,
            ),
        )
    }

    /**
     * Somebody was named in text: tell them, once, the first time they are named.
     *
     * Three things narrow the recipients, in this order, and each is doing work:
     *
     *  1. **Only the newly mentioned.** The set in [previousBody] is subtracted
     *     from the set in [body] — see [IssueNotifier.issueMentioned] for why a
     *     re-save must not re-mail. Note this runs the *same* matcher over both,
     *     so a mention that merely moved within the text is not new.
     *  2. **Only real people here.** Names are matched against
     *     [mentionableUsersIn], so `@everyone` mails nobody and neither does
     *     `@Ada` on a board Ada has no role on. The mailer and the autocomplete
     *     read the same list, so a name the popup offered always resolves.
     *  3. **Never the author.** People name themselves while writing, and being
     *     e-mailed about your own sentence is pure noise.
     *
     * Two accounts sharing a display name both get mailed, which is the honest
     * reading of an ambiguous mention: the writer meant one of them and there is
     * no way to tell which, so telling both beats telling the wrong one. See
     * [mentionedNames], which collapses the duplicate name and leaves this
     * decision here.
     *
     * An account with no address on file still gets the in-app notification (its
     * bell needs no mailbox, per LNL-109); only the e-mail waits on an address.
     */
    override suspend fun issueMentioned(
        issue: IssueRecord,
        body: String,
        previousBody: String,
        actorId: Long?,
        context: String,
    ) {
        // Cheapest possible early out, and the common one: most text has no "@"
        // in it at all, and this spares every ordinary save two store reads.
        if (!body.contains('@')) return
        val candidates = mentionableUsersIn(issue.projectId, users, roles, instanceSettings)
        if (candidates.isEmpty()) return
        val names = candidates.map { it.resolvedName }

        val fresh = mentionedNames(body, names) - mentionedNames(previousBody, names)
        if (fresh.isEmpty()) return

        val project = projects.findById(issue.projectId) ?: return
        val actor = actorName(actorId)
        val reference = "${project.namePrefix}-${issue.number}"
        val link = issueUrl(baseUrl, reference)
        val notification = NewNotification(
            kind = NotificationKind.ISSUE_MENTIONED,
            title = issueMentionedNotificationTitle(actor, reference, issue.title),
            projectId = project.id,
            issueId = issue.id,
        )
        // By name, case-insensitively, because that is how the text matched:
        // `@ada` reaching Ada must reach her here too.
        candidates
            .filter { candidate -> fresh.any { it.equals(candidate.resolvedName, ignoreCase = true) } }
            .filter { it.id != actorId }
            .forEach { recipient ->
                dispatch.record(recipient.id, notification)
                val email = recipient.email ?: return@forEach
                dispatch(
                    EmailRecipient(userId = recipient.id, email = email, name = recipient.resolvedName),
                    "[$reference] You were mentioned: ${issue.title}",
                    issueMentionedBody(
                        recipientName = recipient.resolvedName,
                        actor = actor,
                        context = context,
                        projectName = project.name,
                        link = link,
                        reference = reference,
                        title = issue.title,
                    ),
                )
            }
    }

    /**
     * Send one message, or log it when there is no sender.
     *
     * A one-line forward since LNL-60. The body of it moved to
     * [NotificationDispatcher.send] unchanged, and this stayed rather than every
     * call site below growing a `dispatch.` — five call sites is exactly the
     * number where a rename to the shared thing stops being a readability
     * improvement and starts being noise.
     */
    private suspend fun dispatch(recipient: EmailRecipient, subject: String, html: String) =
        dispatch.send(recipient, subject, html)

    private suspend fun actorName(actorId: Long?): String? = dispatch.actorName(actorId)
}
