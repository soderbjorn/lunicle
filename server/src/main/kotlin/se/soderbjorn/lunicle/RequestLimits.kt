/**
 * How much a request may weigh, and how long a piece of writing may be.
 *
 * ── What was here before: nothing (LUS-30) ──────────────────────────────────
 *
 * Three Ktor plugins were installed and none of them capped a request body. The
 * attachment upload path was the *only* route in the server that pre-checked
 * `Content-Length`; every other body was buffered into the heap in full before any
 * validation ran. Issue descriptions, comment bodies, forum post bodies and
 * private message bodies had no length cap at all — only a blank check. There was
 * a `MAX_TITLE_LENGTH`; there was no maximum description.
 *
 * The unauthenticated half is the sharper one. An anonymous client posts a
 * multi-gigabyte body to `/oauth/register`, the server reads it into the heap of a
 * small container before the JSON parse fails, and the process runs out of memory.
 * No credentials, and repeatable at will. The authenticated variant — a very large
 * issue description — additionally *persists*, filling a volume whose trial ceiling
 * is half a gigabyte.
 *
 * ── Two limits, because they answer different questions ─────────────────────
 *
 * [MAX_REQUEST_BODY_BYTES] is about the process staying alive: nothing may be read
 * into memory that would not fit. It has to sit above the attachment limit, since
 * an upload is a legitimate large body.
 *
 * [MAX_LONG_TEXT_LENGTH] is about what gets *stored*, and is far smaller. A
 * description is meant to hold a lot — that is why it never had a cap, and the
 * reasoning was sound as far as it went — but "a lot" and "unbounded" are not the
 * same promise.
 *
 * @see se.soderbjorn.lunicle.clientserver.MAX_ATTACHMENT_BYTES
 */
package se.soderbjorn.lunicle

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.clientserver.MAX_ATTACHMENT_BYTES

private val logger = LoggerFactory.getLogger("RequestLimits")

/**
 * The most any request body may weigh.
 *
 * Deliberately above [MAX_ATTACHMENT_BYTES] rather than equal to it. This is a
 * backstop against a body nothing else has looked at yet; the attachment rule is a
 * product decision about how big a file may be, checked by the upload route with a
 * message that names both numbers. Making them the same value would mean a
 * 25-megabyte-and-one-byte upload got a bare 413 from here instead of the sentence
 * that tells somebody what to do about it.
 */
const val MAX_REQUEST_BODY_BYTES: Long = 32L * 1024 * 1024

/**
 * The most characters a stored piece of writing may hold — a description, a
 * comment, a forum post, a private message.
 *
 * A hundred thousand is roughly a fifty-page document, which is far past anything
 * anybody types into an issue and far under anything that threatens a volume. The
 * cap is per field rather than per request, so a long description does not make the
 * comment beneath it fail.
 *
 * Refused rather than truncated. Silently storing half of what somebody wrote is
 * the worse failure: they would find out by reading it back later, with no way to
 * recover the rest. The same call the title cap makes.
 */
const val MAX_LONG_TEXT_LENGTH: Int = 100_000

/**
 * Refuse an over-size body **before** anything reads it.
 *
 * ── Why an interceptor and not a check per route ────────────────────────────
 *
 * Because the routes that most need it are the ones nobody thought about. A cap
 * added at each `receive` would cover the bodies somebody remembered, which is the
 * shape of the problem this exists to fix.
 *
 * ── What each of the three cases costs ──────────────────────────────────────
 *
 *  - **A declared length over the ceiling** is a 413 in one round trip, with
 *    nothing read. `Content-Length` is a *claim* and a caller may lie about it —
 *    which is why this is a cheap first line and not the only one; the attachment
 *    path still counts the bytes that actually arrived.
 *  - **A chunked body** declares no length at all, so there is nothing to check
 *    and no bound to enforce short of buffering it to find out. Refused with 411,
 *    which is exactly the call the upload route already makes and documents: our
 *    own client always sends a length, so the only callers turned away are ones
 *    declining to say how big they are, and asking is not a hardship. That is what
 *    closes the bypass — a limit that only reads `Content-Length` is a limit an
 *    attacker opts out of by omitting the header.
 *  - **No length and not chunked** is a request with no body. Allowed, obviously:
 *    every bodyless POST on this server is one.
 */
fun Application.installRequestBodyCeiling(maxBytes: Long = MAX_REQUEST_BODY_BYTES) {
    intercept(ApplicationCallPipeline.Plugins) {
        val refusal = bodyCeilingRefusal(
            method = call.request.httpMethod,
            contentLength = call.request.header(HttpHeaders.ContentLength),
            transferEncoding = call.request.header(HttpHeaders.TransferEncoding),
            maxBytes = maxBytes,
        ) ?: return@intercept
        logger.info("Refused ${call.request.path()}: ${refusal.reason}")
        call.respond(refusal.status, refusal.message)
        finish()
    }
}

/**
 * Why a request was turned away before its body was read, or null to let it
 * through.
 *
 * A value rather than a response, and a pure function rather than an inline block,
 * for one reason: the two headers it reads are **engine-controlled** and a test
 * client cannot set either. Ktor's test engine derives `Content-Length` from the
 * body it is given and refuses `Transfer-Encoding` outright, so the interesting
 * cases — a four-gigabyte claim, a chunked body — are unreachable through the
 * client and are reachable here.
 *
 * @property reason for the log, which wants the number.
 * @property message for the caller, which does not.
 */
internal data class BodyCeilingRefusal(
    val status: HttpStatusCode,
    val message: String,
    val reason: String,
)

/** See [BodyCeilingRefusal] for why this is a function and not four lines inline. */
internal fun bodyCeilingRefusal(
    method: HttpMethod,
    contentLength: String?,
    transferEncoding: String?,
    maxBytes: Long = MAX_REQUEST_BODY_BYTES,
): BodyCeilingRefusal? {
    if (method != HttpMethod.Post && method != HttpMethod.Put && method != HttpMethod.Patch) return null

    val declaredLength = contentLength?.toLongOrNull()
    if (declaredLength == null) {
        // `Transfer-Encoding: chunked` is the one shape that carries a body of
        // unknown size. Anything else with no length is a body of no size.
        if (transferEncoding?.contains("chunked", ignoreCase = true) != true) return null
        return BodyCeilingRefusal(
            status = HttpStatusCode.LengthRequired,
            message = "A request body has to say how big it is (Content-Length).",
            reason = "chunked body, no declared size",
        )
    }

    if (declaredLength <= maxBytes) return null
    return BodyCeilingRefusal(
        status = HttpStatusCode.PayloadTooLarge,
        message = "That request is too large.",
        reason = "declared $declaredLength bytes, ceiling $maxBytes",
    )
}

/**
 * The refusal for a piece of writing over [MAX_LONG_TEXT_LENGTH], or null when it
 * fits.
 *
 * A function rather than four copies of a comparison, so the four fields cannot
 * drift apart and a fifth has one obvious thing to call.
 *
 * @param what the field, named as the writer would name it — "description",
 *   "comment". The message says which one, because a save that refuses without
 *   saying what to shorten leaves somebody staring at a page of their own writing.
 */
fun tooLongMessage(what: String, text: String): String? {
    if (text.length <= MAX_LONG_TEXT_LENGTH) return null
    return "That $what is ${text.length} characters, and the limit is $MAX_LONG_TEXT_LENGTH. " +
        "Attach it as a file, or split it up."
}
