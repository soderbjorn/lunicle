/**
 * The persistence seam for the instance-wide switches an administrator sets for
 * the whole deployment (LNL-115).
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

import se.soderbjorn.lunicle.clientserver.InstanceSettingKey

/**
 * The instance switches, read together.
 *
 * A snapshot rather than a getter per switch, because every reader wants the set
 * at once — the session builder reads [requireSignIn] and [hideDisplayName], the
 * project routes read [anyoneCanCreateProject], and the admin dialog reads them all
 * — and one read is one query. A switch that has never been set reads as its default
 * (off), so a caller never has to tell "unset" from "off".
 */
data class InstanceSettings(
    val requireSignIn: Boolean = false,
    val anyoneCanCreateProject: Boolean = false,
    val hideDisplayName: Boolean = false,
)

/** Reads and writes the deployment-wide switches. */
interface InstanceSettingsStore {
    /**
     * Every switch, with those never set reading as their default (off).
     *
     * The defaults are the pre-LNL-115 behaviour: the app is usable signed out, and
     * only an administrator may create a project. A fresh volume, therefore, behaves
     * exactly as every deployment did before this table existed.
     */
    suspend fun current(): InstanceSettings

    /**
     * Set one switch, replacing whatever was there.
     *
     * Idempotent and unconditional: a switch has no history worth keeping and no
     * conflict worth reporting — last write wins.
     */
    suspend fun set(key: InstanceSettingKey, isEnabled: Boolean)
}
