/**
 * The deploy gate, and the one thing worth pinning about it: **it fails closed**.
 *
 * Its sibling [resolveEmailSignInEnabled] deliberately does the opposite — unset is
 * on, and a typo is on — because it governs behaviour that predates the flag and
 * failing closed there would silently lock a deployment out of sign-in. This flag
 * has no such history and grants the power to become any account on the instance,
 * so both defaults invert. A typo costs you a feature you have to switch on again;
 * the opposite mistake costs you the instance.
 *
 * Read through the **system property** rather than the environment variable, and
 * that is a limitation of the test rather than a preference: `System.getenv` cannot
 * be set from inside a JVM. The property is the tier that wins anyway, so the
 * vocabulary and the defaults below are exactly the ones the variable gets — see
 * `ownerImpersonationSetting`.
 *
 * @see resolveOwnerImpersonationEnabled
 */
package se.soderbjorn.lunicle

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImpersonationConfigTest {
    private companion object {
        const val PROPERTY = "lunicle.ownerImpersonation"
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty(PROPERTY)
    }

    /** Unset is off, which is the state of every deployment that has not asked. */
    @Test
    fun `an unset gate is off`() {
        System.clearProperty(PROPERTY)
        assertFalse(resolveOwnerImpersonationEnabled(), "A deployment that said nothing was armed.")
    }

    @Test
    fun `every on-value switches it on`() {
        listOf("on", "true", "1", "yes", "enabled", "ON", " On ").forEach {
            System.setProperty(PROPERTY, it)
            assertTrue(resolveOwnerImpersonationEnabled(), "\"$it\" did not read as on.")
        }
    }

    @Test
    fun `every off-value switches it off`() {
        listOf("off", "false", "0", "no", "disabled", "OFF").forEach {
            System.setProperty(PROPERTY, it)
            assertFalse(resolveOwnerImpersonationEnabled(), "\"$it\" did not read as off.")
        }
    }

    /**
     * A value that is neither is **off**, with a WARN naming it.
     *
     * The whole of the fail-closed decision, in one assertion. Somebody who meant to
     * arm the instance and typed `LUNICLE_ENABLE_OWNER_IMPERSONATION=yse` finds the
     * feature missing and a line in the log saying why, which is a five-second fix.
     * The other direction — treating it as on — would leave an instance armed that
     * nobody believes is.
     */
    @Test
    fun `an unrecognised value is off`() {
        listOf("yse", "maybe", "-", "onn").forEach {
            System.setProperty(PROPERTY, it)
            assertFalse(resolveOwnerImpersonationEnabled(), "\"$it\" armed the instance by accident.")
        }
    }

    /** Blank is absent, as it is everywhere else on this server. */
    @Test
    fun `a blank value is off`() {
        System.setProperty(PROPERTY, "   ")
        assertFalse(resolveOwnerImpersonationEnabled())
    }
}
