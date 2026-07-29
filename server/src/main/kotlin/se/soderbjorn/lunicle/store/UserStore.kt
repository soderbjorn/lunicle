/**
 * The persistence seam for accounts.
 *
 * One of the LNL-111 domain store interfaces. The reference implementation is
 * today's SQLite [se.soderbjorn.lunicle.UserStore] (named by its fully-qualified
 * name in that class's supertype clause, since the two share a simple name).
 *
 * This is where the ticket's "legacy record missing a new field" parity case
 * lives, and it is the subtlest thing in the suite: [upsert] keys on **verified
 * e-mail** first and only falls back to the `(provider, provider_id)` pair, so a
 * returning user who signed in a different way but proves the same address is
 * reunited with their existing row rather than getting a second account. A
 * document backend must reproduce that exactly, including the unkeyed fallback for
 * an account whose address was never confirmed — which is why the contract asserts
 * both.
 *
 * @see se.soderbjorn.lunicle.store.UserStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.InstanceRole
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.UserKind
import se.soderbjorn.lunicle.UserRecord

interface UserStore {
    /**
     * Find the user behind a provider identity — by verified e-mail first, then by
     * the provider pair — creating them on first sign-in (the first ever becomes
     * the instance admin) and refreshing their provider name on every later one.
     *
     * @param kind the staff/member answer this sign-in earns, from
     *   [UserKind.forEmail]. Passed in rather than derived here because the rule
     *   needs the deployment's domain, which is configuration and not a fact a store
     *   should be holding. It defaults to the lesser value, so a caller that has no
     *   domain to match against — and every test fixture — lands on `member` and is
     *   corrected by the startup stamp rather than being over-privileged.
     */
    suspend fun upsert(identity: ProviderIdentity, kind: UserKind = UserKind.MEMBER): UserRecord

    /** The user with [id], or null. */
    suspend fun findById(id: Long): UserRecord?

    /** Every account. */
    suspend fun selectAll(): List<UserRecord>

    /** Set or clear the display-name override (blank normalises to cleared). */
    suspend fun setDisplayName(id: Long, name: String?)

    /** Set or clear the e-mail address and its verified flag. */
    suspend fun setEmail(id: Long, email: String?, isVerified: Boolean)

    /** The account's own agent-access switch. */
    suspend fun setMcpEnabled(id: Long, isEnabled: Boolean)

    /**
     * The derived staff/member kind. Written by sign-in and by the startup stamp,
     * both from [UserKind.forEmail], and by nothing else.
     */
    suspend fun setKind(id: Long, kind: UserKind)

    /**
     * Put this account on the instance ladder at [InstanceRole.ADMIN], or take it
     * off. Ownership is a setting and does not come through here — see
     * [InstanceSettingsStore.setOwnerUserId].
     */
    suspend fun setInstanceAdmin(id: Long, isAdmin: Boolean)
}
