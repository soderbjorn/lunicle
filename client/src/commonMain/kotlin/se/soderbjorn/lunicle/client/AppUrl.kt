/**
 * The address bar, as a value rather than as a side effect.
 *
 * Lunicle keeps the URL pointing at what is on screen — the focused issue, the
 * picked project, and since LNL-30 the active tab — so that a reload, a bookmark
 * or a pasted link comes back to the same view. That behaviour used to live
 * entirely inside `main.kt`'s `syncUrl`, wrapped around `URLSearchParams` and
 * `history.replaceState`, where none of it could be tested: the interesting
 * claims are all about what happens to *other people's* parameters, and asserting
 * those needs a function that takes a query string and returns one.
 *
 * So the decision is here and the effect stays there. [nextSearch] is the whole
 * rule; `main.kt` calls it and, if it answers, hands the result to
 * `replaceState`.
 *
 * ── Two properties this must not lose ───────────────────────────────────────
 *
 * **Unknown parameters are preserved.** The embed's `?project=<name>` is set by
 * whoever wrote the `<iframe src>`, and blowing it away would break the embed on
 * the first focus change. The forum master toggle `?forums=1` is in the same
 * position, and so is `?message=`, which is read once at load and never written.
 * None of them is enumerated below — anything this function was not asked about
 * is copied through untouched, which is why a *new* parameter needs no change
 * here to survive.
 *
 * **Values are never re-encoded.** Parameters are split on `&` and `=` and their
 * values carried across as the exact substrings they arrived as. Decoding and
 * re-encoding would round-trip somebody else's parameter through this build's
 * idea of escaping, and the one thing worse than dropping the embed's parameter
 * is handing it back subtly different. Lunicle's own values — a ticket like
 * `LMX-12`, a decimal id, a tab key — need no escaping at all, which is what
 * makes the shortcut safe rather than merely convenient.
 *
 * @see se.soderbjorn.lunicle.client.viewmodel.ShellTab
 */
package se.soderbjorn.lunicle.client

/**
 * Split a query string into its parameters, in order.
 *
 * @param search the query, with or without its leading `?`. Empty yields an
 *   empty list.
 * @return name to raw value. A parameter with no `=` gets an empty value, and a
 *   value containing `=` keeps every one after the first — `a=b=c` is `a` to
 *   `b=c`, which is what every other parser does and what round-tripping
 *   requires.
 */
fun parseQuery(search: String): List<Pair<String, String>> =
    search.removePrefix("?").split("&")
        .filter { it.isNotEmpty() }
        .map { part ->
            val at = part.indexOf('=')
            if (at < 0) part to "" else part.substring(0, at) to part.substring(at + 1)
        }

/** Join parameters back into a query string, without the leading `?`. */
fun formatQuery(parameters: List<Pair<String, String>>): String =
    parameters.joinToString("&") { (name, value) -> if (value.isEmpty()) name else "$name=$value" }

/** The raw value of [name] in [search], or null if it is not there. */
fun queryValue(search: String, name: String): String? =
    parseQuery(search).firstOrNull { it.first == name }?.second

