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
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/**
 * Format an epoch-millis timestamp as "17 Jul 2026, 14.32".
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
    // Both fields padded. The minute must be — 14.9 is not a time anybody writes
    // — and the hour must be for the reason the format exists at all: these are
    // scanned down a column, and an unpadded "8.56" shifts every character after
    // it half a step left of the "14.32" above.
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "${local.day} $month ${local.year}, $hour.$minute"
}
