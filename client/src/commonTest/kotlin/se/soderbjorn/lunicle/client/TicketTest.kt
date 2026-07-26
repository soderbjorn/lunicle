/**
 * Tests for the ticket parser and the reference scanner.
 *
 * [ticketSpans] is where the false positives are stopped — an acronym that reads
 * like a ticket, a prefix buried in a bigger word — so the weight here is on what
 * must *not* be a reference, the same way [MarkdownTest] leans on the escaping.
 *
 * @see parseTicket
 * @see ticketSpans
 */
package se.soderbjorn.lunicle.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TicketTest {

    @Test
    fun `a plain reference is found`() {
        assertEquals(
            listOf(TicketSpan(start = 4, end = 10, ticket = Ticket("LNL", 12))),
            ticketSpans("see LNL-12 today", listOf("LNL")),
        )
    }

    @Test
    fun `several references are found left to right`() {
        val spans = ticketSpans("LNL-1 blocks LNL-2 and LNL-33", listOf("LNL"))
        assertEquals(listOf(1L, 2L, 33L), spans.map { it.ticket.number })
    }

    @Test
    fun `a prefix matches case-insensitively but the ticket is canonical`() {
        val spans = ticketSpans("fixed in lnl-7", listOf("LNL"))
        assertEquals(1, spans.size)
        assertEquals(Ticket("LNL", 7), spans.single().ticket)
    }

    @Test
    fun `references to any accessible project are found`() {
        // The cross-project case (LNL-139): each prefix in the set is a link.
        val spans = ticketSpans("LNL-1 depends on ABC-9", listOf("LNL", "ABC"))
        assertEquals(
            listOf(Ticket("LNL", 1), Ticket("ABC", 9)),
            spans.map { it.ticket },
        )
    }

    @Test
    fun `a reference to a prefix outside the accessible set is not found`() {
        // A project the reader cannot see is not linked — it would promise a
        // destination that is not there.
        assertTrue(ticketSpans("see ABC-12", listOf("LNL")).isEmpty(), "A foreign prefix is not a reference")
    }

    @Test
    fun `a prefix that only starts a longer word is not a reference`() {
        assertTrue(ticketSpans("SUPERLNL-12", listOf("LNL")).isEmpty(), "A prefix mid-word is not one")
        assertTrue(ticketSpans("xLNL-12", listOf("LNL")).isEmpty(), "A letter before the prefix blocks it")
        assertTrue(ticketSpans("9LNL-12", listOf("LNL")).isEmpty(), "A digit before the prefix blocks it")
    }

    @Test
    fun `a reference hidden inside a bigger ticket is not found`() {
        // FOO-LNL-1 is one ticket of some other project, not an LNL reference.
        assertTrue(ticketSpans("FOO-LNL-1", listOf("LNL")).isEmpty(), "A hyphen before the prefix blocks it")
    }

    @Test
    fun `an at-mention shaped like a ticket is left for the mention scanner`() {
        assertTrue(ticketSpans("@LNL-1", listOf("LNL")).isEmpty(), "An @ before the prefix blocks it")
    }

    @Test
    fun `a number that does not end a word is not a reference`() {
        assertTrue(ticketSpans("LNL-12abc", listOf("LNL")).isEmpty(), "A letter after the number blocks it")
        assertTrue(ticketSpans("LNL-123x", listOf("LNL")).isEmpty(), "A trailing letter blocks it")
    }

    @Test
    fun `a reference must have a number`() {
        assertTrue(ticketSpans("LNL-", listOf("LNL")).isEmpty(), "No digits, no reference")
        assertTrue(ticketSpans("LNL-x", listOf("LNL")).isEmpty(), "A non-digit, no reference")
        assertTrue(ticketSpans("LNL-0", listOf("LNL")).isEmpty(), "Zero names no issue")
    }

    @Test
    fun `punctuation around a reference does not swallow it`() {
        assertEquals(listOf(5L), ticketSpans("(see LNL-5).", listOf("LNL")).map { it.ticket.number })
        assertEquals(listOf(5L), ticketSpans("LNL-5, then", listOf("LNL")).map { it.ticket.number })
    }

    @Test
    fun `an empty prefix set matches nothing`() {
        assertTrue(ticketSpans("LNL-1", emptyList()).isEmpty(), "No projects, nothing to link")
        assertTrue(ticketSpans("LNL-1", listOf("", "  ")).isEmpty(), "Blank prefixes match nothing")
    }

    @Test
    fun `a hyphenated prefix is matched whole`() {
        // Ticket.kt allows a prefix to contain a hyphen (MY-APP); the scanner must
        // match the whole of it rather than stopping at the first hyphen.
        val spans = ticketSpans("done in MY-APP-42", listOf("MY-APP"))
        assertEquals(1, spans.size)
        assertEquals(Ticket("MY-APP", 42), spans.single().ticket)
    }

    @Test
    fun `when two prefixes could match the longer one wins`() {
        // MY and MY-APP are both projects; MY-APP-1 is a MY-APP reference, not a MY
        // one that stops at the first hyphen.
        val spans = ticketSpans("MY-APP-1", listOf("MY", "MY-APP"))
        assertEquals(Ticket("MY-APP", 1), spans.single().ticket)
    }
}