/**
 * The query string the address bar should hold, or **null when it already holds
 * it**.
 *
 * The null is the point rather than an optimisation: `main.kt` calls this on
 * every state emission, and rewriting the URL to the value it already has is a
 * `replaceState` per keystroke.
 *
 * @param search the current query string.
 * @param ticket the focused issue's ticket, or null to remove `?issue=`. The one
 *   parameter that is *cleared* by a null, because "no issue is focused" is a
 *   state the URL has to be able to express.
 * @param projectId the picked project, or null meaning **leave it alone** — not
 *   "clear it". The board is null before the first load returns and while a
 *   project is being switched to, and blanking the URL on those would lose the
 *   very thing being restored if the page were reloaded mid-flight.
 * @param tab the active tab's key, or null meaning leave it alone, for
 *   [projectId]'s reason plus one of its own: with the master toggle off there
 *   are no tabs, and a `?tab=` somebody typed anyway is then just another
 *   unknown parameter, which this function preserves rather than tidies.
 * @param conversation the open conversation's id, or null to remove
 *   `?conversation=`. The **second** parameter a null clears, and the only one
 *   besides [ticket] — because "no conversation is open" is a state the URL has
 *   to be able to express, exactly as "no issue is focused" is.
 *
 *   Note the consequence, which is accepted rather than worked around: with the
 *   master toggle off nothing ever opens a conversation, so a `?conversation=9`
 *   somebody typed by hand is removed on the first sync. That is the honest
 *   answer — with the feature off, the parameter names nothing — and it is what
 *   distinguishes a *known* parameter from the unknown ones this function
 *   preserves.
 *
 *   There is deliberately no `message` parameter here, though the deep link has
 *   one. `?message=` says which message to scroll to on a cold load, which is a
 *   position inside a view rather than a view; writing it back would make the URL
 *   change as somebody scrolled, and a link copied mid-thread would reopen at a
 *   message nobody chose. It is read at load and never written. See main.kt.
 * @param forum the Discussion tab's selected forum, or null to remove `?forum=`.
 *   A **view**, so it behaves like [ticket] and [conversation] rather than like
 *   [projectId] — the same split, one tab over. Written whenever a forum is
 *   selected, which on the Discussion tab is essentially always: which room you are
 *   in is part of what is on screen, and a URL that omitted it would come back to a
 *   different forum than the one that was copied.
 * @param post the open post, or null to remove `?post=`. A view for [forum]'s
 *   reason, and null when the post window is closed — which is a state the URL has
 *   to be able to express, or closing the window would leave a link that reopens it.
 *
 *   There is deliberately no `comment` parameter, though the deep link has one, and
 *   it is exactly the `message` case above: a comment is a position inside a post.
 *   Read at load, never written, and preserved here as any unknown parameter is.
 * @return the new query string without its `?`, or null if nothing changed.
 */
fun nextSearch(
    search: String,
    ticket: String?,
    projectId: Long?,
    tab: String?,
    conversation: String? = null,
    forum: String? = null,
    post: String? = null,
): String? {
    val current = parseQuery(search)
    val project = projectId?.toString()

    fun valueOf(name: String) = current.firstOrNull { it.first == name }?.second
    val issueChanged = valueOf("issue") != ticket
    val projectChanged = project != null && valueOf("projectId") != project
    val tabChanged = tab != null && valueOf("tab") != tab
    val conversationChanged = valueOf("conversation") != conversation
    val forumChanged = valueOf("forum") != forum
    val postChanged = valueOf("post") != post
    if (!issueChanged && !projectChanged && !tabChanged && !conversationChanged &&
        !forumChanged && !postChanged
    ) {
        return null
    }

    // Rebuild in the original order, so a parameter that was already there stays
    // where it was. Only genuinely new ones land at the end — a URL whose
    // parameters shuffle on every focus change is one nobody can diff by eye.
    val wanted = buildList {
        if (ticket != null) add("issue" to ticket)
        if (project != null) add("projectId" to project)
        if (tab != null) add("tab" to tab)
        if (conversation != null) add("conversation" to conversation)
        if (forum != null) add("forum" to forum)
        if (post != null) add("post" to post)
    }
    val written = mutableSetOf<String>()
    val result = mutableListOf<Pair<String, String>>()
    current.forEach { (name, value) ->
        val replacement = wanted.firstOrNull { it.first == name }
        when {
            replacement != null -> {
                result.add(replacement)
                written.add(name)
            }
            // `issue`, `conversation`, `forum` and `post` are the names a null
            // argument removes — the four that name something being *looked at*.
            // Every other parameter here, known or not, is carried across; see
            // their @param docs for the split, which is views against positions.
            name == "issue" && ticket == null -> Unit
            name == "conversation" && conversation == null -> Unit
            name == "forum" && forum == null -> Unit
            name == "post" && post == null -> Unit
            else -> result.add(name to value)
        }
    }
    wanted.filterNot { it.first in written }.forEach { result.add(it) }
    return formatQuery(result)
}
