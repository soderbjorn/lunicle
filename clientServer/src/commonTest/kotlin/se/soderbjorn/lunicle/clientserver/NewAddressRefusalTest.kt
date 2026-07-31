/**
 * Which addresses the add-people picker refuses, pinned.
 *
 * The bug this exists to keep fixed: [ProjectAccessState.newAddressRefusal] is the
 * deployment's standing sentence about an address **off** its domain, so on a
 * domain-restricted instance it is non-null at all times. The picker read it as "this
 * address is refused" and therefore refused every new address — including the on-domain
 * ones, who are the only people such an instance can be asked to add. It printed "admits
 * framna.com addresses only" underneath a framna.com address, and the server would have
 * accepted that address without a word of complaint.
 *
 * So the cases worth having are the ones where the refusal stands but must not apply.
 *
 * @see ProjectAccessState.newAddressRefusalFor
 * @see se.soderbjorn.lunicle.AdmissionPolicy.admitsNewAccount for the rule this explains
 */
package se.soderbjorn.lunicle.clientserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewAddressRefusalTest {

    private val refusal =
        "This instance admits framna.com addresses only, so there is no account for it to hold."

    /** A domain-restricted deployment: refusal standing, domain declared. */
    private val restricted = ProjectAccessState(
        staffDomain = "framna.com",
        newAddressRefusal = refusal,
    )

    @Test
    fun `an address on the domain is not refused`() {
        // The regression. This instance exists to hold exactly these people.
        assertNull(restricted.newAddressRefusalFor("test.testsson@framna.com"))
    }

    @Test
    fun `an address off the domain is refused, in the deployment's words`() {
        assertEquals(refusal, restricted.newAddressRefusalFor("nadia@vessel.studio"))
    }

    @Test
    fun `the domain is matched case-insensitively`() {
        // `UserKind.forEmail`'s rule, which is what the server's guard will apply to the
        // same address a moment later. Disagreeing here refuses somebody the POST admits.
        assertNull(restricted.newAddressRefusalFor("Test.Testsson@FRAMNA.COM"))
    }

    @Test
    fun `surrounding whitespace does not make an address foreign`() {
        // The panel trims before asking; this makes the answer not depend on that.
        assertNull(restricted.newAddressRefusalFor("  test.testsson@framna.com  "))
    }

    @Test
    fun `a subdomain is not the domain`() {
        // Not staff at framna.com on the server either — `forEmail` compares the whole
        // domain. An address admitted here that the POST refuses is the same defect
        // pointing the other way.
        assertEquals(refusal, restricted.newAddressRefusalFor("nadia@mail.framna.com"))
    }

    @Test
    fun `only the last at-sign starts the domain`() {
        assertNull(restricted.newAddressRefusalFor("odd\"@\"local@framna.com"))
    }

    @Test
    fun `a fragment with no at-sign keeps the refusal`() {
        // Half-typed, and not on the domain until it is. The picker only asks about whole
        // addresses (see PeoplePicker.isWholeAddress), so this is belt and braces.
        assertEquals(refusal, restricted.newAddressRefusalFor("test.testsson"))
    }

    @Test
    fun `a deployment that refuses nothing refuses nothing`() {
        // `anyone` admission — null refusal, whatever the domain says. A domain by itself
        // restricts nobody.
        val open = ProjectAccessState(staffDomain = "framna.com")
        assertNull(open.newAddressRefusalFor("nadia@vessel.studio"))
        assertNull(open.newAddressRefusalFor("test.testsson@framna.com"))
    }

    @Test
    fun `a refusal with no domain to compare against stands for every address`() {
        // The `identity.domain == null` branch of the server's wording — "does not accept
        // new accounts for that address". With no domain there is no on-domain exception to
        // make, and the safe reading is the one that matches what the POST will do.
        val domainless = ProjectAccessState(
            newAddressRefusal = "This instance does not accept new accounts for that address.",
        )
        assertEquals(
            "This instance does not accept new accounts for that address.",
            domainless.newAddressRefusalFor("nadia@vessel.studio"),
        )
    }
}
