/**
 * Vocabulary and project mutations for the demo (LNL-146), split out of
 * [DemoLunicleApi] so the API shell stays a flat list of endpoints.
 *
 * Each function mutates the [DemoWorld] in place, mirroring the effect of a real
 * server write. They are deliberately more forgiving than the server — a delete
 * that the real `VocabularyRepository` would refuse (a status still in use) instead
 * reassigns the affected issues so the demo board can never end up referencing a
 * vocabulary row that no longer exists.
 *
 * @see DemoLunicleApi
 * @see DemoWorld
 */
package se.soderbjorn.lunicle.demo

import se.soderbjorn.lunicle.clientserver.VocabularyKind

/** The real provisioning defaults a freshly created project starts with. */
internal fun provisionProject(
    world: DemoWorld,
    name: String,
    prefix: String,
): DemoProject {
    val p = DemoProject(
        id = world.allocId(),
        name = name.trim(),
        prefix = prefix.trim().uppercase(),
        discussionsEnabled = false,
        messagesEnabled = false,
        // What the INSTANCE says a new project starts out admitting (LNL-195), rather than
        // the fixtures' own members-as-viewers default. Nothing read that setting in the
        // demo before: a visitor could set "new projects admit staff as contributors" on the
        // Who-gets-in tab, create a board, and find it admitting members as viewers instead
        // — a switch that visibly did nothing, which is worse than one that is absent.
        //
        // Empty is the ordinary state and is what a real fresh instance has: a new project
        // admits nobody wholesale, and its creator's own row below is the only way in.
        audiences = world.newProjectAudiences.toMutableMap(),
    )
    listOf("New", "Backlog", "Ready for development", "In progress", "Ready for test", "Closed")
        .forEachIndexed { i, n -> p.statuses.add(DemoStatus(world.allocId(), n, i, requiresResolution = n == "Closed")) }
    listOf("Very high", "High", "Normal", "Low", "Very low")
        .forEachIndexed { i, n -> p.priorities.add(DemoStatus(world.allocId(), n, i)) }
    listOf("Done", "Will not fix", "Duplicate")
        .forEachIndexed { i, n -> p.resolutions.add(DemoStatus(world.allocId(), n, i, isDone = n == "Done")) }
    listOf("Bug", "Feature", "Improvement", "Codebase")
        .forEachIndexed { i, n -> p.labels.add(DemoNamed(world.allocId(), n, i)) }
    listOf("Desktop", "Server", "Android", "iOS")
        .forEachIndexed { i, n -> p.components.add(DemoNamed(world.allocId(), n, i)) }
    seedDefaultRelationKinds(world, p)
    // The creator owns the board.
    p.members[world.demoUserId] = DemoRungKeys.OWNER
    return p
}

/**
 * Give [p] the three relation kinds every real project is created with (LNL-215).
 *
 * The demo's copy of `ProjectRepository.DEFAULT_RELATION_KINDS`, names, inverses,
 * order and flag included. Its own function rather than three lines inside
 * [provisionProject] because the three seeded fixture boards need it too: they build
 * their vocabularies by hand — that is the whole point of them, three boards that share
 * no status name — and would otherwise each have to restate this list, which is three
 * places for the demo to drift from the product.
 *
 * The trio is chosen to cover the whole shape of the feature in one seeding, which is
 * why it is worth mirroring exactly rather than approximating:
 *
 *  - **Blocked by / Blocks** is the asymmetric pair that also *marks blocked*, so it is
 *    the only one that changes what a board looks like.
 *  - **Duplicate of / Duplicated by** is asymmetric and marks nothing — the proof that
 *    direction and blocking are two independent decisions rather than one.
 *  - **Related to** has a null inverse, which IS the encoding of symmetry: it reads the
 *    same word from both ends. See [DemoRelationKind.inverseName].
 *
 * @return the three, in order, so a fixture can destructure them and name its links
 *   after what they mean rather than looking each kind up by its seeded name. A lookup
 *   by name would work today and quietly stop working the moment the demo renames one,
 *   which is precisely the thing an administrator is invited to do.
 */
