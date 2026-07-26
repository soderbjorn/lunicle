/**
 * Turning the epoch millis the server stores into something a person reads.
 *
 * Here, in the shared client, rather than in the JS view — for the same reason
 * every other label lives in a view model. `Date(millis).toLocaleString()` would
 * be one line in MarkdownEditor's neighbourhood and would have to be written
 * again, differently, for iOS; worse, it would put a *decision* (which fields,
 * what order, 24-hour or not) somewhere the tests cannot reach.
 *
 * The format is fixed rather than locale-derived, and that is deliberate. A
 * tracker's timestamps are scanned in a column, not read as prose: "17 Jul 2026,
 * 14.32" is the same width and the same shape for every reader, where a locale
 * format silently becomes 7/17/26 for one visitor and 17.07.2026 for another —
 * and the two disagree about what 07/08 means. 24-hour for the same reason.
 */
package se.soderbjorn.lunicle.client

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/**
 * Format an epoch-millis timestamp as "17 Jul 2026, 14:32".
 *
 * In the reader's own zone, not the server's. The server stores UTC millis and
 * has no idea where anyone is; `currentSystemDefault()` is the browser's zone on
 * JS and the JVM's on the server-side tests. A comment written at 09:00 must say
 * 09:00 to the person who wrote it.
 *
 * The month is a name and not a number on purpose: it is the one field that
 * cannot be misread. 07/08 is two different days depending on who is looking;
 * "7 Aug" is one.
 *
 * @param millis epoch milliseconds, UTC — what every `created_at` in the schema
 *   holds.
 */
@OptIn(ExperimentalTime::class)
fun formatTimestamp(millis: Long): String {
    val local = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
    // `ordinal`, not `month.number` or `monthNumber`: on JVM kotlinx-datetime's
    // Month is a typealias for java.time.Month, whose accessor is `getValue()`
    // and not `number` — so the tidy-looking spelling compiles on JS and fails
    // on the JVM. Enum ordinal is 0-based and identical on both, which is also
    // why MONTHS is indexed from 0 rather than offset by one.
    val month = MONTHS[local.month.ordinal]
    // Both fields padded. The minute must be — 14:9 is not a time anybody writes
    // — and the hour must be for the reason the format exists at all: these are
    // scanned down a column, and an unpadded "8:56" shifts every character after
    // it half a step left of the "14:32" above.
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    // A colon, not a full stop: "14:32" is the shape a time has. (The earlier
    // "14.32" read as a decimal to anyone outside the sv-SE convention.)
    return "${local.day} $month ${local.year}, $hour:$minute"
}

/**
 * How long ago something was, as "4m", "3h", "2d", "5w", "14 Mar 2026".
 *
 * The forum's post card ends in a column saying when the thread was last spoken
 * in, and "is this still moving" is a question about *elapsed* time — an absolute
 * timestamp there would have to be subtracted from now by every reader, in a
 * column two words wide. [formatTimestamp] stays what it is for everywhere the
 * question is "when exactly", which is everywhere else.
 *
 * ── Why it stops being relative after a year ────────────────────────────────
 *
 * Past 52 weeks the answer becomes a date. "63w" is not a duration anybody has an
 * intuition for, and it is also the point at which the reader's question flips
 * back from "recently?" to "when?". Weeks rather than months in between, because a
 * month is not a fixed length and dividing by an average one produces an answer
 * that is wrong in a way nobody can see.
 *
 * Rounded **down** throughout: something 119 minutes old is "1h", not "2h". A
 * relative age that overstates itself reads as staler than it is, and the cost of
 * understating is that the newest possible answer is "0m" — which is why anything
 * under a minute is spelled "now" rather than left as a zero.
 *
 * A future timestamp answers "now" as well. Clock skew between a server and a
 * browser is real and small, and "in -1m" is not something a card should ever say.
 *
 * @param millis epoch milliseconds, UTC.
 * @param now the moment to measure against, defaulted to the caller's clock. A
 *   parameter so the tests can pin an answer without one, which is the only way
 *   this function is testable at all.
 */
@OptIn(ExperimentalTime::class)
fun formatRelative(millis: Long, now: Long = Clock.System.now().toEpochMilliseconds()): String {
    val elapsed = now - millis
    val minutes = elapsed / 60_000
    val hours = minutes / 60
    val days = hours / 24
    val weeks = days / 7
    return when {
        elapsed < 60_000 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        weeks < 52 -> "${weeks}w"
        // Past a year, the date itself — but the day and the month only, since the
        // time of day of something a year old is noise in a two-word column.
        else -> {
            val local = Instant.fromEpochMilliseconds(millis)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            "${local.day} ${MONTHS[local.month.ordinal]} ${local.year}"
        }
    }
}
