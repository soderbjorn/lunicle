/**
 * When the people picker offers to add a typed address as a new person (LNL-204).
 *
 * ── Why this is worth its own file ───────────────────────────────────────────
 *
 * Because nothing can tell when somebody has finished typing, and the cost of guessing
 * wrong is asymmetric. Guess too early and the panel offers to create an account for
 * `nadia@vessel` — a fragment — which is an offer to write a row nobody wants. Guess too
 * late and there is no way to add anybody who has no account, which is the fallback the
 * whole picker rests on.
 *
 * So the rule is *structural completeness*, and these pin it at both edges. They are unit
 * tests over one derived property rather than route tests, deliberately: the property is
 * the whole decision, and it is the kind of thing a later "tidy up the regex" would break
 * silently.
 *
 * The offer staying an offer — nothing written until it is clicked or Enter pressed — is
 * the view's half and is not asserted here.
 *
 * @see PeoplePicker.isWholeAddress
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeoplePickerAddressTest {
    private fun typed(query: String) = PeoplePicker(query = query).isWholeAddress

    @Test
    fun `a whole address is offered`() {
        assertTrue(typed("nadia@vessel.studio"))
        assertTrue(typed("first.last@sub.domain.example.com"))
        assertTrue(typed("someone+tag@example.co.uk"))
    }

    /** Surrounding whitespace is somebody having pasted, not somebody being unfinished. */
    @Test
    fun `an address is trimmed before it is judged`() {
        assertTrue(typed("  nadia@vessel.studio  "))
    }

    /**
     * Everything on the way there is refused — one assertion per keystroke class, because
     * each is a distinct place an eager rule would fire.
     */
    @Test
    fun `a half-typed address is not offered`() {
        assertFalse(typed(""), "an empty field")
        assertFalse(typed("nadia"), "a name, which is a directory search")
        assertFalse(typed("nadia@"), "the at sign alone")
        assertFalse(typed("nadia@vessel"), "a domain with no suffix")
        assertFalse(typed("nadia@vessel."), "the dot, before the suffix exists")
        assertFalse(typed("nadia@vessel.s"), "a one-letter suffix — the case a lazier rule accepts")
    }

    /**
     * And things that are not addresses at all.
     *
     * `@` in the middle of a phrase is the one worth having: somebody searching for a name
     * that happens to contain it must not be offered an account.
     */
    @Test
    fun `something that is not an address is not offered`() {
        assertFalse(typed("@vessel.studio"), "no local part")
        assertFalse(typed("nadia@@vessel.studio"), "two at signs")
        assertFalse(typed("nadia at vessel.studio"), "spaces")
        assertFalse(typed("nadia@vessel .studio"), "a space inside the domain")
    }

    /**
     * The offer is about the text, not about the rung.
     *
     * Worth stating: the picker holds a chosen rung beside the query, and the two are
     * independent — the view refuses to *act* without a rung, and that is a different guard
     * from this one.
     */
    @Test
    fun `the chosen rung does not decide whether an address is whole`() {
        assertTrue(PeoplePicker(query = "nadia@vessel.studio", roleKey = null).isWholeAddress)
        assertTrue(PeoplePicker(query = "nadia@vessel.studio", roleKey = "viewer").isWholeAddress)
    }
}
