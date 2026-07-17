/**
 * The two facts about an attachment that both sides must agree on, exactly.
 *
 * ── Why these are here and not on one side ───────────────────────────────────
 *
 * Attachments are the one feature where the client and the server each make a
 * decision, separately, that only works if the two decisions are *identical*:
 *
 *  - The client decides how to spell an upload in markdown — `![name](url)` for
 *    something the browser will draw, `[name](url)` for something it will
 *    download. See `attachmentMarkdown` in :client.
 *  - The server decides how to serve the bytes back — `Content-Disposition:
 *    inline` for the same small set of types, `attachment` for everything else.
 *    See `BoardRoutes.attachmentRoutes`.
 *
 * Split those two lists across two modules and they drift, and the drift is
 * silent in both directions: an `<img>` pointing at bytes the server insists on
 * downloading is a broken image in someone's issue, and a *download link* to
 * bytes the server serves inline is a file that opens in the tab instead of
 * saving. One list, read by both, is the only way that stays true.
 *
 * The same argument puts the size limit and its refusal sentence here. The
 * client turns an oversized file away before uploading it and the server turns
 * it away after, and the two must agree on the number *and* on the wording —
 * "That file is too big" from one and "Maximum size exceeded" from the other is
 * the same refusal reported as two different bugs.
 *
 * That makes this the one place in :clientServer holding a sentence a user
 * reads, which the house rule otherwise keeps in view models. The rule earns its
 * exception here: the alternative is the same sentence written twice, in two
 * modules, and drifting.
 *
 * @see ApiRoutes.attachment
 */
package se.soderbjorn.lunicle.clientserver

import kotlin.math.roundToLong

/**
 * How big an upload may be.
 *
 * ── The two numbers this sits between ───────────────────────────────────────
 *
 * A limit is not optional: without one, the upload routes are an unbounded write
 * to a volume whose free-trial ceiling is measured in hundreds of megabytes, and
 * the first person to drag a film in fills it. It is also the number that stops
 * the server buffering a body it cannot hold — see `receiveUpload`.
 *
 * 25 MB is a deliberate widening of the 10 MB this had while only images were
 * allowed. Now that a PDF, a zip of logs or a short screen recording are all
 * legitimate, 10 MB refuses things people will reasonably try; 25 MB takes the
 * screen recording and still means twenty of them do not fill the volume. It is
 * emphatically *not* enough for a 4 GB movie, which is the point — that upload
 * gets a sentence back rather than a full disk.
 *
 * Shared rather than server-only so the client can refuse an obviously-too-big
 * file before spending five minutes uploading it, quoting the same number the
 * server would have. The client check is a courtesy; the server's is the rule.
 */
const val MAX_ATTACHMENT_BYTES: Long = 25L * 1024 * 1024

/**
 * The types served from our own origin with `Content-Disposition: inline`, and
 * the only types the editor will spell as an `<img>`.
 *
 * ── This list is a security control. Read before adding to it. ──────────────
 *
 * `GET /api/attachments/<id>` serves user-uploaded bytes from
 * `lunicle.lunamux.dev`, and that URL is deliberately shareable — it appears
 * inside rendered markdown. Anything served *inline* from that origin runs in
 * that origin, with that origin's cookies, including `lunicle_session`. So an
 * inline `text/html` upload is stored XSS by way of the attach button, and the
 * only reason this feature can accept arbitrary files at all is that everything
 * not on this list is served as a download instead.
 *
 * Every entry is therefore a format that is **data and not a document**: a
 * rectangle of pixels with no scripting, no external references, and no way to
 * express a link. A browser handed one can draw it or fail; it cannot execute
 * it.
 *
 * **`image/svg+xml` is deliberately absent, despite being an image.** An SVG is
 * a document — it carries `<script>`, `<foreignObject>` and event handlers — so
 * serving one inline is exactly the `text/html` hole wearing a friendlier
 * extension. An uploaded SVG is a download, and looks it. Anyone who wants SVGs
 * to render inline must first serve attachments from a separate origin, where
 * "runs with our cookies" stops being true.
 *
 * `image/bmp` stays despite not being one of the four formats anyone still
 * produces: it is as inert as the others, and dropping it would turn every BMP
 * already stored into a download for no gain in safety.
 */
