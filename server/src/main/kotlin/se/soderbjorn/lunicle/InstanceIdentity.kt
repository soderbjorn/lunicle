/**
 * What a deployment says about itself, and who that lets it admit (LNL-192).
 *
 * ── Three fields that used to be one ────────────────────────────────────────
 *
 * `brand.json` had exactly one field with an opinion about people: `googleHostedDomain`,
 * whose job is to pin the Google account chooser (LNL-125). LNL-191 then derived
 * staff-ness from it as a stopgap, and that is what this file exists to stop. Reusing
 * it made "who is staff here" a side effect of an OAuth detail: a deployment that does
 * not use Google sign-in had no way to name its own domain at all, and one that wanted
 * its domain to *mean* something had to force everybody through a hosted Google
 * account to say so.
 *
 * So there are three fields now, and each does one job:
 *
 *  - **`domain`** — the organisation's own domain. **Identity only**: the sole input to
 *    `users.kind`, and nothing else reads it. Unset by default, and unset means there is
 *    no staff tier at all — everybody signed in is a member, and the `staff` audience is
 *    simply unusable. Nothing typed into the app can invent a staff tier; it is a
 *    deploy-time fact or it is nothing.
 *  - **`onlyHostedGoogleAccounts`** — whether the Google chooser is pinned to [domain].
 *    **Sign-in ergonomics only**: it grants nothing and denies nobody a rung. Default
 *    off.
 *  - **`allowEmailCodeSignIn`** — whether somebody without a Google account can get in
 *    with a mailed code. Default **on**: an unbranded install has every way in available
 *    and nothing restricted. It can only ever *narrow* — see [isCodeSignInAvailable].
 *
 * ── The compatibility story, and why there is no migration ──────────────────
 *
 * A deployment that sets only `googleHostedDomain` — which is every deployment that
 * exists today — behaves identically: the legacy field seeds [domain] *and* turns
 * [onlyHostedGoogleAccounts] on, because that is precisely what it used to mean. The day
 * a manifest names the new fields, the legacy one stops being consulted for them and the
 * two come apart. Nothing is stored, so there is nothing to migrate: this is all read
 * from a file at boot. See [resolveInstanceIdentity].
 *
 * @see BrandRoutes for the manifest reader.
 * @see UserKind.forEmail, the one thing [domain] feeds.
 */
package se.soderbjorn.lunicle

import se.soderbjorn.lunicle.clientserver.AdmissionOption
import se.soderbjorn.lunicle.clientserver.AdmissionPolicy
import se.soderbjorn.lunicle.clientserver.AdmissionState

/**
 * The deployment's own identity and the doors into it, resolved once at boot.
 *
 * Deploy-time configuration, never a setting: no screen can edit any of this, and
 * that is the point of it being here rather than in `instance_settings`. An
 * administrator chooses a policy; this decides which policies the deployment is
 * capable of honouring.
 *
 * @property domain the organisation's own domain, or null when it has none. Null is
 *   the default and means **no staff tier**: [UserKind.forEmail] returns `member` for
 *   everybody, so a `staff` audience row matches nobody. That is honest rather than
 *   broken — a deployment that cannot tell its own people apart should not pretend to.
 * @property onlyHostedGoogleAccounts whether the Google chooser is pinned to [domain].
 *   Inert without a [domain] to pin it to; see [googleHostedDomainPin].
 * @property isCodeSignInAvailable whether a mailed code is actually a way in **on this
 *   process** — the manifest's `allowEmailCodeSignIn` already ANDed with a configured
 *   transport and with `LUNICLE_EMAIL_SIGN_IN`. The effective answer and never the
 *   claim: the branded deployment has SMTP deliberately off, so its answer stays "no"
 *   whatever the manifest says. See [OAuthConfig.isEmailAvailable], which is where the
 *   three terms meet.
 * @property isGoogleAvailable whether Google sign-in is configured **on this process**
 *   — `OAuthConfig.google != null`, which is two environment variables and not
 *   anything the manifest says (LNL-195). It is here because the admission rule below
 *   cannot be asked without it: a deployment with no Google credentials and no mail
 *   transport has no door at all, and every rule written before this field existed
 *   quietly assumed Google was one. Defaults to true, which is the shape of every
 *   deployment that has ever run and keeps a test fixture saying nothing about
 *   providers from accidentally describing a deployment nobody can reach.
 */
