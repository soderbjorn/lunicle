/**
 * Wire types for the instance-wide decisions an administrator makes for the whole
 * deployment: who may hold an account here at all ([AdmissionState]), whether a
 * project may be published to the world, what each tier of signed-in person may do,
 * and whether the display-name override is offered (LNL-137).
 *
 * These are not per-user preferences ([UiSettings] is that) and they are not a
 * per-project setting ([ProjectSettingsState] is that). They are facts about the
 * deployment, the same for everyone who asks, and only an instance administrator may
 * change them — `AccessControl.canAdministerInstance`. What is *not* here is the one
 * thing narrower than that: managing the set of projects across the whole instance
 * belongs to the instance owner alone, one rung above. They ride on
 * [AdminSettingsState], which is administrator-only — except
 * [InstanceSettingKey.HIDE_DISPLAY_NAME], which also reaches every client on
 * [SessionState] because the field it hides is one every signed-in user has.
 *
 * ── What is configuration and what is a setting (LNL-192) ───────────────────
 *
 * Everything here is a *setting*: stored, and an administrator's to change from a
 * screen. Beside it sits deploy-time *configuration* — the deployment's own domain,
 * whether the Google chooser is pinned to it, and whether a mailed code is a way in
 * — which lives in `brand.json` and no screen can edit. The two meet in exactly one
 * place, [AdmissionState]: a policy an administrator chose, and the configuration's
 * verdict on whether this deployment can honour it. The verdict is computed
 * server-side and sent, so a screen renders what it is handed rather than
 * re-deriving a rule from config it would have to be shown as well.
 *
 * @see se.soderbjorn.lunicle.clientserver.ApiRoutes.ADMIN_INSTANCE_SETTINGS
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * Which instance-wide switch a write is naming.
 *
 * A closed enum rather than a free-form key string, for the reason
 * [UiSettingKeys] is an allowlist: the set of switches this deployment has is the
 * server's to define, not something any signed-in browser can invent a new one of.
 * The server stores each under [storageKey]; that string is the database column
 * value and outlives any rename of the constant, exactly like [AuthProvider]'s
 * wire names.
 *
 * ── Two switches that used to be here, and are not (LNL-192) ────────────────
 *
 * `require_sign_in` is **retired**. It said "this deployment refuses to be used
 * signed out", which is now what a project's *guest* audience row says, one board
 * at a time — a project that admits no guests is not readable signed out, and one
 * that does is, without an instance-wide blanket having an opinion. Note it was
 * never the same question as [AdmissionState]: who may *read* a board and who may
 * *hold an account* are different, and conflating them is what the blanket did.
 *
 * `anyone_can_create_project` is **retired** too, replaced by the per-tier
 * [STAFF_MAY_CREATE_PROJECTS] / [MEMBER_MAY_CREATE_PROJECTS] pair. One boolean
 * could only say "everybody or nobody"; a deployment that wants its own people
 * making boards while outside collaborators do not had no way to say so.
 *
 * @property storageKey the key this switch is persisted under, server-side. Kept
 *   distinct from the constant's own name so a refactor of the Kotlin name is not
 *   a migration of the stored rows.
 */
@Serializable
enum class InstanceSettingKey(val storageKey: String) {
    /**
     * `allow_public_projects` — when on, a project's owner may hand its *guest*
     * audience a rung, which is what publishing a board to the world means now.
     * Off (the default) and the guest row is refused server-side and greyed in
     * every project's Access list, whoever asks.
     *
     * The instance-wide veto that replaces what the retired `require_sign_in`
     * blanket provided, and deliberately shaped as a veto rather than as a
     * default: a deployment that must never publish anything says so once, and no
     * project owner can undo it from inside their own board.
     */
    ALLOW_PUBLIC_PROJECTS("allow_public_projects"),

    /**
     * `staff_may_create_projects` — when on, anybody whose account belongs to the
     * deployment's own domain may bring a new project into existence.
     *
     * Off by default, like every permission this rework introduces: LNL-191
     * translated no privilege at all, and a switch that arrived on would be this
     * ticket handing out the power that one deliberately withheld. An instance
     * administrator and the instance owner may create projects regardless — they
     * are senior to both tiers.
     */
    STAFF_MAY_CREATE_PROJECTS("staff_may_create_projects"),

    /** `member_may_create_projects` — the same permission for everybody else signed in. */
    MEMBER_MAY_CREATE_PROJECTS("member_may_create_projects"),

    /**
     * `staff_may_use_agents` — when on, an account on the deployment's own domain
     * is *permitted* to connect an agent over MCP.
     *
     * Permission only. The person still turns agent access on for themselves from
     * their profile, and the server re-reads that switch on every request — so this
     * grants the possibility and never the access, which is why the sentence the
     * refusal already shows ("an administrator has not given your account agent
     * access") stays true.
     *
     * This is what replaces the per-account `users.mcp_allowed` LNL-191 dropped.
     * There is no per-person override anywhere in this design, deliberately: a
     * per-tier rule an admin can read off one screen is a thing they can be sure
     * of, and a column of exceptions beside it is not.
     */
    STAFF_MAY_USE_AGENTS("staff_may_use_agents"),

