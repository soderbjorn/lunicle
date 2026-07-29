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
 */
data class InstanceIdentity(
    val domain: String? = null,
    val onlyHostedGoogleAccounts: Boolean = false,
    val isCodeSignInAvailable: Boolean = true,
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
     * Every admission choice, with the ones this deployment cannot honour greyed and
     * the reason spelled out.
     *
     * ── Computed here, and sent, and never re-derived by a screen ────────────
     *
     * The inputs are a manifest file and a mail transport. A client that decided this
     * for itself would have to be handed both, and would be a second copy of a rule
     * that has to agree with the one the *write* enforces — so the greying and the
     * refusal would drift the first time either was touched. The screen renders what
     * it is handed.
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
     * Three reasons, each traceable to one configuration field:
     *
     *  - **No domain.** Both staff policies name a domain the deployment does not have,
     *    so they would admit *nobody*. An option that admits nobody is not a stricter
     *    setting, it is a locked door with no key.
     *  - **The Google chooser is pinned.** [AdmissionPolicy.ANYONE] promises to take
     *    all comers, and a pinned chooser has already refused most of them.
     *  - **No code sign-in.** [AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED] exists so an
     *    outside address can be added and then arrive. With no mailed code there is no
     *    way for it to arrive, so the "plus added" half is a promise the deployment
     *    cannot keep.
     *
     * The no-domain reason is checked first where two apply, because it is the one that
     * would still be true if the other were fixed.
     */
    private fun unavailableReason(policy: AdmissionPolicy): String? = when (policy) {
        AdmissionPolicy.ANYONE ->
            googleHostedDomainPin?.let { "Google sign-in is locked to $it" }

        AdmissionPolicy.STAFF_DOMAIN_ONLY ->
            NO_DOMAIN_REASON.takeIf { !hasStaffTier }

        AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED -> when {
            !hasStaffTier -> NO_DOMAIN_REASON
            !isCodeSignInAvailable -> "code sign-in is off"
            else -> null
        }
    }

    private companion object {
        const val NO_DOMAIN_REASON = "this deployment has no domain of its own configured"
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
 */
internal fun BrandInfo?.toInstanceIdentity(isCodeSignInAvailable: Boolean): InstanceIdentity =
    InstanceIdentity(
        domain = this?.domain,
        onlyHostedGoogleAccounts = this?.onlyHostedGoogleAccounts ?: false,
        isCodeSignInAvailable = isCodeSignInAvailable,
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
