/**
 * The grant store: a capability that expires, is spendable once, and is bounded to
 * one per owner.
 *
 * Everything here is about a value worth exactly as much as the owner's own session
 * cookie — it mints the owner's session on demand — so the properties are the ones
 * you would want of a credential rather than of a cache. It cannot be guessed, it
 * does not outlive its window, revoking it works, and arming again does not leave
 * the previous key lying about.
 *
 * @see ProbeGrants
 */
package se.soderbjorn.lunicle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProbeGrantsTest {
    @Test
    fun `an armed grant resolves to its owner`() {
        val grants = ProbeGrants()
        val probeId = grants.arm(ownerUserId = 7)

        val grant = assertNotNull(grants.resolve(probeId), "The grant that was just armed does not resolve.")
        assertEquals(7L, grant.ownerUserId)
    }

    /**
     * Two arms produce two different ids, and neither is guessable from the other.
     *
     * Only the first half is testable; the second is [java.security.SecureRandom]'s
     * job and the reason it is used rather than `Random`. What this rules out is the
     * failure that *would* be silent — a store handing back one id per owner, or a
     * counter — which would look identical in every other test here.
     */
    @Test
    fun `each arm mints a distinct id`() {
        val grants = ProbeGrants()
        assertNotEquals(grants.arm(7), grants.arm(8))
        // Long enough not to be brute-forced: 32 bytes, base64url, so 43 characters.
        assertTrue(grants.arm(9).length >= 40, "The probe id is short enough to be worth guessing at.")
    }

    /** An unknown id resolves to nothing, and so does a null one. */
    @Test
    fun `an unknown or absent id resolves to null`() {
        val grants = ProbeGrants()
        grants.arm(7)
        assertNull(grants.resolve("not-a-real-probe-id"))
        assertNull(grants.resolve(null), "A caller with no probe cookie was handed somebody's grant.")
    }

    /**
     * Past its lifetime it stops resolving, and the entry is **removed**.
     *
     * Removed rather than merely ignored because this map has no other sweep: there
     * is no timer and no startup pass, since a restart empties it entirely, so
     * resolution is the only thing that can collect litter.
     */
    @Test
    fun `an expired grant stops resolving and is collected`() {
        var clock = 1_000L
        val grants = ProbeGrants(now = { clock })
        val probeId = grants.arm(7)

        clock += PROBE_GRANT_LIFETIME_MILLIS - 1
        assertNotNull(grants.resolve(probeId), "A grant expired inside its own lifetime.")

        clock += 2
        assertNull(grants.resolve(probeId), "A grant outlived its lifetime.")
        assertEquals(0, grants.size(), "The expired grant was left in the map.")
    }

    @Test
    fun `revoke is idempotent and null-tolerant`() {
        val grants = ProbeGrants()
        val probeId = grants.arm(7)

        grants.revoke(probeId)
        assertNull(grants.resolve(probeId), "A revoked grant still resolves.")
        // Neither of these is an error, mirroring SessionStore.destroy: every call
        // site would otherwise repeat the same null guard to reach the same nothing.
        grants.revoke(probeId)
        grants.revoke(null)
    }

    /**
     * One live grant per owner: arming again invalidates the first id.
     *
     * So an arm somebody walked away from cannot sit waiting behind the one they are
     * using. Without this, every re-arm would leave another standing key to the
     * instance in circulation for the rest of the TTL — and nothing anywhere would
     * be able to name them to revoke them.
     */
    @Test
    fun `arming twice for one owner invalidates the first id`() {
        val grants = ProbeGrants()
        val first = grants.arm(7)
        val second = grants.arm(7)

        assertNull(grants.resolve(first), "The abandoned grant is still live.")
        assertNotNull(grants.resolve(second))
        assertEquals(1, grants.size(), "One owner is holding two grants.")
    }

    /** ...but two owners hold their own, which is not the same rule. */
    @Test
    fun `two owners hold separate grants`() {
        val grants = ProbeGrants()
        val ada = grants.arm(7)
        val grace = grants.arm(8)

        assertEquals(7L, grants.resolve(ada)?.ownerUserId)
        assertEquals(8L, grants.resolve(grace)?.ownerUserId, "One owner's arm revoked another's.")
    }
}