internal fun seedDefaultRelationKinds(world: DemoWorld, p: DemoProject): List<DemoRelationKind> {
    p.relationKinds.add(DemoRelationKind(world.allocId(), "Blocked by", "Blocks", marksBlocked = true, position = 0))
    p.relationKinds.add(DemoRelationKind(world.allocId(), "Duplicate of", "Duplicated by", position = 1))
    p.relationKinds.add(DemoRelationKind(world.allocId(), "Related to", null, position = 2))
    return p.relationKinds.toList()
}

/**
 * Append a row to one of a project's vocabularies, at the end of its order.
 *
 * @param inverseName a relation kind's to-side label, ignored by every other kind.
 *   Blank folds to null — symmetry — rather than to an empty second name, mirroring
 *   `VocabularyRepository.add`'s `takeIf { it.isNotBlank() }`: a kind whose inverse is
 *   the empty string would render a link labelled with nothing at all from one end.
 *   Taken at *add* time and not only at rename, so a kind is never briefly and visibly
 *   symmetric on its way to not being.
 * @param marksBlocked a relation kind's blocking flag, ignored by every other kind.
 */
internal fun addVocabularyRow(
    world: DemoWorld,
    p: DemoProject,
    kind: VocabularyKind,
    name: String,
    inverseName: String? = null,
    marksBlocked: Boolean = false,
) {
    when (kind) {
        VocabularyKind.LABEL -> p.labels.add(DemoNamed(world.allocId(), name, nextPosition(p.labels.map { it.position })))
        VocabularyKind.COMPONENT ->
            p.components.add(DemoNamed(world.allocId(), name, nextPosition(p.components.map { it.position })))
        VocabularyKind.VERSION ->
            p.versions.add(DemoNamed(world.allocId(), name, nextPosition(p.versions.map { it.position })))
        VocabularyKind.STATUS ->
            p.statuses.add(DemoStatus(world.allocId(), name, nextPosition(p.statuses.map { it.position })))
        VocabularyKind.PRIORITY ->
            p.priorities.add(DemoStatus(world.allocId(), name, nextPosition(p.priorities.map { it.position })))
        VocabularyKind.RESOLUTION ->
            p.resolutions.add(DemoStatus(world.allocId(), name, nextPosition(p.resolutions.map { it.position })))
        VocabularyKind.SPRINT ->
            p.sprints.add(DemoSprint(world.allocId(), name, nextPosition(p.sprints.map { it.position })))
        VocabularyKind.RELATION_KIND ->
            p.relationKinds.add(
                DemoRelationKind(
                    world.allocId(),
                    name,
                    inverseName?.trim()?.takeIf { it.isNotBlank() },
                    marksBlocked,
                    nextPosition(p.relationKinds.map { it.position }),
                ),
            )
    }
}

/**
 * Rename a row, and set whichever extra its kind carries — a status's closing flag, a
 * resolution's done flag, or a relation kind's inverse name and blocking flag.
 *
 * The extras are per-kind and every caller sends all of them, so each branch reads only
 * the ones its own kind has and lets the rest fall on the floor. That is the shape the
 * real `VocabularyEdit` takes on the wire, and for the same reason: one route family for
 * every vocabulary is what stops six near-identical ones from drifting.
 */
internal fun editVocabularyRow(
    p: DemoProject,
    kind: VocabularyKind,
    itemId: Long,
    name: String,
    requiresResolution: Boolean,
    isDone: Boolean,
    inverseName: String? = null,
    marksBlocked: Boolean = false,
) {
    when (kind) {
        VocabularyKind.LABEL -> p.labels.firstOrNull { it.id == itemId }?.name = name
        VocabularyKind.COMPONENT -> p.components.firstOrNull { it.id == itemId }?.name = name
        VocabularyKind.VERSION -> p.versions.firstOrNull { it.id == itemId }?.name = name
        VocabularyKind.STATUS -> p.statuses.firstOrNull { it.id == itemId }?.let {
            it.name = name
            it.requiresResolution = requiresResolution
        }
        VocabularyKind.PRIORITY -> p.priorities.firstOrNull { it.id == itemId }?.name = name
        VocabularyKind.RESOLUTION -> p.resolutions.firstOrNull { it.id == itemId }?.let {
            it.name = name
            it.isDone = isDone
        }
        VocabularyKind.SPRINT -> p.sprints.firstOrNull { it.id == itemId }?.name = name
        VocabularyKind.RELATION_KIND -> p.relationKinds.firstOrNull { it.id == itemId }?.let {
            it.name = name
            // Blank means symmetric, as it does on the way in — the row's "same in both
            // directions" checkbox clears this field, and clearing it is how a kind
            // becomes symmetric. Nothing else records symmetry, so an empty string kept
            // here would be a third state the renderer has no word for.
            it.inverseName = inverseName?.trim()?.takeIf { s -> s.isNotBlank() }
            it.marksBlocked = marksBlocked
        }
    }
}

