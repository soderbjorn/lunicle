/**
 * The persistence seam for the instance-wide decisions an administrator makes for
 * the whole deployment (LNL-115, reshaped by LNL-192).
 *
 * One of the domain store interfaces introduced by LNL-111, and shaped exactly
 * like its neighbours: `suspend` methods over plain values, nothing about tables
 * or upserts leaking through, so a document backend can satisfy it without
 * pretending to be relational. The store contract test suite pins the behaviour
 * once so two backends cannot drift.
 *
 * Unlike [UiSettingsStore] there is no user in the signature: these switches
 * belong to the instance, not to whoever is asking. That is the whole distinction
 * between the two, and it is why they are two interfaces rather than one keyed on a
 * nullable user.
 *
 * @see se.soderbjorn.lunicle.clientserver.InstanceSettingKey for the switches.
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.Audience
import se.soderbjorn.lunicle.InstanceRole
import se.soderbjorn.lunicle.ProjectRole
import se.soderbjorn.lunicle.clientserver.AdmissionPolicy
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey

/**
 * The instance settings, read together.
 *
 * A snapshot rather than a getter per setting, because every reader wants the set
 * at once — the session builder reads [hideDisplayName], the project routes read
 * [permitsProjectCreation], the MCP gates read [permitsAgents], and the admin dialog
 * reads them all — and one read is one query. A switch that has never been set reads
 * as its default (off), so a caller never has to tell "unset" from "off".
 *
 * ── Everything new here is off (LNL-192) ────────────────────────────────────
 *
 * The four per-tier permissions and the public-projects veto all default to the
 * closed answer, which is the same decision LNL-191 made when it translated no
 * privilege at all: a permission that arrives on is a permission nobody chose to
 * give. [admission] is the one exception and is not a permission — it is the door,
 * and an unbranded install has every way in available and nothing restricted.
 */
data class InstanceSettings(
    val hideDisplayName: Boolean = false,
    /**
     * Who may hold an account here at all. Checked once, when one is about to be
     * created; it grants access to nothing. See [AdmissionPolicy].
     */
    val admission: AdmissionPolicy = AdmissionPolicy.ANYONE,
    /**
     * Whether [admission] is a stored answer rather than the default standing in.
     *
     * The one place this collapse matters. Every other setting here reads as its
     * default when unset and no caller needs to tell the two apart — but "nobody has
     * chosen" and "somebody chose ANYONE" mean opposite things to a deployment that
     * cannot honour ANYONE. A stored one is an administrator's stranded choice and is
     * reported back to them, greyed, so they can see what to fix (see
     * `AdmissionState.selected`). An unstored one is a default that describes a
     * deployment this is not.
     *
     * Read by `settleAdmissionPolicy` at boot, and by nothing else.
     */
    val isAdmissionStored: Boolean = false,
    /**
     * Whether a project's owner may hand its *guest* audience a rung — publishing a
     * board to the world.
     *
     * A veto, and enforced rather than merely rendered: while it is off,
     * `AccessControl.canSetAudience` refuses a guest row whoever asks. See
     * [InstanceSettingKey.ALLOW_PUBLIC_PROJECTS].
     */
    val allowPublicProjects: Boolean = false,
    /** Whether the staff tier may create projects. See [permitsProjectCreation]. */
    val staffMayCreateProjects: Boolean = false,
    /** Whether the member tier may create projects. See [permitsProjectCreation]. */
    val memberMayCreateProjects: Boolean = false,
    /** Whether the staff tier is permitted to connect an agent. See [permitsAgents]. */
    val staffMayUseAgents: Boolean = false,
    /** Whether the member tier is permitted to connect an agent. See [permitsAgents]. */
    val memberMayUseAgents: Boolean = false,
    /**
     * Who owns this deployment — the top of the instance ladder — or null if nobody
     * does yet (LNL-191).
     *
     * **The one setting here that is not a switch**, and it is here rather than as a
     * third value on `users.instance_role` on purpose: as a column value, "exactly
     * one owner, always" needs a partial unique index to enforce, and Firestore has
     * no equivalent — so the two backends would disagree about the invariant the
     * whole permission model rests on. As a single-valued setting it is structural
     * in both: there is one field, so there is one owner.
     *
     * Null on a volume that has never had an owner seated, which the startup pass
     * repairs by seating the first administrator — and so does every sign-in, which
     * is what a fresh instance actually needs: its boot happens before there is
     * anybody to seat. See stampUserKinds' neighbour, seatInstanceOwner.
     */
    val ownerUserId: Long? = null,
    /**
     * The audience rows a **new** project is created with (LNL-195).
     *
     * ── Copied once, and never consulted again ──────────────────────────────
     *
     * Read at the moment a project is created and written into that project's own
     * `project_audience_roles`; from then on the project's Access list is the only
     * thing that decides who it admits. Changing this setting therefore changes
     * nothing about any project that already exists, which is what makes it safe to
     * be an ordinary setting rather than a live policy layered under every board.
     *
     * Empty by default, which is LNL-194's decision restated rather than softened: a
     * project that admits nobody until somebody says otherwise is the closed answer,
     * and this is the setting that lets a deployment choose a different one **on
     * purpose**. The creator holds Owner either way — that is seated by the create
     * route, not by this map.
     *
     * An audience absent from the map means no row for it. [Audience.GUEST] is
     * additionally subject to the [allowPublicProjects] veto at creation time, so a
     * deployment cannot use this setting to publish boards it has forbidden itself
     * from publishing.
     */
    val newProjectAudiences: Map<Audience, ProjectRole> = emptyMap(),
) {
    /**
     * May somebody standing at [role] create a project?
     *
     * The one place the per-tier rule lives, so the create gate and the affordance
     * the project list sends cannot answer it differently. An instance
     * administrator and the instance owner may regardless: they are senior to both
     * tiers, and a deployment whose owner could not make a board would be one
     * nobody could set up.
     */
    fun permitsProjectCreation(role: InstanceRole): Boolean = when {
        role.atLeast(InstanceRole.ADMIN) -> true
        role == InstanceRole.STAFF -> staffMayCreateProjects
        role == InstanceRole.MEMBER -> memberMayCreateProjects
        // A guest has no account to hang an agent or a project off.
        else -> false
    }

    /**
     * Is somebody standing at [role] *permitted* to connect an agent?
     *
     * Permission and never access — the person's own `mcp_enabled` switch is the
     * other half, re-read on every request. See `canUseMcp`, the only thing a gate
     * should read.
     */
    fun permitsAgents(role: InstanceRole): Boolean = when {
        role.atLeast(InstanceRole.ADMIN) -> true
        role == InstanceRole.STAFF -> staffMayUseAgents
        role == InstanceRole.MEMBER -> memberMayUseAgents
        else -> false
    }
}

