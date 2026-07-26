/**
 * The relative clock the forum's post list reads by.
 *
 * [formatRelative] exists because one column in one card asks "is this thread
 * still moving", and every failure it can have is a boundary: the unit it flips
 * at, the direction it rounds, and the two ends where a duration stops being the
 * right answer at all. None of those is visible by looking at the function, and
 * all of them are visible to a reader as a card that says something slightly
 * wrong — "2h" for something an hour and a minute old, or "in -1m" when a
 * browser's clock runs a second ahead of the server's.
 *
 * [formatTimestamp] is not tested here and deliberately: it is a fixed format
 * over a fixed input, and the interesting thing about it — that it is the same
 * shape for every reader — is a property of it having no locale in it.
 *
 * Every case pins `now` explicitly. A relative formatter tested against the wall
 * clock is a test that passes for a while.
 *
 * @see formatRelative
 */
package se.soderbjorn.lunicle.client

import kotlin.test.Test
import kotlin.test.assertEquals

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val WEEK = 7 * DAY

/** An arbitrary but fixed "now": 20 Jul 2026, 12:00 UTC. */
private const val NOW = 1_784_548_800_000L

class DatesTest {

    /**
     * Anything under a minute is "now", including a *future* timestamp.
     *
     * The future case is not hypothetical: the server stamps `created_at` from its
     * own clock and the browser subtracts using its, and the two differ by seconds
     * routinely. Without this the newest possible comment — the one somebody just
     * posted — would be the one most likely to render as nonsense.
     */
    @Test
    fun `under a minute, and anything in the future, reads as now`() {
        assertEquals("now", formatRelative(NOW, NOW))
        assertEquals("now", formatRelative(NOW - 59_000, NOW))
        assertEquals("now", formatRelative(NOW + 5 * MINUTE, NOW))
    }

    /**
     * Each unit runs right up to the next one and then flips, and the flip is
     * exact rather than a hair early or late.
     *
     * Written as pairs on purpose: an off-by-one in a divisor shows up as one of
     * these two lines being wrong, where a single sample in the middle of each
     * range would pass against almost any arithmetic.
     */
    @Test
    fun `the units flip exactly where they should`() {
        assertEquals("1m", formatRelative(NOW - MINUTE, NOW))
        assertEquals("59m", formatRelative(NOW - 59 * MINUTE, NOW))
        assertEquals("1h", formatRelative(NOW - HOUR, NOW))
        assertEquals("23h", formatRelative(NOW - 23 * HOUR, NOW))
        assertEquals("1d", formatRelative(NOW - DAY, NOW))
        assertEquals("6d", formatRelative(NOW - 6 * DAY, NOW))
        assertEquals("1w", formatRelative(NOW - WEEK, NOW))
        assertEquals("51w", formatRelative(NOW - 51 * WEEK, NOW))
    }

    /**
     * It rounds down, always.
     *
     * Something 119 minutes old is "1h". Rounding to nearest would make a thread
     * read as staler than it is for half of every unit, and the only cost of
     * rounding down is that the newest answer is "0m" — which is why under a
     * minute is spelled "now" instead.
     */
    @Test
    fun `an age is rounded down rather than to nearest`() {
        assertEquals("1h", formatRelative(NOW - (2 * HOUR - MINUTE), NOW))
        assertEquals("1d", formatRelative(NOW - (2 * DAY - HOUR), NOW))
    }

    /**
     * Past a year it stops being a duration and becomes a date.
     *
     * "63w" is not something anybody has an intuition for, and it is the point at
     * which the reader's question flips back from "recently?" to "when?". The date
     * is the reader's own, so this asserts the shape and the year rather than the
     * exact day — a test that pinned the day would be asserting the test machine's
     * time zone.
     */
    @Test
    fun `past a year it gives up and shows the date`() {
        val old = formatRelative(NOW - 60 * WEEK, NOW)
        assertEquals(3, old.split(" ").size, "Expected a 'day month year' date, got: $old")
        assertEquals("2025", old.split(" ").last())
    }
}