/**
 * Delete a row. Where the real server would refuse a status/priority/resolution
 * still in use, the demo reassigns the affected issues to the first remaining row
 * so the board can never reference a vocabulary that is gone.
 */
internal fun deleteVocabularyRow(p: DemoProject, kind: VocabularyKind, itemId: Long) {
    when (kind) {
        VocabularyKind.LABEL -> {
            p.labels.removeAll { it.id == itemId }
            p.issues.forEach { it.labelIds.remove(itemId) }
        }
        VocabularyKind.COMPONENT -> {
            p.components.removeAll { it.id == itemId }
            p.issues.forEach { it.componentIds.remove(itemId) }
        }
        VocabularyKind.VERSION -> {
            p.versions.removeAll { it.id == itemId }
            p.issues.forEach {
                if (it.plannedVersionId == itemId) it.plannedVersionId = null
                if (it.fixedVersionId == itemId) it.fixedVersionId = null
            }
        }
        VocabularyKind.STATUS -> {
            if (p.statuses.size <= 1) return
            p.statuses.removeAll { it.id == itemId }
            val fallback = p.statuses.minByOrNull { it.position } ?: return
            p.issues.filter { it.statusId == itemId }.forEach { it.statusId = fallback.id }
        }
        VocabularyKind.PRIORITY -> {
            if (p.priorities.size <= 1) return
            p.priorities.removeAll { it.id == itemId }
            val fallback = p.priorities.sortedBy { it.position }.let { it[it.size / 2] }
            p.issues.filter { it.priorityId == itemId }.forEach { it.priorityId = fallback.id }
        }
        VocabularyKind.RESOLUTION -> {
            p.resolutions.removeAll { it.id == itemId }
            p.issues.filter { it.resolutionId == itemId }.forEach { it.resolutionId = null }
        }
        VocabularyKind.SPRINT -> {
            p.sprints.removeAll { it.id == itemId }
            p.issues.filter { it.sprintId == itemId }.forEach { it.sprintId = null }
            if (p.activeSprintId == itemId) p.activeSprintId = null
        }
        // The links go with the kind, rather than being released the way a deleted
        // version releases the issues that named it (LNL-215). That is the schema's own
        // ON DELETE CASCADE, and it is the right one here: a relation stripped of its
        // kind would be two issue ids and no statement about them, where an issue
        // stripped of its fixed version is an ordinary issue. The settings row shows the
        // count first, so this is never a surprise.
        VocabularyKind.RELATION_KIND -> {
            p.relationKinds.removeAll { it.id == itemId }
            p.relations.removeAll { it.kindId == itemId }
        }
    }
}

/** Put one whole vocabulary in the given order, reassigning positions by index. */
internal fun reorderVocabularyRows(p: DemoProject, kind: VocabularyKind, ids: List<Long>) {
    fun index(id: Long): Int = ids.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it }
    when (kind) {
        VocabularyKind.LABEL -> p.labels.forEach { it.position = index(it.id) }
        VocabularyKind.COMPONENT -> p.components.forEach { it.position = index(it.id) }
        VocabularyKind.VERSION -> p.versions.forEach { it.position = index(it.id) }
        VocabularyKind.STATUS -> p.statuses.forEach { it.position = index(it.id) }
        VocabularyKind.PRIORITY -> p.priorities.forEach { it.position = index(it.id) }
        VocabularyKind.RESOLUTION -> p.resolutions.forEach { it.position = index(it.id) }
        VocabularyKind.SPRINT -> p.sprints.forEach { it.position = index(it.id) }
        VocabularyKind.RELATION_KIND -> p.relationKinds.forEach { it.position = index(it.id) }
    }
}

private fun nextPosition(positions: List<Int>): Int = (positions.maxOrNull() ?: -1) + 1
