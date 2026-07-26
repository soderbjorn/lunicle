/**
 * The Link-header parse, pinned against a real GitHub response.
 *
 * Its own file because what it covers is not a rule anybody chose — it is a fact
 * about somebody else's API, and the test's value is entirely in the header
 * literal below being one this codebase actually received rather than one
 * somebody wrote from memory.
 *
 * Both assertions here guard against the same class of failure: a wrong commit
 * count that looks exactly like a right one. Nothing errors, nothing logs, and
 * the dialog shows a plausible number. The only thing that will ever catch it is
 * a test that knows the true answer.
 *
 * @see lastPageNumber
 */
package se.soderbjorn.lunicle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubStatisticsParsingTest {
    /**
     * A real header, captured from api.github.com on the JetBrains/kotlin
     * repository. 140136 was the true commit count at that moment.
     *
     * Kept verbatim, including the `rel="next"` link that precedes the one being
     * read and the `per_page=1` that is the whole reason this is delicate.
     */
    private val realHeader =
        """<https://api.github.com/repositories/3432266/commits?sha=master&per_page=1&page=2>; rel="next", """ +
            """<https://api.github.com/repositories/3432266/commits?sha=master&per_page=1&page=140136>; rel="last""""

    /**
     * The count comes from `rel="last"`, not from `rel="next"` and not from
     * `per_page`.
     *
     * Three numbers appear in that header — 1, 2 and 140136 — and two of them are
     * wrong answers that would never look wrong: `per_page=1` yields "1 commit"
     * and the next-link yields "2 commits". Both are entirely plausible on a young
     * repository, which is what makes this worth a test rather than a comment.
     */
    @Test
    fun `the last page number is the commit count`() {
        assertEquals(140136L, lastPageNumber(realHeader))
    }

    /**
     * A windowed request parses the same way, with the `since` parameter sitting
     * between the page number and the relation.
     *
     * Captured from the same repository for the past seven days. This is what the
     * `[^>]*` in the pattern has to tolerate — an encoded timestamp after
     * `page=`... and, in this ordering, before it too.
     */
    @Test
    fun `a windowed request parses despite the since parameter`() {
        val windowed =
            """<https://api.github.com/repositories/3432266/commits?sha=master&per_page=1""" +
                """&since=2026-07-13T13%3A45%3A16Z&page=2>; rel="next", """ +
                """<https://api.github.com/repositories/3432266/commits?sha=master&per_page=1""" +
                """&since=2026-07-13T13%3A45%3A16Z&page=421>; rel="last""""
        assertEquals(421L, lastPageNumber(windowed))
    }

    /**
     * No `rel="last"` means there is no second page, and the caller counts the
     * body instead — 0 or 1 commits.
     *
     * GitHub omits the header entirely for a single-page result, so this is the
     * common shape for a quiet window rather than an edge case.
     */
    @Test
    fun `a single-page result has no last link`() {
        assertNull(lastPageNumber(null))
        assertNull(
            lastPageNumber(
                """<https://api.github.com/repositories/1/commits?per_page=1&page=1>; rel="first"""",
            ),
        )
    }

    /** A malformed header degrades to null rather than throwing. */
    @Test
    fun `a malformed header is not an exception`() {
        assertNull(lastPageNumber("garbage"))
        assertNull(lastPageNumber(""))
    }
}
