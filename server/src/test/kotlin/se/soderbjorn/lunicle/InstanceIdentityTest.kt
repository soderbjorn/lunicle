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
     * A pinned chooser alone greys nothing while a mailed code is still a door.
     *
     * The first of LNL-192's two wrong rules, corrected (LNL-195): it greyed `anyone` on
     * the pin alone, as though Google were the only way in. It is not — a stranger with
     * no Google account at all gets a mailed code and arrives — so `anyone` is exactly
     * what this deployment can honour. See [InstanceIdentity.outsiderCanArrive].
     */
    @Test
    fun `a pinned chooser does not grey anyone while codes are available`() {
        val identity = InstanceIdentity(
            domain = "acme.com",
            onlyHostedGoogleAccounts = true,
            isCodeSignInAvailable = true,
        )
        assertTrue(identity.outsiderCanArrive)
        val options = identity.admissionState(AdmissionPolicy.STAFF_DOMAIN_ONLY).options
        assertTrue(
            options.first { it.policy == AdmissionPolicy.ANYONE }.isSelectable,
            "A pinned chooser greyed `anyone` on a deployment that mails codes to anybody.",
        )
        assertTrue(options.first { it.policy == AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED }.isSelectable)
    }

    /**
     * Codes off alone greys nothing while the chooser is open.
     *
     * The second wrong rule, corrected: it greyed `staff domain plus added` on the
     * absence of a mailed code, as though a code were the only way an added address
     * could arrive. An added outside address signs in under its own Google account,
     * which an open chooser accepts.
     */
    @Test
    fun `no code sign-in does not grey plus-added while the chooser is open`() {
        val identity = InstanceIdentity(domain = "acme.com", isCodeSignInAvailable = false)
        assertTrue(identity.outsiderCanArrive)
        val options = identity.admissionState(AdmissionPolicy.STAFF_DOMAIN_ONLY).options
        assertTrue(
            options.first { it.policy == AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED }.isSelectable,
            "Losing the mailed code greyed a policy an open Google chooser can honour.",
        )
        assertTrue(options.first { it.policy == AdmissionPolicy.ANYONE }.isSelectable)
    }

    /**
     * Both doors shut to an outsider greys **both** outward-facing choices, together.
     *
     * They exist for the same purpose — admitting somebody outside the domain — so
     * there is no configuration in which one is honourable and the other is not. This
     * is also the branded shape: a domain, a pinned chooser, no mail.
     */
    @Test
    fun `a pinned chooser with no codes greys both outward choices, with both facts named`() {
        val identity = InstanceIdentity(
            domain = "acme.com",
            onlyHostedGoogleAccounts = true,
            isCodeSignInAvailable = false,
        )
        assertFalse(identity.outsiderCanArrive)
        val options = identity.admissionState(AdmissionPolicy.STAFF_DOMAIN_ONLY).options
        val expected = "Google sign-in is locked to acme.com, and this deployment cannot mail a sign-in code"
        for (policy in listOf(AdmissionPolicy.ANYONE, AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED)) {
            val option = options.first { it.policy == policy }
            assertFalse(option.isSelectable, "$policy was offered where no outsider can arrive.")
            assertEquals(expected, option.unavailableReason)
        }
        assertEquals(
            listOf(AdmissionPolicy.STAFF_DOMAIN_ONLY),
            options.filter { it.isSelectable }.map { it.policy },
            "The branded shape offered something other than the one policy it can honour.",
        )
    }

    /**
     * Google unconfigured but a mailed code available: the pin is irrelevant, and
     * everything a domain allows is honourable.
     *
     * Neither of LNL-192's rules could see this case at all — both were written about a
     * chooser that is not there.
     */
    @Test
    fun `with no Google at all a mailed code still admits everybody`() {
        val identity = InstanceIdentity(
            domain = "acme.com",
            onlyHostedGoogleAccounts = true,
            isCodeSignInAvailable = true,
            isGoogleAvailable = false,
        )
        assertTrue(identity.hasAnyWayIn)
        assertTrue(identity.outsiderCanArrive)
        assertEquals(listOf("mailed code"), identity.waysIn)
        assertTrue(identity.admissionState(AdmissionPolicy.ANYONE).options.all { it.isSelectable })
    }

    /**
     * Google unconfigured **and** no mailed code: nothing is honourable, and the reason
     * is the missing door rather than a domain restriction.
     *
     * A deployment in this state is usually a container that did not receive its
     * variables, and telling its administrator that "this deployment has no domain of
     * its own configured" would send them to edit the wrong file.
     */
    @Test
    fun `with no way in at all every choice is unreachable, and says so`() {
        for (identity in listOf(
            InstanceIdentity(isCodeSignInAvailable = false, isGoogleAvailable = false),
            InstanceIdentity(domain = "acme.com", isCodeSignInAvailable = false, isGoogleAvailable = false),
            InstanceIdentity(
                domain = "acme.com",
                onlyHostedGoogleAccounts = true,
                isCodeSignInAvailable = false,
                isGoogleAvailable = false,
            ),
        )) {
            assertFalse(identity.hasAnyWayIn, "A deployment with no provider claimed a door.")
            assertTrue(identity.waysIn.isEmpty())
            for (option in identity.admissionState(AdmissionPolicy.ANYONE).options) {
                assertFalse(option.isSelectable, "${option.policy} was offered on a deployment nobody can reach.")
                assertEquals(
                    "this deployment has no way to sign in",
                    option.unavailableReason,
                    "${option.policy} borrowed a restriction's wording for a missing door.",
                )
            }
        }
    }

    /**
     * The member tier stands empty of arrivals exactly where no outsider can arrive.
     *
     * The branded shape — a domain, a pinned chooser, no mail — leaves every account that
     * signs in on the domain, so the tier's two switches on Who gets in, and the members
     * row under what a new project starts with, describe a set no arriving account is in
     * (LNL-210). The sentence is what says so; nothing is greyed by it, because nothing
     * about it is refused.
     */
    @Test
    fun `the member tier reports being unreachable only where no outsider can arrive`() {
        val branded = InstanceIdentity(
            domain = "acme.com",
            onlyHostedGoogleAccounts = true,
            isCodeSignInAvailable = false,
        )
        assertEquals(
            "Nobody outside acme.com can sign in here — Google sign-in is locked to acme.com, " +
                "and this deployment cannot mail a sign-in code — so every account that " +
                "arrives is staff.",
            branded.memberTierUnreachableReason,
        )
        // A single open door is enough to put somebody in the tier, and each of the two
        // is a door on its own — the same one predicate the admission greying rides on.
        assertNull(
            InstanceIdentity(domain = "acme.com", onlyHostedGoogleAccounts = true)
                .memberTierUnreachableReason,
            "A deployment that mails codes claimed no member could reach it.",
        )
        assertNull(
            InstanceIdentity(domain = "acme.com", isCodeSignInAvailable = false)
                .memberTierUnreachableReason,
            "An open Google chooser claimed no member could reach it.",
        )
    }

    /**
     * ...and says nothing at all in the two cases where the sentence would be a lie.
     *
     * With no domain the member tier is simply everybody, so there is nothing to report.
     * With no door at all "every account that arrives is staff" would describe arrivals
     * that cannot happen — that deployment's problem is a missing environment variable,
     * which the admission list already says in the language that fixes it.
     */
    @Test
    fun `the member tier says nothing where it is everybody, or where nobody arrives`() {
        assertNull(
            InstanceIdentity(isCodeSignInAvailable = false).memberTierUnreachableReason,
            "A deployment with no domain reported a tier nobody could reach; it is everybody.",
        )
        assertNull(
            InstanceIdentity(
                domain = "acme.com",
                onlyHostedGoogleAccounts = true,
                isCodeSignInAvailable = false,
                isGoogleAvailable = false,
            ).memberTierUnreachableReason,
            "A deployment nobody can sign into claimed its arrivals were all staff.",
        )
    }

    /**
     * The staff-only policy needs a domain and nothing else — no door reaches it.
     *
     * It admits the deployment's own people, who arrive through whichever door exists,
     * so a closed outward door is not its problem. That is what makes the outsider rule
     * a rule about the *other two*.
     */
    @Test
    fun `staff-domain-only turns only on the domain`() {
        assertNull(
            InstanceIdentity(
                domain = "acme.com",
                onlyHostedGoogleAccounts = true,
                isCodeSignInAvailable = false,
            ).admissionState(AdmissionPolicy.ANYONE).options
                .first { it.policy == AdmissionPolicy.STAFF_DOMAIN_ONLY }
                .unavailableReason,
            "The strictest policy was greyed by a door it does not need.",
        )
        assertEquals(
            "this deployment has no domain of its own configured",
            InstanceIdentity(isCodeSignInAvailable = true)
                .admissionState(AdmissionPolicy.ANYONE).options
                .first { it.policy == AdmissionPolicy.STAFF_DOMAIN_ONLY }
                .unavailableReason,
        )
    }

    /**
     * Every combination of the four configuration facts, swept.
     *
     * The point of the sweep is the invariants rather than any one row: the list is
     * always three long, a greyed option always carries a reason and a live one never
     * does, and the two outward choices never disagree about whether an outsider can
     * arrive. LNL-192's two independent rules broke the last of those on four of these
     * sixteen rows, which is precisely how they shipped.
     */
    @Test
    fun `every configuration combination is internally consistent`() {
        for (domain in listOf(null, "acme.com")) {
            for (pinned in listOf(false, true)) {
                for (codes in listOf(false, true)) {
                    for (google in listOf(false, true)) {
                        val identity = InstanceIdentity(domain, pinned, codes, google)
                        val options = identity.admissionState(AdmissionPolicy.ANYONE).options
                        assertEquals(
                            AdmissionPolicy.entries,
                            options.map { it.policy },
                            "An option went missing under $identity.",
                        )
                        options.forEach { option ->
                            assertEquals(
                                option.isSelectable,
                                option.unavailableReason == null,
                                "${option.policy} was greyed without a reason, or live with one, under $identity.",
                            )
                        }
                        val anyone = options.first { it.policy == AdmissionPolicy.ANYONE }
                        val plusAdded = options.first { it.policy == AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED }
                        // "Plus added" additionally needs a domain, so it can be greyed where
                        // `anyone` is live — but never the other way round: both exist to admit
                        // an outsider, so nothing that closes one can leave the other open.
                        if (!anyone.isSelectable) {
                            assertFalse(
                                plusAdded.isSelectable,
                                "`anyone` was unreachable while `plus added` was offered under $identity.",
                            )
                        }
                    }
                }
            }
        }
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
        val state = InstanceIdentity(
            domain = "acme.com",
            onlyHostedGoogleAccounts = true,
            isCodeSignInAvailable = false,
        ).admissionState(AdmissionPolicy.ANYONE)
        assertEquals(AdmissionPolicy.ANYONE, state.selected, "The stranded selection was silently replaced.")
        val option = state.options.first { it.policy == state.selected }
        assertFalse(option.isSelectable)
        assertNotNull(option.unavailableReason)
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

    // ── The admission policy a deployment starts with (LNL-210) ──────────────

    /**
     * A deployment that cannot admit all comers does not open on saying it will.
     *
     * ANYONE is the standing default and is right for an unbranded install. On the
     * branded shape — a domain, a pinned chooser, no mail — it describes a deployment
     * this is not: Who-gets-in opened on "Anyone who can sign in", selected and
     * greyed, on an instance nobody outside the domain can reach at all.
     *
     * The stored value matters as much as the screen. Unpin the chooser or turn mail
     * on, and a default nobody chose becomes live — the instance would start taking
     * all comers off a change that was about sign-in ergonomics.
     */
    @Test
    fun `a deployment that cannot admit anyone settles on the policy it can honour`(): Unit = runBlocking {
        val settings = InMemoryInstanceSettingsStore()
        assertFalse(settings.current().isAdmissionStored, "Precondition: nothing has been chosen.")

        val settled = settleAdmissionPolicy(settings, BRANDED_IDENTITY)

        assertEquals(AdmissionPolicy.STAFF_DOMAIN_ONLY, settled)
        assertEquals(AdmissionPolicy.STAFF_DOMAIN_ONLY, settings.current().admission)
        // ...and it is a stored answer now, so the next boot leaves it alone.
        assertTrue(settings.current().isAdmissionStored)
        assertNull(settleAdmissionPolicy(settings, BRANDED_IDENTITY), "A second boot settled again.")
    }

    /**
     * A choice somebody made is never settled over, even a stranded one.
     *
     * This is the case `AdmissionState.selected` exists to report: an administrator
     * chose ANYONE, the deployment was pinned afterwards, and the screen shows their
     * choice greyed with the reason. Overwriting it would erase the one fact that
     * explains what they are looking at — and would quietly undo a policy the moment
     * the pin came off.
     */
    @Test
    fun `a stored policy survives, even one this deployment cannot honour`(): Unit = runBlocking {
        val settings = InMemoryInstanceSettingsStore()
        settings.setAdmissionPolicy(AdmissionPolicy.ANYONE)

        assertNull(settleAdmissionPolicy(settings, BRANDED_IDENTITY), "Somebody's choice was settled over.")
        assertEquals(AdmissionPolicy.ANYONE, settings.current().admission)
    }

    /**
     * ...and nothing is written where there is nothing to correct.
     *
     * Two shapes, and both would be wrong to touch. An unbranded install can honour
     * ANYONE, so the default already describes it. A deployment with no door at all
     * can honour nothing — every policy is unavailable there, and the fix is a
     * missing environment variable rather than a setting, which is what the admission
     * list already says.
     */
    @Test
    fun `an open deployment and a doorless one are both left alone`(): Unit = runBlocking {
        val open = InMemoryInstanceSettingsStore()
        assertNull(settleAdmissionPolicy(open, InstanceIdentity(domain = "acme.com")))
        assertFalse(open.current().isAdmissionStored, "An open deployment had a policy written for it.")

        val doorless = InMemoryInstanceSettingsStore()
        assertNull(
            settleAdmissionPolicy(
                doorless,
                InstanceIdentity(
                    domain = "acme.com",
                    onlyHostedGoogleAccounts = true,
                    isCodeSignInAvailable = false,
                    isGoogleAvailable = false,
                ),
            ),
        )
        assertFalse(doorless.current().isAdmissionStored, "A deployment nobody can reach was given a policy.")
    }

    /** The branded shape: a domain, a chooser pinned to it, and no mail. */
    private val BRANDED_IDENTITY = InstanceIdentity(
        domain = "acme.com",
        onlyHostedGoogleAccounts = true,
        isCodeSignInAvailable = false,
    )

    /** A brand dir holding just this manifest text. */
    private fun manifest(json: String): File {
        val dir = Files.createTempDirectory("brand-identity").toFile()
        File(dir, "brand.json").writeText(json)
        return dir
    }
}