data class InstanceIdentity(
    val domain: String? = null,
    val onlyHostedGoogleAccounts: Boolean = false,
    val isCodeSignInAvailable: Boolean = true,
    val isGoogleAvailable: Boolean = true,
) {
    /** Does this deployment have a staff tier at all? False unless a [domain] is configured. */
    val hasStaffTier: Boolean get() = !domain.isNullOrBlank()

    /**
     * The domain to pin Google's account chooser to, or null for an open chooser.
     *
     * The one place the two fields are allowed to meet, and it is a `takeIf` rather
     * than an equality: pinning needs both a domain to pin *to* and a decision to pin,
     * and a deployment that names its domain without asking for the pin gets the open
     * chooser it asked for. Fed to `exchangeGoogleCode`'s server-side gate and to the
     * client's `hd` hint, which is the whole of this field's job.
     */
    val googleHostedDomainPin: String? get() = domain?.takeIf { it.isNotBlank() && onlyHostedGoogleAccounts }

    /**
     * Can anybody sign in here at all?
     *
     * False only for a deployment with no Google credentials *and* no working mailed
     * code — which is a real configuration (it is what a container missing its
     * variables looks like) and a different problem from a restriction. Every
     * admission choice is unreachable there, and saying so in the language of domains
     * would send an administrator to `brand.json` to fix an environment variable.
     */
    val hasAnyWayIn: Boolean get() = isGoogleAvailable || isCodeSignInAvailable

    /**
     * The ways in, named for a screen: "Google · mailed code", or the absence of both.
     *
     * Written here rather than in a view because it is the same two facts the rules
     * below are computed from, and a screen that assembled its own list could describe
     * a door the greying does not believe in.
     */
    val waysIn: List<String>
        get() = listOfNotNull(
            "Google".takeIf { isGoogleAvailable },
            "mailed code".takeIf { isCodeSignInAvailable },
        )

    /**
     * Can somebody from outside [domain] reach a sign-in at all?
     *
     * ── One question of the whole configuration, and why it has to be (LNL-195) ──
     *
     * LNL-192 asked two independent questions instead — the chooser being pinned greyed
     * `anyone`, code sign-in being off greyed `staff domain plus added` — and each was
     * written as though the other door did not exist. Both were wrong in the case that
     * matters. Chooser pinned *and* codes on: `anyone` is perfectly reachable, because
     * a stranger gets a mailed code. Chooser open *and* codes off: an added outside
     * address still arrives under its own Google account, so "plus added" is honourable.
     * And neither rule covered Google being unconfigured, where the pin is irrelevant
     * because there is no chooser.
     *
     * So it is one predicate over both doors, and both choices that exist in order to
     * admit somebody outside the domain live and die with it:
     *
     *     outsiderCanArrive = (googleAvailable && !googlePinned) || codesAvailable
     *
     * The Google term is a **real gate** and not a hint: `exchangeGoogleCode` refuses
     * an account whose `hd` claim does not match the pin, so a pinned chooser is a
     * closed door and not merely a pre-filled one.
     */
    val outsiderCanArrive: Boolean
        get() = (isGoogleAvailable && googleHostedDomainPin == null) || isCodeSignInAvailable

    /**
     * Every admission choice, with the ones this deployment cannot honour greyed and
     * the reason spelled out.
     *
     * ── Computed here, and sent, and never re-derived by a screen ────────────
     *
     * The inputs are a manifest file, a mail transport and two environment variables.
     * A client that decided this for itself would have to be handed all of them, and
     * would be a second copy of a rule that has to agree with the one the *write*
     * enforces — so the greying and the refusal would drift the first time either was
     * touched. The screen renders what it is handed.
     *
     * @param selected the stored policy, reported back as the selection **even when it
     *   is no longer selectable**. A configuration change can strand a choice somebody
     *   made months ago; falling back silently would hide the one fact an administrator
     *   needs in order to fix it, and the effective behaviour is the deployment's
     *   restriction either way.
     */

    fun admissionState(selected: AdmissionPolicy): AdmissionState = AdmissionState(
        selected = selected,
        options = AdmissionPolicy.entries.map { policy ->
            when (val reason = unavailableReason(policy)) {
                null -> AdmissionOption(policy, isSelectable = true, unavailableReason = null)
                else -> AdmissionOption(policy, isSelectable = false, unavailableReason = reason)
            }
        },
    )

    /**
     * Why this deployment cannot honour [policy], or null when it can.
     *
     * Three reasons, in the order a fix would have to be applied in:
     *
     *  - **No way in at all.** Nothing is honourable, because nobody can arrive. Its
     *    own reason and deliberately not worded as a restriction: the fix is two
     *    environment variables or a mail transport, not a line in `brand.json`, and
     *    borrowing the domain wording would send an administrator to the wrong file.
     *  - **No domain.** Both staff policies name a domain the deployment does not have,
     *    so they would admit *nobody*. An option that admits nobody is not a stricter
     *    setting, it is a locked door with no key. Checked before the outsider rule
     *    because it is the one that would still be true if that were fixed.
     *  - **No outsider can arrive.** [AdmissionPolicy.ANYONE] promises to take all
     *    comers and [AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED] exists so an added
     *    outside address can then arrive; both are promises this deployment cannot keep
     *    when every door it has is closed to somebody outside the domain. **The two
     *    live and die together** — see [outsiderCanArrive], which is where LNL-192's
     *    two independent half-rules became one question of the whole configuration.
     */
    private fun unavailableReason(policy: AdmissionPolicy): String? {
        if (!hasAnyWayIn) return NO_WAY_IN_REASON
        return when (policy) {
            AdmissionPolicy.ANYONE -> outsiderReason.takeIf { !outsiderCanArrive }

            AdmissionPolicy.STAFF_DOMAIN_ONLY -> NO_DOMAIN_REASON.takeIf { !hasStaffTier }

            AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED -> when {
                !hasStaffTier -> NO_DOMAIN_REASON
                !outsiderCanArrive -> outsiderReason
                else -> null
            }
        }
    }

    /**
     * Why nobody outside the domain can arrive, named field by field.
     *
     * Reached only when [hasAnyWayIn] holds and [outsiderCanArrive] does not, which
     * pins the configuration down to exactly one shape: Google configured, the chooser
     * pinned, and no mailed code. It is still assembled from the live fields rather
     * than written as that one sentence, so a future third door cannot leave this
     * describing a deployment that no longer exists.
     */
    private val outsiderReason: String
        get() = listOfNotNull(
            googleHostedDomainPin?.let { "Google sign-in is locked to $it" },
            "Google sign-in is not configured here".takeIf { !isGoogleAvailable },
            "this deployment cannot mail a sign-in code".takeIf { !isCodeSignInAvailable },
        ).joinToString(", and ")

    private companion object {
        const val NO_DOMAIN_REASON = "this deployment has no domain of its own configured"

        /** The no-door reason. Never borrows the domain wording; see [unavailableReason]. */
        const val NO_WAY_IN_REASON = "this deployment has no way to sign in"
    }
}

