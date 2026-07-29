/**
 * What a deployment says about itself, and what that lets it admit (LNL-192).
 *
 * Three questions this file separates, because the whole ticket is about their having
 * been one field:
 *
 *  - **Identity.** `domain` decides staff from member and nothing else. Unset — the
 *    default — means there is no staff tier at all.
 *  - **Ergonomics.** `onlyHostedGoogleAccounts` pins the Google chooser and grants
 *    nobody anything. It is inert without a domain to pin to.
 *  - **The second door.** `allowEmailCodeSignIn` can only ever narrow: a deployment
 *    with no mail transport has no code sign-in whatever the manifest claims.
 *
 * The admission options come off those three, computed server-side and asserted here
 * as data — greyed with a reason rather than removed, and a stored policy still
 * reported as the selection after the configuration strands it. Ticket 5's screens
 * render what these assertions describe; nothing there re-derives it.
 *
 * @see InstanceIdentity
 * @see AdmissionRoutesTest for the same rules through the real routes.
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AdmissionPolicy
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceIdentityTest {

    // ── Identity: the domain, and only the domain ────────────────────────────

    /**
     * An unset domain means no staff tier, and that is the default.
     *
     * The load-bearing half of the split: nothing typed into the app can invent a
     * staff tier, so an unbranded install has two audiences and a `staff` audience row
     * that matches nobody. Asserted against the one function `domain` feeds.
     */
    @Test
    fun `with no domain configured every account is a member`() {
        val identity = InstanceIdentity()
        assertFalse(identity.hasStaffTier, "An unconfigured deployment claimed a staff tier.")
        assertEquals(UserKind.MEMBER, UserKind.forEmail("anyone@example.com", identity.domain))
        assertEquals(UserKind.MEMBER, UserKind.forEmail("boss@acme.com", identity.domain))
    }

    /** With a domain, and only then, an address on it is staff. */
    @Test
    fun `with a domain configured an address on it is staff and nothing else is`() {
        val identity = InstanceIdentity(domain = "acme.com")
        assertTrue(identity.hasStaffTier)
        assertEquals(UserKind.STAFF, UserKind.forEmail("boss@acme.com", identity.domain))
        assertEquals(UserKind.STAFF, UserKind.forEmail("BOSS@ACME.COM", identity.domain))
        assertEquals(UserKind.MEMBER, UserKind.forEmail("outsider@example.com", identity.domain))
        assertEquals(UserKind.MEMBER, UserKind.forEmail(null, identity.domain))
    }

    /**
     * A domain does not pin the chooser, and a pin without a domain pins nothing.
     *
     * The two fields' independence, in the one place they are allowed to meet.
     */
    @Test
    fun `the chooser pin needs both a domain and the decision to pin`() {
        assertNull(
            InstanceIdentity(domain = "acme.com").googleHostedDomainPin,
            "Naming a domain silently locked the Google chooser.",
        )
        assertNull(
            InstanceIdentity(onlyHostedGoogleAccounts = true).googleHostedDomainPin,
            "The chooser was pinned to nothing in particular.",
        )
        assertEquals(
            "acme.com",
            InstanceIdentity(domain = "acme.com", onlyHostedGoogleAccounts = true).googleHostedDomainPin,
        )
    }

    // ── The brand manifest, and the field these three replaced ───────────────

    /**
     * A manifest that names only `googleHostedDomain` behaves exactly as it did.
     *
     * The whole compatibility story: the legacy field seeds the domain *and* turns the
     * pin on, because that is precisely what it used to mean. Every deployment that
     * exists today is this shape, so this is the assertion that says nothing changed
     * for them and nothing had to be migrated.
     */
    @Test
    fun `the legacy googleHostedDomain still means domain plus a pinned chooser`() {
        val info = loadBrandInfo(manifest("""{"googleHostedDomain":"acme.com"}"""))
        assertEquals("acme.com", info.domain)
        assertTrue(info.onlyHostedGoogleAccounts)
        assertTrue(info.allowEmailCodeSignIn, "The legacy field said something about code sign-in.")
        assertEquals("acme.com", info.toInstanceIdentity(true).googleHostedDomainPin)
    }

    /** Named explicitly, the two come apart — a domain with an open chooser. */
    @Test
    fun `an explicit domain without the pin leaves the chooser open`() {
        val info = loadBrandInfo(manifest("""{"domain":"acme.com","onlyHostedGoogleAccounts":false}"""))
        assertEquals("acme.com", info.domain)
        assertFalse(info.onlyHostedGoogleAccounts)
        assertNull(
            info.toInstanceIdentity(true).googleHostedDomainPin,
            "An explicit onlyHostedGoogleAccounts:false was overridden by something.",
        )
    }

    /** And the reverse: a pinned chooser is what the explicit field says, not the legacy one. */
    @Test
    fun `an explicit field wins over the legacy spelling`() {
        val info = loadBrandInfo(
            manifest("""{"googleHostedDomain":"legacy.com","domain":"acme.com","allowEmailCodeSignIn":false}"""),
        )
        assertEquals("acme.com", info.domain, "The legacy field outranked the one that replaced it.")
        assertFalse(info.allowEmailCodeSignIn)
    }

    /** A manifest that says nothing takes the permissive defaults. */
    @Test
    fun `an unbranded manifest has no domain, no pin, and code sign-in available`() {
        val info = loadBrandInfo(manifest("""{"title":"Issues"}"""))
        assertNull(info.domain)
        assertFalse(info.onlyHostedGoogleAccounts)
        assertTrue(info.allowEmailCodeSignIn)
    }

    // ── The second door narrows, and never widens ────────────────────────────

    /**
     * `allowEmailCodeSignIn: true` with no transport configured is still "no".
     *
     * The flag must never claim a door that is not there. This matters concretely for
     * the branded deployment, which has SMTP deliberately off — its effective answer
     * stays "no code sign-in" whatever the manifest says, and the admission option
     * that depends on that door is greyed accordingly.
     *
     * The test JVM has no mail variables, so [resolveOAuthConfig] is being asked
     * exactly the production question.
     */
    @Test
    fun `the manifest cannot claim code sign-in that the transport does not support`() {
        assertFalse(
            isEmailConfigured(),
            "Precondition: this test JVM must have no mail transport configured.",
        )
        assertFalse(
            resolveOAuthConfig(allowEmailCodeSignIn = true).isEmailAvailable,
            "A manifest flag turned code sign-in on with nothing to send it with.",
        )
    }

    // ── Admission: what is offered, what is greyed, and why ──────────────────

    /**
     * With nothing configured, all three choices are offered — except the two that
     * name a domain this deployment does not have.
     *
     * An option that would admit nobody is not a stricter setting; it is a locked door
     * with no key, so it is greyed with the reason rather than silently selectable.
     */
    @Test
    fun `an unbranded deployment offers anyone, and greys what needs a domain`() {
        val options = InstanceIdentity().admissionState(AdmissionPolicy.ANYONE).options
        assertTrue(options.first { it.policy == AdmissionPolicy.ANYONE }.isSelectable)
        for (policy in listOf(AdmissionPolicy.STAFF_DOMAIN_ONLY, AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED)) {
            val option = options.first { it.policy == policy }
            assertFalse(option.isSelectable, "$policy was offered on a deployment with no domain.")
            assertEquals("this deployment has no domain of its own configured", option.unavailableReason)
        }
    }

    /**
     * A pinned Google chooser makes "anyone" unreachable, and says so by name.
     *
     * The reason names the domain, because "you cannot pick this" without saying what
     * is stopping you teaches an administrator nothing they can act on.
     */
    @Test
    fun `a pinned chooser greys anyone, with the domain in the reason`() {
        val identity = InstanceIdentity(domain = "acme.com", onlyHostedGoogleAccounts = true)
        val option = identity.admissionState(AdmissionPolicy.STAFF_DOMAIN_ONLY).options
            .first { it.policy == AdmissionPolicy.ANYONE }
        assertFalse(option.isSelectable)
        assertEquals("Google sign-in is locked to acme.com", option.unavailableReason)
    }

    /**
     * With no code sign-in, an invited outside address could never arrive, so
     * "plus added" is greyed.
     */
    @Test
    fun `no code sign-in greys the plus-added policy`() {
        val identity = InstanceIdentity(domain = "acme.com", isCodeSignInAvailable = false)
        val options = identity.admissionState(AdmissionPolicy.STAFF_DOMAIN_ONLY).options
        val plusAdded = options.first { it.policy == AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED }
        assertFalse(plusAdded.isSelectable)
        assertEquals("code sign-in is off", plusAdded.unavailableReason)
        assertTrue(
            options.first { it.policy == AdmissionPolicy.STAFF_DOMAIN_ONLY }.isSelectable,
            "Losing code sign-in also took away the policy that needs no second door.",
        )
    }

    /**
     * The branded shape: a domain, a pinned chooser, no mail. Exactly one choice is
     * honourable, and the other two say why not.
     */
    @Test
    fun `the branded shape leaves exactly one honourable choice`() {
        val identity = InstanceIdentity(
            domain = "acme.com",
            onlyHostedGoogleAccounts = true,
            isCodeSignInAvailable = false,
        )
        val selectable = identity.admissionState(AdmissionPolicy.STAFF_DOMAIN_ONLY).options
            .filter { it.isSelectable }
            .map { it.policy }
        assertEquals(listOf(AdmissionPolicy.STAFF_DOMAIN_ONLY), selectable)
    }

    /**
     * A stored policy the configuration has since stranded is still reported as the
     * selection — greyed, with the reason, rather than silently swapped.
     *
     * The effective behaviour is the deployment's restriction either way, so nothing
     * is bought by lying about what was chosen; and an administrator cannot fix a
     * setting they are not shown.
     */
    @Test
    fun `a stored policy that became unreachable is still the reported selection`() {
        val state = InstanceIdentity(domain = "acme.com", onlyHostedGoogleAccounts = true)
            .admissionState(AdmissionPolicy.ANYONE)
        assertEquals(AdmissionPolicy.ANYONE, state.selected, "The stranded selection was silently replaced.")
        val option = state.options.first { it.policy == state.selected }
        assertFalse(option.isSelectable)
        assertNotNull(option.unavailableReason)
    }

    /** Every option is always present, in ladder order. Greyed, never removed. */
    @Test
    fun `no configuration removes an option from the list`() {
        val configurations = listOf(
            InstanceIdentity(),
            InstanceIdentity(domain = "acme.com"),
            InstanceIdentity(domain = "acme.com", onlyHostedGoogleAccounts = true),
            InstanceIdentity(domain = "acme.com", isCodeSignInAvailable = false),
            InstanceIdentity(onlyHostedGoogleAccounts = true, isCodeSignInAvailable = false),
        )
        for (identity in configurations) {
            assertEquals(
                AdmissionPolicy.entries,
                identity.admissionState(AdmissionPolicy.ANYONE).options.map { it.policy },
                "An option went missing under $identity.",
            )
        }
    }

    // ── The gate itself ─────────────────────────────────────────────────────

    /** "Anyone" admits anybody, including an address the deployment cannot place. */
    @Test
    fun `the anyone policy admits every address`() {
        val identity = InstanceIdentity(domain = "acme.com")
        assertTrue(AdmissionPolicy.ANYONE.admitsNewAccount("outsider@example.com", identity))
        assertTrue(AdmissionPolicy.ANYONE.admitsNewAccount(null, identity))
    }

    /** "Staff domain only" admits the domain and nothing else — not even an added address. */
    @Test
    fun `the staff-domain policy admits only the domain`() {
        val identity = InstanceIdentity(domain = "acme.com")
        assertTrue(AdmissionPolicy.STAFF_DOMAIN_ONLY.admitsNewAccount("boss@acme.com", identity))
        assertFalse(AdmissionPolicy.STAFF_DOMAIN_ONLY.admitsNewAccount("outsider@example.com", identity))
        assertFalse(
            AdmissionPolicy.STAFF_DOMAIN_ONLY.admitsNewAccount(
                "outsider@example.com",
                identity,
                isAlreadyAdded = true,
            ),
            "The strictest policy admitted an outside address somebody had added.",
        )
    }

    /** "Plus added" is the same, plus the addresses somebody put here on purpose. */
    @Test
    fun `the plus-added policy admits the domain and anything already added`() {
        val identity = InstanceIdentity(domain = "acme.com")
        assertTrue(AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED.admitsNewAccount("boss@acme.com", identity))
        assertFalse(AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED.admitsNewAccount("outsider@example.com", identity))
        assertTrue(
            AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED.admitsNewAccount(
                "outsider@example.com",
                identity,
                isAlreadyAdded = true,
            ),
        )
    }

    /**
     * A staff policy on a deployment with no domain admits nobody, which is exactly
     * why both are greyed rather than merely discouraged.
     */
    @Test
    fun `a staff policy with no domain admits nobody`() = runBlocking {
        val identity = InstanceIdentity()
        assertFalse(AdmissionPolicy.STAFF_DOMAIN_ONLY.admitsNewAccount("anyone@example.com", identity))
        assertFalse(AdmissionPolicy.STAFF_DOMAIN_ONLY.admitsNewAccount("boss@acme.com", identity))
    }

    // ── findExisting: the question the gate actually asks ────────────────────

    /**
     * The gate consults [se.soderbjorn.lunicle.store.UserStore.findExisting], and it
     * has to reach a returning account by **either** key — otherwise a returning
     * Google account whose address Google never confirmed reads as a brand new sign-up
     * and is refused at a door it already came through.
     */
    @Test
    fun `findExisting reaches an account by address and by the provider pair`() = runBlocking {
        val file = Files.createTempFile("lunicle-identity", ".db").toFile().also { it.delete() }
        val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
        try {
            val users = UserStore(opened.database)
            val keyed = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "sub-1", "Alice", "alice@acme.com"))
            val unkeyed = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "sub-2", "Bob", null))

            assertEquals(
                keyed.id,
                users.findExisting(
                    ProviderIdentity(AuthProvider.EMAIL, "alice@acme.com", "alice", "alice@acme.com"),
                )?.id,
                "A different provider proving the same address did not reach the existing row.",
            )
            assertEquals(
                unkeyed.id,
                users.findExisting(ProviderIdentity(AuthProvider.GOOGLE, "sub-2", "Bob", null))?.id,
                "An account with no address was unreachable by its provider pair.",
            )
            assertNull(
                users.findExisting(ProviderIdentity(AuthProvider.GOOGLE, "sub-3", "Carol", "carol@acme.com")),
                "A genuinely new identity was reported as an existing account.",
            )
        } finally {
            opened.close()
            file.delete()
            File("${file.absolutePath}-wal").delete()
            File("${file.absolutePath}-shm").delete()
        }
    }

    /** A brand dir holding just this manifest text. */
    private fun manifest(json: String): File {
        val dir = Files.createTempDirectory("brand-identity").toFile()
        File(dir, "brand.json").writeText(json)
        return dir
    }
}
