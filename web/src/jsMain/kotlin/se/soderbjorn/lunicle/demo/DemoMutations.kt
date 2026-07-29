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
    // The creator owns the board.
    p.members[world.demoUserId] = DemoRungKeys.OWNER
    return p
}

/** Append a row to one of a project's vocabularies, at the end of its order. */
internal fun addVocabularyRow(world: DemoWorld, p: DemoProject, kind: VocabularyKind, name: String) {
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
    }
}

/** Rename a row, and set a status's closing flag or a resolution's done flag. */
internal fun editVocabularyRow(
    p: DemoProject,
    kind: VocabularyKind,
    itemId: Long,
    name: String,
    requiresResolution: Boolean,
    isDone: Boolean,
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
    }
}

private fun nextPosition(positions: List<Int>): Int = (positions.maxOrNull() ?: -1) + 1