val INLINE_IMAGE_MIME_TYPES: Set<String> = setOf(
    "image/png",
    "image/jpeg",
    "image/gif",
    "image/webp",
    "image/bmp",
)

/**
 * Whether these bytes may be shown rather than downloaded.
 *
 * @param mimeType a *claim* — the uploader's `Content-Type`, or the column
 *   written from it. Never verified against the bytes, and it does not need to
 *   be: a caller who lies and calls their HTML `image/png` gets it served as
 *   `image/png`, which is inert, plus `X-Content-Type-Options: nosniff` so the
 *   browser cannot go looking for the truth. Lying only ever makes your own
 *   file useless.
 *
 * The parameters are dropped before matching because a browser sends
 * "image/png" where a script may send "image/png; charset=binary", and the
 * parameters are not part of the identity being checked.
 */
fun isInlineImageType(mimeType: String): Boolean =
    normaliseMimeType(mimeType) in INLINE_IMAGE_MIME_TYPES

/**
 * A `Content-Type` reduced to the `type/subtype` this system stores.
 *
 * Lowercased and stripped of parameters, so the stored column holds one spelling
 * of each type rather than however many the callers happen to send. It is also
 * what keeps a caller's header out of a response header: whatever arrives, what
 * is stored — and later echoed back as `Content-Type` — is this.
 */
fun normaliseMimeType(mimeType: String): String =
    mimeType.substringBefore(';').trim().lowercase()

/**
 * The sentence to show for a file too big to upload, or null if it fits.
 *
 * ── Why the client checks a rule the server enforces ────────────────────────
 *
 * It does not enforce anything — [MAX_ATTACHMENT_BYTES] is the server's rule and
 * the server applies it to bytes that have actually arrived, which is the only
 * check that counts. This is about the *shape of the failure*. Someone who drags
 * in a 4 GB video and gets no answer for six minutes, then a 400, has been
 * failed twice: they waited, and they burned four gigabytes of somebody's
 * mobile data to be told no. Asking the question before the upload starts makes
 * the refusal instant.
 *
 * Both numbers, both spelled the same way: "That file is 4.1 GB" without the
 * limit leaves the user guessing what would fit, and the limit without the size
 * leaves them arguing with it.
 */
fun tooLargeMessage(byteSize: Long): String? {
    if (byteSize <= MAX_ATTACHMENT_BYTES) return null
    return "That file is ${formatByteSize(byteSize)}, and the limit is " +
        "${formatByteSize(MAX_ATTACHMENT_BYTES)}. Link to it instead, or send a smaller version."
}

/**
 * A byte count as a person reads it — "412 bytes", "8.1 kB", "2.3 MB", "4.1 GB".
 *
 * Powers of two under decimal names, which is the lie every file manager tells
 * and therefore the number the user will see if they look at the same file on
 * their own disk. Being technically right here — 26.2 MB where Finder says 25 —
 * would only make the two disagree.
 *
 * The GB rung exists for one caller: [tooLargeMessage], which has to name the
 * size of a file this app will never store. Nothing that gets uploaded reaches
 * it, and "4096.0 MB" is not a sentence anyone reads as four gigabytes.
 *
 * One decimal place: "8.1 kB" is a size, "8.132 kB" is a measurement, and
 * nobody decides whether to click based on the third digit.
 */
fun formatByteSize(bytes: Long): String {
    val kilo = 1024.0
    val mega = kilo * 1024
    val giga = mega * 1024
    return when {
        // "1 byte", not "1 bytes". A one-byte upload is a rounding error of a
        // file, but the plural is the kind of thing that makes an app look
        // machine-written.
        bytes < 1024 -> if (bytes == 1L) "1 byte" else "$bytes bytes"
        bytes < 1024 * 1024 -> "${oneDecimal(bytes / kilo)} kB"
        bytes < 1024 * 1024 * 1024 -> "${oneDecimal(bytes / mega)} MB"
        else -> "${oneDecimal(bytes / giga)} GB"
    }
}

/**
 * A double to one decimal place, without `String.format`.
 *
 * Which does not exist in common Kotlin — it is a JVM API, and this module
 * compiles for the browser too. Rounding by hand and joining the halves is the
 * whole of the workaround, and it is shorter than an `expect`/`actual` pair
 * would be. See Dates.kt, which reaches the same conclusion.
 */
private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10}"
}
