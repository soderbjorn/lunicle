/**
 * The persistence seam for who stands where — a person's rung in a project, and
 * the rungs a project hands to whole audiences.
 *
 * One of the LNL-111 domain store interfaces. The reference implementation is
 * SQLite's [se.soderbjorn.lunicle.RoleStore] (named by its fully-qualified name in
 * that class's supertype clause, since the two share a simple name), which reads
 * and writes `project_roles` and `project_audience_roles`.
 *
 * ── What this seam stores, and what it does not ─────────────────────────────
 *
 * Two tiny tables and nothing else:
 *
 *  - **one row per person per project**, holding a [ProjectRole] *name*. Not a
 *    row per privilege, not a column per privilege — what a rung permits is a
 *    function in [se.soderbjorn.lunicle.AccessControl], so widening a rung is a
 *    deploy rather than a migration.
 *  - **at most three rows per project**, one per [Audience], holding a rung the
 *    project hands to everybody who matches that audience. These replace
 *    `projects.is_public` and `projects.visible_to_all_signed_in`.
 *
 * The [ProjectRole] and [Audience] vocabularies are facts about this build and
 * live beside the SQLite store, not here: [se.soderbjorn.lunicle.AccessControl]
 * compares rungs by rank, so a rung invented at runtime would mean nothing.
 *
 * Nothing here decides anything, and nothing here combines the two tables. The
 * `max(audience, own row)` rule lives in exactly one place — see
 * [se.soderbjorn.lunicle.AccessControl.effectiveRole].
 *
 * Both backends must make a person's rungs readable **in one go** ([rolesForUser])
 * rather than a read per project, and must keep a project's audience rows where
 * the project already is — on its own row or its own document.
 *
 * @see se.soderbjorn.lunicle.store.RoleStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.Audience
import se.soderbjorn.lunicle.ProjectRole

interface RoleStore {
    /**
     * The rung [userId] holds in [projectId] by their own row, or null for "no row".
     *
     * **Not the answer to "what may they do here"** — an audience row may put them
     * higher, and never lower. Only [se.soderbjorn.lunicle.AccessControl] combines
     * the two.
     *
     * A row naming a rung this build has never heard of reads as null, which is the
     * safe reading: an unknown rung grants nothing rather than failing the read.
     */
    suspend fun roleFor(userId: Long, projectId: Long): ProjectRole?

    /**
     * Every rung [userId] holds anywhere, as project id → rung.
     *
     * One read, for the callers that would otherwise ask [roleFor] once per project
     * — listing projects, and any future per-issue fan-out. Backends are expected to
     * answer this from a single row set or a single document.
     */
    suspend fun rolesForUser(userId: Long): Map<Long, ProjectRole>

    /**
     * Every own-row grant in [projectId], as user id → rung.
     *
     * For the settings pane's Access section, and never to decide a permission:
     * that is an administrative question with a different audience, and the day this
     * map is used to gate a write is the day permissions live in two places.
     */
    suspend fun rolesForProject(projectId: Long): Map<Long, ProjectRole>

    /**
     * Everyone with an own row in [projectId] — [rolesForProject]'s keys, as a set.
     *
     * Its own method because [se.soderbjorn.lunicle.ProjectAudience] wants exactly
     * this and nothing else, and a backend can answer it without materialising the
     * rungs.
     */
    suspend fun memberIds(projectId: Long): Set<Long>

    /**
     * Put [userId] on [role] in [projectId], replacing whatever row they had; null
     * removes the row.
     *
     * Idempotent and single-valued — a person has one rung in a project, so there is
     * no "grant" and "revoke" pair to get out of step, and setting the rung somebody
     * already holds writes the same row again.
     */
    suspend fun setRole(userId: Long, projectId: Long, role: ProjectRole?)

    /** The rungs [projectId] hands to whole audiences. At most one row per [Audience]. */
    suspend fun audienceRoles(projectId: Long): Map<Audience, ProjectRole>

    /**
     * Set the rung [projectId] hands to [audience], replacing whatever was there;
     * null removes the row, which is how an audience is shut out again.
     */
    suspend fun setAudienceRole(projectId: Long, audience: Audience, role: ProjectRole?)
}