/**
 * Resolve what this process should believe about itself: the brand manifest's three
 * identity fields, plus the one fact only the running server knows.
 *
 * Null receiver — an unbranded install — yields no domain, no chooser pin, and
 * whatever the mail transport actually supports. That is the shape every default
 * deployment and every test fixture takes, and it is deliberately the permissive one:
 * two audiences, nothing greyed, nothing restricted.
 *
 * @param isCodeSignInAvailable the *effective* answer, from [OAuthConfig.isEmailAvailable]
 *   — the manifest's `allowEmailCodeSignIn` already ANDed with a configured transport
 *   and with `LUNICLE_EMAIL_SIGN_IN`. Passed in rather than read from the manifest so
 *   this type can never claim a door that is not there.
 * @param isGoogleAvailable whether Google credentials reached this process
 *   (`OAuthConfig.google != null`). Defaulted to true for the callers that only want
 *   [InstanceIdentity.googleHostedDomainPin] — a term Google's availability does not
 *   enter into — and passed honestly by [Application.module], which is the one place
 *   the answer is used to decide whether anybody can sign in at all.
 */
internal fun BrandInfo?.toInstanceIdentity(
    isCodeSignInAvailable: Boolean,
    isGoogleAvailable: Boolean = true,
): InstanceIdentity =
    InstanceIdentity(
        domain = this?.domain,
        onlyHostedGoogleAccounts = this?.onlyHostedGoogleAccounts ?: false,
        isCodeSignInAvailable = isCodeSignInAvailable,
        isGoogleAvailable = isGoogleAvailable,
    )

/**
 * May an account be **created** for [email] under this policy?
 *
 * ── Once, at creation, and it grants nothing ────────────────────────────────
 *
 * Admission is the door. Somebody admitted here arrives as a member or a staff member
 * with whatever the two ladders give that tier, which on a fresh instance is nothing at
 * all — so a permissive admission policy is not a permissive deployment. It is asked
 * exactly once, at the moment a row would be inserted; an account that already exists
 * is signing in, not being created, and a policy change never locks somebody out of the
 * account they already hold.
 *
 * @param isAlreadyAdded whether this address is one somebody on the instance has already
 *   put here — today, whether an account already holds it. It is a parameter rather than
 *   a lookup so the rule stays a function of its inputs, and so the gesture that lets an
 *   administrator add an outside address ahead of time asks this same function rather
 *   than growing a second opinion beside it.
 */
fun AdmissionPolicy.admitsNewAccount(
    email: String?,
    identity: InstanceIdentity,
    isAlreadyAdded: Boolean = false,
): Boolean {
    val isStaff = UserKind.forEmail(email, identity.domain) == UserKind.STAFF
    return when (this) {
        AdmissionPolicy.ANYONE -> true
        AdmissionPolicy.STAFF_DOMAIN_ONLY -> isStaff
        AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED -> isStaff || isAlreadyAdded
    }
}