/** Reads and writes the deployment-wide settings. */
interface InstanceSettingsStore {
    /**
     * Every setting, with those never set reading as their default.
     *
     * Every permission defaults to off and admission defaults to
     * [AdmissionPolicy.ANYONE], so a fresh volume admits anybody who can sign in and
     * hands them nothing — see [InstanceSettings].
     */
    suspend fun current(): InstanceSettings

    /**
     * Set one switch, replacing whatever was there.
     *
     * Idempotent and unconditional: a switch has no history worth keeping and no
     * conflict worth reporting — last write wins.
     */
    suspend fun set(key: InstanceSettingKey, isEnabled: Boolean)

    /**
     * Hand the deployment to [userId], or to nobody.
     *
     * Not part of [set], because that takes an [InstanceSettingKey] and a boolean —
     * a closed enum of switches, which this is not one of. Same last-write-wins
     * semantics: transferring ownership has no history worth keeping here (the
     * *audit* of who did it belongs to the route that did it, not to the setting).
     */
    suspend fun setOwnerUserId(userId: Long?)

    /**
     * Set who may hold an account here.
     *
     * Not part of [set] for [setOwnerUserId]'s reason: that takes an
     * [InstanceSettingKey] and a boolean, a closed enum of switches, and admission
     * is neither. Whether the deployment can *honour* the policy is not this
     * store's question — it is configuration, resolved at the route. Last write
     * wins, as everywhere here.
     */
    suspend fun setAdmissionPolicy(policy: AdmissionPolicy)

    /**
     * Say what rung [audience] arrives at in a **newly created** project, or hand it
     * nothing at all (LNL-195).
     *
     * One audience per call rather than the whole map, so the screen's three rows are
     * three independent writes and a concurrent edit of one cannot silently revert
     * another. Null removes the row, which is different from writing
     * [ProjectRole.VIEWER] and is the state a fresh instance is in.
     *
     * Not part of [set] for [setAdmissionPolicy]'s reason, twice over: this is neither
     * a boolean nor a single value.
     */
    suspend fun setNewProjectAudience(audience: Audience, role: ProjectRole?)
}

/**
 * The key one new-project audience row is stored under, spelled once (LNL-195).
 *
 * Here rather than in either store because **both** backends write it — a row in
 * `instance_settings` on SQLite, an entry in the one `values` map on Firestore — and
 * two copies of the string is exactly how the two would come to disagree about which
 * setting a stored value belongs to. Derived from [Audience.key] so it moves with the
 * wire name and not with the constant's.
 */
internal fun newProjectAudienceKey(audience: Audience): String = "new_project_audience_${audience.key}"
