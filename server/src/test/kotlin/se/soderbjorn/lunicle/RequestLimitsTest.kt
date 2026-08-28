/**
 * The request-body ceiling and the long-text cap (LUS-30).
 *
 * Before these, three Ktor plugins were installed and none of them capped a body.
 * The attachment upload path was the only route that pre-checked `Content-Length`;
 * every other body was buffered into the heap in full before any validation ran.
 * So an anonymous client could post a multi-gigabyte body to `/oauth/register` and
 * take the process out of memory — no credentials, repeatable at will.
 *
 * ── Why most of this is a unit test ─────────────────────────────────────────
 *
 * Both headers the ceiling reads are **engine-controlled**. Ktor's test client
 * derives `Content-Length` from the body it is handed and refuses an explicit
 * `Transfer-Encoding` outright, so the two cases worth asserting — a
 * four-gigabyte claim, and a chunked body declaring nothing — cannot be sent
 * through it at all. They are asserted against [bodyCeilingRefusal], which exists
 * for exactly that reason, and the wiring is asserted once through a real route
 * with a small ceiling.
 *
 * @see installRequestBodyCeiling
 */
package se.soderbjorn.lunicle

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestLimitsTest {

    // ── The decision ─────────────────────────────────────────────────────────

    @Test
    fun `a declared length over the ceiling is refused`() {
        val refusal = bodyCeilingRefusal(
            method = HttpMethod.Post,
            contentLength = (MAX_REQUEST_BODY_BYTES + 1).toString(),
            transferEncoding = null,
        )
        assertEquals(HttpStatusCode.PayloadTooLarge, refusal?.status)
    }

    @Test
    fun `a declared length at the ceiling is allowed`() {
        assertNull(
            bodyCeilingRefusal(
                method = HttpMethod.Post,
                contentLength = MAX_REQUEST_BODY_BYTES.toString(),
                transferEncoding = null,
            ),
        )
    }

    /**
     * A chunked body declares no size, so there is nothing to check and no bound to
     * enforce short of buffering it to find out.
     *
     * Refused with 411 — the same call the upload route already makes and
     * documents. This is what closes the bypass: a ceiling that only reads
     * `Content-Length` is a ceiling an attacker opts out of by omitting the header.
     */
    @Test
    fun `a chunked body is refused for declaring no size`() {
        val refusal = bodyCeilingRefusal(
            method = HttpMethod.Post,
            contentLength = null,
            transferEncoding = "chunked",
        )
        assertEquals(HttpStatusCode.LengthRequired, refusal?.status)
    }

    /** No length and not chunked is a request with no body. Every bodyless POST is one. */
    @Test
    fun `a bodyless request is allowed`() {
        assertNull(bodyCeilingRefusal(HttpMethod.Post, contentLength = null, transferEncoding = null))
    }

    /** A GET has no body to weigh, whatever headers arrive on it. */
    @Test
    fun `reads are not weighed`() {
        assertNull(
            bodyCeilingRefusal(
                method = HttpMethod.Get,
                contentLength = (MAX_REQUEST_BODY_BYTES * 100).toString(),
                transferEncoding = null,
            ),
        )
    }

    // ── The wiring ───────────────────────────────────────────────────────────

    /** Installed in front of everything, so the handler never runs on an over-size body. */
    @Test
    fun `the interceptor refuses before the handler runs`() = testApplication {
        var reached = false
        application {
            installRequestBodyCeiling(maxBytes = 16)
            routing { post("/sink") { reached = true; call.respondText("read") } }
        }
        val response = client.post("/sink") { setBody("x".repeat(64)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(!reached, "The handler ran anyway, so the body was read after all.")

        assertEquals(HttpStatusCode.OK, client.post("/sink") { setBody("small") }.status)
        assertTrue(reached, "An ordinary body stopped reaching its handler.")
    }

    // ── The stored-text cap ──────────────────────────────────────────────────

    /**
     * The ceiling has to sit above the attachment limit, or a 25-megabyte upload
     * meets a bare 413 from the interceptor instead of the sentence naming both
     * numbers that the upload route exists to give.
     */
    @Test
    fun `the ceiling leaves room for a legitimate attachment`() {
        assertTrue(
            MAX_REQUEST_BODY_BYTES > se.soderbjorn.lunicle.clientserver.MAX_ATTACHMENT_BYTES,
            "The body ceiling is at or below the attachment limit, so a legal upload is refused " +
                "by the wrong check with the wrong message.",
        )
    }

    @Test
    fun `long text is refused with the field and the limit in the message`() {
        assertNull(tooLongMessage("description", "a".repeat(MAX_LONG_TEXT_LENGTH)))
        val message = assertNotNull(tooLongMessage("description", "a".repeat(MAX_LONG_TEXT_LENGTH + 1)))
        assertTrue("description" in message, "The refusal does not name the field.")
        assertTrue(MAX_LONG_TEXT_LENGTH.toString() in message, "The refusal does not name the limit.")
    }
}