    /** `member_may_use_agents` — the same permission for everybody else signed in. */
    MEMBER_MAY_USE_AGENTS("member_may_use_agents"),

    /**
     * `hide_display_name` — when on, the display-name override in the settings
     * pane's You tab is hidden, so every user's name is the one their sign-in
     * provider gives and cannot be overridden here (LNL-137). Off (the default)
     * leaves the override offered, which is what it has always been. Alone among
     * these switches it reaches every client, not only the administrator, because the
     * field it hides is one every signed-in user has — it rides on
     * [SessionState.isDisplayNameHidden].
     */
    HIDE_DISPLAY_NAME("hide_display_name"),
}

/**
 * "Set this instance switch to this state."
 *
 * Names the desired state rather than saying "toggle": a retry says the same thing,
 * and two admins with the dialog open cannot flip one switch back and forth by both
 * clicking once.
 *
 * Note what it does not carry: who is asking. That comes from the session cookie,
 * server-side, on every request. A field for it would be the authorization system
 * asking the caller to authorize themselves.
 *
 * @property key which switch to change.
 * @property isEnabled the state to move it to.
 */
@Serializable
data class SetInstanceSettingRequest(
    val key: InstanceSettingKey,
    val isEnabled: Boolean,
)

/**
 * Who may hold an account on this deployment at all (LNL-192).
 *
 * Checked **once, when an account is about to be created**, and it grants access to
 * nothing: somebody admitted here arrives as a member or a staff member with
 * whatever the two ladders give that tier, which on a fresh instance is nothing.
 * Admission is the door, not the room.
 *
 * Not a switch, so not an [InstanceSettingKey]: three values rather than two, and
 * — unlike every switch — the deployment's configuration can make one of them
 * unhonourable. See [AdmissionState].
 *
 * @property key the stored/wire name, distinct from the constant's for
 *   [InstanceSettingKey.storageKey]'s reason.
 * @property label the sentence a screen shows beside the choice. Written here so
 *   the server and every client name a policy the same way.
 */
@Serializable
enum class AdmissionPolicy(val key: String, val label: String) {
    /** Anybody who can complete a sign-in gets an account. The default. */
    ANYONE("anyone", "Anyone who can sign in"),

    /** Only addresses on the deployment's own domain. */
    STAFF_DOMAIN_ONLY("staff_domain_only", "Only addresses on this organisation's domain"),

    /**
     * The deployment's own domain, plus any address an administrator has already
     * added.
     *
     * The policy for an instance that works with people outside the organisation
     * but will not take all comers. Note it widens *who may be added*, not who may
     * wander in: an outside address still has to have been put on the instance by
     * somebody before it can arrive.
     */
    STAFF_DOMAIN_PLUS_ADDED("staff_domain_plus_added", "This organisation's domain, plus addresses already added"),
    ;

    companion object {
        /** The policy with this [key], or null — an unknown key is not silently a permissive one. */
        fun byKey(key: String?): AdmissionPolicy? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One admission choice as this deployment can actually offer it.
 *
 * ── Why an unhonourable option is greyed and never removed ──────────────────
 *
 * A missing option teaches an administrator nothing: they look for a setting they
 * remember, do not find it, and have no idea whether it was renamed, withdrawn, or
 * never existed. A greyed one with a sentence beside it tells them the limit is the
 * *deployment's* — a decision in `brand.json` that a redeploy could change — rather
 * than theirs.
 *
 * @property policy which choice this is.
 * @property isSelectable whether this deployment could honour it.
 * @property unavailableReason why not, when it could not — a short clause meant to
 *   sit beside the greyed option, e.g. "Google sign-in is locked to example.com".
 *   Null exactly when [isSelectable] is true.
 */
@Serializable
data class AdmissionOption(
    val policy: AdmissionPolicy,
    val isSelectable: Boolean = true,
    val unavailableReason: String? = null,
)

/**
 * The admission setting, and this deployment's verdict on each of its choices.
 *
 * Computed server-side in full. A client renders [options] as it is handed them and
 * must not re-derive the greying: the rule reads deploy-time configuration
 * (`brand.json`) and the mail transport, neither of which a browser has any
 * business being shown, and two copies of that rule would disagree the first time
 * one of them was updated.
 *
 * @property selected the stored policy — **even when it is no longer selectable**.
 *   A configuration change can strand a choice an administrator made months ago;
 *   silently reporting a fallback would hide the one fact they need to fix it. The
 *   effective behaviour is the deployment's restriction either way, so nothing is
 *   gained by lying about what was chosen. Find [selected] in [options] to learn
 *   whether it is still honourable.
 * @property options every choice, in ladder order, never filtered.
 */
@Serializable
data class AdmissionState(
    val selected: AdmissionPolicy = AdmissionPolicy.ANYONE,
    val options: List<AdmissionOption> = emptyList(),
)

/**
 * "Admit this set of people."
 *
 * Names the desired policy rather than a delta, for [SetInstanceSettingRequest]'s
 * reason. Refused when the named policy is not [AdmissionOption.isSelectable] on
 * this deployment — a greyed choice that a hand-written request could still set
 * would make the greying an affordance, and the whole point of computing it
 * server-side is that it is not.
 */
@Serializable
data class SetAdmissionPolicyRequest(
    val policy: AdmissionPolicy,
)
