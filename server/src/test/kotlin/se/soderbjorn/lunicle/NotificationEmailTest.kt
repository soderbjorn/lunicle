/**
 * The pure decisions the e-mail feature rests on, tested without a network or a
 * database: whether a string the User tab submitted is worth trying to send to,
 * and what the deep link in a notification actually says.
 *
 * Both are the sort of logic that is invisible when it is subtly wrong — a valid
 * address refused, a pasted sentence accepted, a link that 404s only for the one
 * project whose prefix has a space in it — and neither is reachable from a
 * manual flow without sending real mail. So they are pinned here.
 *
 * @see isPlausibleEmail
 * @see issueUrl
 */
package se.soderbjorn.lunicle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationEmailTest {

    // ── The User tab's e-mail shape check ─────────────────────────────────────

    @Test
    fun `ordinary addresses are accepted`() {
        assertTrue(isPlausibleEmail("robert@example.com"))
        assertTrue(isPlausibleEmail("a.b+tag@sub.domain.co.uk"))
    }

    @Test
    fun `obvious non-addresses are refused`() {
        assertFalse(isPlausibleEmail("no-at-sign"), "A string with no @ passed.")
        assertFalse(isPlausibleEmail("@example.com"), "An address with no local part passed.")
        assertFalse(isPlausibleEmail("me@nodot"), "A domain with no dot passed.")
        assertFalse(isPlausibleEmail("me@ends.with.dot."), "A domain ending in a dot passed.")
        assertFalse(isPlausibleEmail("two@@example.com"), "Two @ signs passed.")
        assertFalse(isPlausibleEmail("has space@example.com"), "An address with a space passed.")
    }

    // ── The deep link a notification carries ──────────────────────────────────

    @Test
    fun `an issue link is the client's own deep-link parameter`() {
        // `?issue=` and the ticket form are the web client's, not this file's:
        // see preferredTicket() in the client's main.kt. If this assertion is
        // ever "fixed" to something else, the link stops opening the issue.
        assertEquals(
            "https://example.com/?issue=LNL-18",
            issueUrl("https://example.com", "LNL-18"),
        )
    }

    @Test
    fun `a base URL keeps exactly one slash before the query`() {
        // A value pasted into Railway with a trailing slash is likelier than not.
        assertEquals(
            "https://example.com/?issue=LNL-1",
            issueUrl("https://example.com".trimEnd('/'), "LNL-1"),
        )
        assertEquals(
            "https://example.com/?issue=LNL-1",
            issueUrl("https://example.com/".trimEnd('/'), "LNL-1"),
        )
    }

    @Test
    fun `an awkward ticket prefix is encoded rather than interpolated`() {
        // A prefix is only required to be non-blank — see ProjectRepository's
        // validate — so these are reachable, and an unencoded `&` would silently
        // truncate the parameter.
        assertEquals("https://x.dev/?issue=MY+APP-3", issueUrl("https://x.dev", "MY APP-3"))
        assertEquals("https://x.dev/?issue=A%26B-3", issueUrl("https://x.dev", "A&B-3"))
    }

    @Test
    fun `the base URL is null when nothing configures it`() {
        // Guarded rather than asserted flat: this reads the real environment, and
        // a developer who exports LUNICLE_PUBLIC_BASE_URL for their own run should
        // get a skipped assertion, not a red test in a file they did not touch.
        val overridden = System.getProperty("lunicle.publicBaseUrl")?.isNotBlank() == true ||
            System.getenv("LUNICLE_PUBLIC_BASE_URL")?.isNotBlank() == true
        if (overridden) return
        // No hardcoded fallback origin: an unset base URL is null, and the operator
        // is expected to set it. See resolvePublicBaseUrl.
        assertNull(resolvePublicBaseUrl())
    }

    // ── The private-message deep link (LNL-60) ───────────────────────────────

    /**
     * A message link carries the master toggle and the tab, not just the ids.
     *
     * The failure this catches is silent and would only ever be found by clicking
     * a real e-mail: without `?forums=1&tab=messages` the client renders the issue
     * tracker exactly as it did before LNL-30, and `?conversation=` names nothing
     * at all. The link would open, look fine, and show the board.
     *
     * Pinned against the literal URL rather than against a builder, for the reason
     * the issue link above is: these four parameters are the *client's*, read by
     * `preferredConversation` and `preferredTab` in the web module's main.kt. If
     * this assertion is ever "fixed" to something else, the link stops opening the
     * message.
     */
    @Test
    fun `a conversation link carries the toggle, the tab and both ids`() {
        assertEquals(
            "https://example.com/?forums=1&tab=messages&conversation=9&message=42",
            conversationUrl("https://example.com", 9, 42),
        )
    }

    /** ...and drops `?message=` when there is no particular one to land on. */
    @Test
    fun `a conversation link without a message names only the conversation`() {
        assertEquals(
            "https://example.com/?forums=1&tab=messages&conversation=9",
            conversationUrl("https://example.com", 9, null),
        )
    }

    // ── The forum deep link (LNL-63) ─────────────────────────────────────────

    /**
     * A forum link carries the master toggle and the **discussion** tab.
     *
     * The same silent failure the conversation link above guards against, one tab
     * over: without `?forums=1&tab=discussion` the client renders the issue
     * tracker exactly as it did before LNL-30, and a bare `?forum=` names nothing
     * at all. A mail whose link opens the board, looks fine and is broken is
     * something only a real click would find.
     *
     * Note the tab is `discussion`, not `messages`. Copying [conversationUrl] and
     * forgetting that one word produces a link that opens the wrong tab of the
     * right feature — which is the most plausible way for this to be wrong, and
     * the least likely to be noticed while reading the diff.
     */
    @Test
    fun `a forum post link carries the toggle, the discussion tab and both ids`() {
        assertEquals(
            "https://example.com/?forums=1&tab=discussion&forum=3&post=7",
            forumUrl("https://example.com", 3, 7),
        )
    }

    /** ...and names only the forum when there is no particular post. */
    @Test
    fun `a forum link without a post names only the forum`() {
        assertEquals(
            "https://example.com/?forums=1&tab=discussion&forum=3",
            forumUrl("https://example.com", 3),
        )
    }

    // ── The mails themselves ─────────────────────────────────────────────────

    private val link = issueUrl("https://example.com", "LNL-18")

    /**
     * Every notification carries a clickable reference.
     *
     * One test over all of them rather than one each, because the requirement is
     * exactly "all of them" — the failure this guards against is a further mail
     * added later that quietly reverts to plain text. Which is exactly what
     * happened: the mention mail (LNL-25) was written against a `main` this
     * change had never reached, in plain text, and this list is what would have
     * caught it. Add the body of every new notification here.
     */
    @Test
    fun `every notification links the issue`() {
        val bodies = listOf(
            newIssueBody("Robert", "Alice", "Lunicle", link, "LNL-18", "Deep links"),
            issueUpdateBody("Robert", "Alice", "moved", link, "LNL-18", "Deep links"),
            issueAssignedBody("Robert", "Alice", "Lunicle", link, "LNL-18", "Deep links"),
            issueMentionedBody("Robert", "Alice", "a comment", "Lunicle", link, "LNL-18", "Deep links"),
        )
        bodies.forEach { body ->
            assertTrue(
                body.contains("<a href=\"https://example.com/?issue=LNL-18\">LNL-18: Deep links</a>"),
                "A notification body did not link the issue: $body",
            )
        }
    }

    /**
     * The private-message mail links the conversation, and says nothing else.
     *
     * Two assertions, and the *second* is the one with a decision behind it. Every
     * other body in EmailNotifier.kt quotes what it is about; this one deliberately
     * does not, because a private message copied into an e-mail is a copy of the
     * private thing in a mailbox nobody chose as the confidential store — see
     * `newMessageBody`. That is exactly the sort of decision a later "why not
     * include a preview, it would be friendlier" would undo without noticing, so it
     * is pinned rather than left in prose.
     */
    @Test
    fun `the message notification links the conversation and quotes nothing`() {
        val messageLink = conversationUrl("https://example.com", 9, 42)
        val body = newMessageBody(
            recipientName = "Robert",
            senderName = "Alice",
            others = listOf("Grace"),
            link = messageLink,
        )
        // `esc`, not the raw link: this URL has three `&` in it, and an `&` inside
        // an HTML attribute is escaped on the way out. Asserting the raw form would
        // pass only if the escaper stopped running — which is the opposite of what
        // this file wants to be true.
        assertTrue(
            body.contains("<a href=\"${esc(messageLink)}\">"),
            "The message mail did not link the conversation: $body",
        )
        assertTrue(body.contains("Grace"), "A group message did not name the other people in it: $body")
    }

    /**
     * ...and it must not start quoting one by accident.
     *
     * Asserted over a body that would be unmistakable if it leaked. A mail that
     * grew a preview would still pass every other test in this file.
     */
    @Test
    fun `a message body never reaches the notification`() {
        val secret = "the-password-is-hunter2"
        val body = newMessageBody(
            recipientName = "Robert",
            senderName = "Alice",
            others = emptyList(),
            link = conversationUrl("https://example.com", 9, 42),
        )
        // The body is not even a parameter — which is the point, and is what makes
        // this assertion a statement about the signature rather than about a
        // truncation somebody could raise.
        assertFalse(body.contains(secret), "A message's text reached its notification: $body")
    }

    /** A sender's name is escaped, exactly as an issue title is. */
    @Test
    fun `a sender name cannot break out of the message markup`() {
        val body = newMessageBody(
            recipientName = "Robert",
            senderName = "\"><script>alert(1)</script>",
            others = emptyList(),
            link = link,
        )
        assertFalse(body.contains("<script>"), "A sender's script tag survived into the body: $body")
    }

    /**
     * The forum mails link the post, and — unlike the message mail — quote it.
     *
     * The pair is the point. LNL-30 asks for the post body in the forum mail and
     * `newMessageBody` deliberately withholds the message; asserting both in one
     * file is what keeps somebody from "tidying" the two into agreement without
     * reading why they differ. A forum post is readable by everybody who can see
     * the project, so quoting it copies nothing that was not already theirs.
     */
    @Test
    fun `the forum notifications link the post and quote what was written`() {
        val forumLink = forumUrl("https://example.com", 3, 7)
        val post = newForumPostBody(
            recipientName = "Robert",
            actor = "Alice",
            forumName = "General",
            title = "Deep links",
            body = "Here is the whole thing I wrote.",
            link = forumLink,
        )
        val comment = newForumCommentBody(
            recipientName = "Robert",
            actor = "Alice",
            title = "Deep links",
            body = "And here is my reply.",
            link = forumLink,
        )
        listOf(post, comment).forEach { body ->
            // `esc`, not the raw link: this URL has three `&` in it, and an `&`
            // inside an HTML attribute is escaped on the way out. See the message
            // test above, which says the same at greater length.
            assertTrue(
                body.contains("<a href=\"${esc(forumLink)}\">Deep links</a>"),
                "A forum notification did not link the post: $body",
            )
        }
        assertTrue(post.contains("Here is the whole thing I wrote."), "The post body was not quoted: $post")
        assertTrue(comment.contains("And here is my reply."), "The comment was not quoted: $comment")
    }

    /**
     * Markdown in a quoted body is escaped, not rendered and not passed through.
     *
     * There is no markdown renderer on the server — see `quoted` — so the body
     * arrives as its source, and the thing that must not happen is the *other*
     * failure: a post whose body is an HTML tag reaching a mail client as markup.
     * A post body is the least constrained string in this feature and the easiest
     * place to put one.
     */
    @Test
    fun `a post body cannot break out of the quote`() {
        val body = newForumPostBody(
            recipientName = "Robert",
            actor = "Alice",
            forumName = "General",
            title = "Harmless",
            body = "</blockquote><script>alert(1)</script>",
            link = forumUrl("https://example.com", 1, 1),
        )
        assertFalse(body.contains("<script>"), "A post body's script tag survived into the mail: $body")
        assertTrue(body.contains("&lt;script&gt;"), "The body was not escaped as expected: $body")
    }

    // ── In-app notification lines (LNL-109) ──────────────────────────────────

    /**
     * The bell's lines are one glanceable sentence each, with the actor and the
     * reference the row needs and nothing more. Pinned as literals because these
     * are what the panel shows, and a builder that quietly changed shape would be
     * invisible until somebody read a real list.
     */
    @Test
    fun `notification titles read as one glanceable line`() {
        assertEquals(
            "Alice created LNL-18: Deep links",
            newIssueNotificationTitle("Alice", "LNL-18", "Deep links"),
        )
        assertEquals(
            "Alice moved LNL-18: Deep links",
            issueUpdateNotificationTitle("Alice", "moved", "LNL-18", "Deep links"),
        )
        assertEquals(
            "Alice assigned LNL-18 to you: Deep links",
            issueAssignedNotificationTitle("Alice", "LNL-18", "Deep links"),
        )
        assertEquals(
            "Alice mentioned you on LNL-18: Deep links",
            issueMentionedNotificationTitle("Alice", "LNL-18", "Deep links"),
        )
        assertEquals(
            "Alice posted in General: Deep links",
            newForumPostNotificationTitle("Alice", "General", "Deep links"),
        )
        assertEquals(
            "Alice commented on Deep links",
            newForumCommentNotificationTitle("Alice", "Deep links"),
        )
    }

    /** A null actor becomes "Someone", exactly as the e-mail bodies do. */
    @Test
    fun `a null actor becomes Someone in a notification line`() {
        assertEquals(
            "Someone created LNL-18: Deep links",
            newIssueNotificationTitle(null, "LNL-18", "Deep links"),
        )
    }

    /**
     * The message notification names the sender and carries no body — the same
     * privacy decision `newMessageBody` makes, made harder because a notification is
     * even more glanceable than an e-mail. The builder does not even take a body
     * parameter, which is what makes this a statement about the signature rather
     * than about a truncation somebody could later relax.
     */
    @Test
    fun `the message notification names the sender and quotes nothing`() {
        val secret = "the-password-is-hunter2"
        val title = newMessageNotificationTitle("Alice")
        assertEquals("Alice sent you a message", title)
        assertFalse(title.contains(secret), "A message's text reached its notification line: $title")
    }

    @Test
    fun `a title cannot break out of the markup`() {
        // The title is whatever the reporter typed. Inside the anchor's text it
        // is a text node, but it sits a few characters from an attribute.
        val body = newIssueBody(
            recipientName = "Robert",
            actor = "Alice",
            projectName = "Lunicle",
            link = link,
            reference = "LNL-18",
            title = "\"><script>alert(1)</script>",
        )
        assertFalse(body.contains("<script>"), "A title's script tag survived into the body: $body")
        assertTrue(body.contains("&quot;&gt;&lt;script&gt;"), "The title was not escaped as expected: $body")
    }
}
