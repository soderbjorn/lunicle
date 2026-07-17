/**
 * Backing view-model for the project dialog — new and edit are the same screen.
 *
 * Same convention as everything else: one immutable [State] over one
 * [StateFlow], every decision here, nothing in the view.
 *
 * The decision worth naming is [State.isOkEnabled]. The spec asks for OK to be
 * disabled "if we did not fill in a unique project name, or if we try to rename
 * a project to a name that already exists" — so the dialog knows the other
 * projects' names, and answers locally as the user types. That is an
 * affordance: it exists so nobody fills in a form that is going to be refused.
 * The server checks again on submit, and the 409 it sends back is shown
 * verbatim — because the server is the only thing that can *know*, and it also
 * catches the case this cannot see (someone else creating "Lunamux" while this
 * dialog was open).
 *
 * ── Two dialogs in one, and why the halves behave differently ────────────────
 *
 * Editing an existing project also shows its **vocabularies** and its
 * **privileges**, and those sections do not obey the form around them. The form
 * is a draft: nothing is written until OK, and Cancel throws it away. The
 * sections are not — adding a status writes a status, immediately, and there is
 * no OK to press and nothing for Cancel to undo. That is not an inconsistency to
 * fix; it is what the two things are. A vocabulary editor that batched its edits
 * into the OK button would have to hold "these three renames, that delete and a
 * reorder" as pending state and replay it in an order the server accepts — which
 * is a transaction, invented in a browser, on someone else's machine.
 *
 * The consequence is [State.hasWrittenSettings]: Cancel still reports `changed`
 * when a section wrote something, so the board reloads. Otherwise renaming a
 * column and pressing Cancel would leave the old name on the board until a
 * refresh.
 *
 * @see StorageRepository
 * @see MainScreenBackingViewModel
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.client.userMessage
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.VocabularyEntry
import se.soderbjorn.lunicle.clientserver.VocabularyKind

/**
 * One row of one vocabulary, as the dialog renders it.
 *
 * Every decision is already made here, including the two the view would
 * otherwise be tempted to make itself: whether the delete button is live, and
 * what the confirmation will say. Both depend on rules the view has no business
 * knowing — that a status is refused while issues are in it, that a label is not
 * because its links cascade — and a view that worked them out from `usageCount`
 * would be a second, silent implementation of the server's rules.
 *
 * @property usageNote what to show beside the name, or null for a row nothing
 *   uses. "Used by 3 issues" — not a bare number, which reads as an id.
 * @property isDeletable whether to offer the delete button at all. An affordance;
 *   the server refuses regardless. See the server's VocabularyRepository.
 * @property deleteBlockedReason why it is not offered, for the view to hang off
 *   the disabled button. A disabled control with no explanation is the most
 *   annoying thing a dialog can do — the same reasoning as [State.validationMessage].
 * @property canMoveUp whether this row can go earlier. False for the first row,
 *   and for every row of an unordered kind.
 * @property showsClosingFlag whether to render the "needs a resolution"
 *   checkbox. Statuses only: the flag exists on no other table, and a checkbox
 *   beside a priority would be a control that writes nothing.
 */
data class VocabularyRowState(
    val id: Long,
    val name: String,
    val requiresResolution: Boolean,
    val usageNote: String?,
    val isDeletable: Boolean,
    val deleteBlockedReason: String?,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val showsClosingFlag: Boolean,
)

/**
 * One vocabulary, as a section of the dialog.
 *
 * @property hint the sentence under the heading. Every section has one, because
 *   these five lists look identical and are not: deleting a label unlabels
 *   issues, deleting a status is refused while any issue is in it, and the order
 *   of the priorities decides which one a new issue gets. None of that is
 *   guessable from a list of names.
 * @property isAddEnabled whether the add button is live — false for an empty
 *   field, which is the whole of the local check. Uniqueness is deliberately not
 *   checked here: the server owns it, its 409 names the row that already has the
 *   name, and duplicating the fold on this side buys a marginally faster refusal
 *   in exchange for a second place the rule lives.
 */
data class VocabularySection(
    val kind: VocabularyKind,
    val title: String,
    val hint: String,
    val isOrdered: Boolean,
    val draftName: String,
    val isAddEnabled: Boolean,
    val rows: List<VocabularyRowState>,
)

/**
 * One role checkbox against one user.
 *
 * @property isEnabled false for the instance admin's row. Ticking it would write
 *   a row that grants nothing — `AccessControl` says yes to an admin before it
 *   ever looks at a role — so the box would appear to do something and would not.
 *   The server allows the write (see projectSettingsRoutes); this declines to ask
 *   for it, which is what an affordance is.
 */
data class RoleToggle(
    val key: String,
    val description: String,
    val isOn: Boolean,
    val isEnabled: Boolean,
)

/**
 * One account, and what it may do here.
 *
 * @property note the sentence that replaces the checkboxes' meaning for this row
 *   — "Admin — can do everything in every project" — or null for an ordinary
 *   user. It is not decoration: a row of unticked boxes beside someone who can
 *   already do everything is a lie about what is in force.
 */
data class MemberRowState(
    val userId: Long,
    val name: String,
    val note: String?,
    val roles: List<RoleToggle>,
)

/**
 * A vocabulary row the admin has asked to delete, held while the confirmation is
 * up.
 *
 * The whole row rather than an id: the confirmation names what is about to
 * happen, and it needs the name and the count to say it. Looking them back up
 * from the id would work until a reload landed between the click and the
 * confirm, at which point the dialog would confirm one thing and delete another.
 */
data class PendingVocabularyDelete(
    val kind: VocabularyKind,
    val id: Long,
    val title: String,
    val message: String,
)

/**
 * Owns the project dialog.
 *
 * @param existing the project being edited, or null when creating.
 * @param otherProjects every project this user can see, so the name check can
 *   answer without a round-trip. The one being edited is excluded by id inside
 *   [nameClash] — renaming a project to what it is already called is not a
 *   clash.
 * @param onFinished called when the dialog is done: `changed` is true if
 *   anything was written, so MainScreen knows whether to reload, and `saved` is
 *   the project OK wrote — null for Cancel and for Delete, which leave no
 *   project to look at. The dialog reports its own outcome rather than
 *   MainScreen guessing. `saved` is how a *new* project reaches the board: it is
 *   not in MainScreen's list until the reload this call triggers, so the id has
 *   to be handed over rather than looked up.
 */
class EditProjectBackingViewModel(
    private val existing: ProjectSummary?,
    private val otherProjects: List<ProjectSummary>,
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onFinished: (changed: Boolean, saved: ProjectSummary?) -> Unit,
) {
    private val _stateFlow = MutableStateFlow(
        State(
            name = existing?.name.orEmpty(),
            namePrefix = existing?.namePrefix.orEmpty(),
            isPublic = existing?.isPublic ?: false,
            isNew = existing == null,
        ),
    )

    /** The current dialog state, observed by the view. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    init {
        // Nothing to configure until there is a project to configure, so a new
        // project's dialog is the plain form and asks for nothing. It also could
        // not ask: the settings route is keyed on a project id, and there is no id
        // until OK has been pressed.
        if (existing != null) loadSettings()
    }

    /**
     * Immutable snapshot of the dialog.
     *
     * @property isConfirmingDelete whether the confirmation dialog is up.
     *   Deleting a project takes every issue in it with it, so it asks first.
     * @property settings the project's vocabularies and grants, or null while they
     *   are loading — and also for a caller the server refused, which is the
     *   important half. The cogwheel is admin-only, so this should not happen; if
     *   it does, the dialog quietly renders the plain form rather than an empty
     *   settings section, because a section that cannot be filled is worse than no
     *   section. See [loadSettings].
     * @property vocabularyDrafts what has been typed into each section's add
     *   field, keyed by kind. One map rather than five fields, because five
     *   sections are rendered from one loop and five fields would have to be
     *   selected from by kind anyway.
     * @property hasWrittenSettings whether any section has written something. The
     *   reason it exists: the sections write immediately, so Cancel is not "throw
     *   the changes away" for them — the changes are already on the server. This
     *   is what makes Cancel still tell MainScreen to reload the board. See this
     *   file's preamble.
     * @property settingsErrorMessage a refusal from one of the sections, shown as
     *   a modal over this dialog rather than as text inside it. Separate from
     *   [errorMessage], which belongs to the *form* and sits under the fields it
     *   is about: a "3 issues are in that status" printed under the public
     *   checkbox would be nowhere near the button that caused it, and would sit
     *   there afterwards describing something that already happened.
     */
    data class State(
        val name: String = "",
        val namePrefix: String = "",
        val isPublic: Boolean = false,
        val isNew: Boolean = true,
        val isBusy: Boolean = false,
        val isConfirmingDelete: Boolean = false,
        val errorMessage: String? = null,
        val settings: ProjectSettingsState? = null,
        val vocabularyDrafts: Map<VocabularyKind, String> = emptyMap(),
        val pendingVocabularyDelete: PendingVocabularyDelete? = null,
        val settingsErrorMessage: String? = null,
        val hasWrittenSettings: Boolean = false,
    ) {
        val title: String get() = if (isNew) "New project" else "Project settings"

        /** Only an existing project can be deleted; there is nothing to delete yet when creating. */
        val canDelete: Boolean get() = !isNew

        /**
         * Why OK is disabled, or null when it is enabled.
         *
         * A reason rather than a bare boolean, because a disabled button with no
         * explanation is the most annoying thing a dialog can do. The view
         * renders this next to the field.
         */
        val validationMessage: String? get() = when {
            name.isBlank() -> "A project needs a name."
            namePrefix.isBlank() -> "A project needs a ticket prefix, like LMX."
            !namePrefix.trim().all { it.isLetterOrDigit() } ->
                "A prefix can only contain letters and digits — it becomes the LMX in LMX-123."
            else -> null
        }

        val isOkEnabled: Boolean get() = !isBusy && validationMessage == null

        /** What the prefix will actually be stored as; the server upper-cases it. */
        val prefixPreview: String get() = namePrefix.trim().uppercase()

        /**
         * "LMX-123" under the prefix field, so the point of the field is visible
         * — and null while the field is empty, which is why this is nullable.
         *
         * It used to fall back to "LMX", making an empty form claim that issues
         * "will be numbered LMX-123". They will not: with no prefix there is no
         * project, the server rejects it outright (see ProjectRepository). The
         * fallback was a stand-in for a value the user had not chosen yet, and
         * it read as one they had — the same trap as the placeholders this
         * dialog used to carry. A preview of nothing is nothing.
         */
        val prefixExample: String? get() = prefixPreview.ifBlank { null }?.let { "$it-123" }

        val confirmDeleteMessage: String
            get() = "Delete \"$name\" and every issue in it? This cannot be undone."

        // ── The settings half ────────────────────────────────────────────────

        /**
         * Whether to render the vocabulary and privilege sections at all.
         *
         * Null settings covers three states — creating, still loading, and
         * refused — and all three want the same answer here, which is why this is
         * one nullable rather than a flag per state.
         */
        val hasSettings: Boolean get() = settings != null

        /**
         * The five vocabularies, in the order the dialog stacks them.
         *
         * Statuses first because they are the board: they are what an admin came
         * here to change, and the one whose order is visible on screen five
         * seconds later. Labels and components last because they are a bag of
         * words that sorts itself.
         */
        val sections: List<VocabularySection> get() = settings?.let { loaded ->
            listOf(
                section(
                    loaded,
                    VocabularyKind.STATUS,
                    title = "Board columns",
                    hint = "Left to right, as the board shows them. A new issue lands in the " +
                        "first one. Tick \"needs a resolution\" on a column that closes an " +
                        "issue — that is what makes the board ask why.",
                ),
                section(
                    loaded,
                    VocabularyKind.PRIORITY,
                    title = "Priorities",
                    hint = "Most urgent first. A new issue gets the middle one, so adding or " +
                        "removing a priority changes what \"default\" means.",
                ),
                section(
                    loaded,
                    VocabularyKind.RESOLUTION,
                    title = "Resolutions",
                    hint = "Why an issue was closed. Offered when an issue moves into a column " +
                        "that needs a resolution.",
                ),
                section(
                    loaded,
                    VocabularyKind.LABEL,
                    title = "Labels",
                    hint = "Listed alphabetically — labels have no order. Deleting one removes " +
                        "it from the issues that wear it; the issues themselves are untouched.",
                ),
                section(
                    loaded,
                    VocabularyKind.COMPONENT,
                    title = "Components",
                    hint = "Which part of the thing an issue is about. Deleting one removes it " +
                        "from its issues, like a label.",
                ),
            )
        }.orEmpty()

        /** Every account, and what it holds here. */
        val members: List<MemberRowState> get() = settings?.let { loaded ->
            loaded.members.map { member ->
                MemberRowState(
                    userId = member.userId,
                    name = if (member.isSelf) "${member.name} (you)" else member.name,
                    note = when {
                        member.isAdmin -> "Admin — can already do everything, everywhere. " +
                            "These are what they would keep if that changed."
                        else -> null
                    },
                    roles = loaded.roles.map { role ->
                        RoleToggle(
                            key = role.key,
                            description = role.description,
                            isOn = role.key in member.roleKeys,
                            // Not the admin's own row, and not while a write is in
                            // flight: two clicks on the same box before the first
                            // answer arrives would send the second from a state
                            // that is already stale.
                            isEnabled = !member.isAdmin && !isBusy,
                        )
                    },
                )
            }
        }.orEmpty()

        /** The heading over the privileges table, and what it is for. */
        val membersHint: String get() =
            "Who may do what in this project. Everyone with an account is listed — " +
                "tick a box to grant, untick to revoke. It takes effect immediately."

        private fun section(
            loaded: ProjectSettingsState,
            kind: VocabularyKind,
            title: String,
            hint: String,
        ): VocabularySection {
            val entries = loaded.entriesFor(kind)
            val draft = vocabularyDrafts[kind].orEmpty()
            return VocabularySection(
                kind = kind,
                title = title,
                hint = hint,
                isOrdered = kind.isOrdered,
                draftName = draft,
                isAddEnabled = draft.isNotBlank() && !isBusy,
                rows = entries.mapIndexed { index, entry ->
                    entry.toRow(kind, index, entries)
                },
            )
        }

        /**
         * One row's affordances.
         *
         * The three rules mirrored from the server, and mirrored *deliberately* —
         * the point is not to enforce them here (nothing in a browser enforces
         * anything) but to not offer a button that is going to be refused:
         *
         *  - The last status or priority cannot go, because a project without one
         *    cannot take an issue and cannot be repaired from this dialog.
         *  - A status, priority or resolution in use cannot go: the database
         *    refuses to orphan the issues pointing at it.
         *  - A label or a component in use can go, and takes its links with it.
         *    That is the confirm's sentence, not a reason to disable anything.
         */
        private fun VocabularyEntry.toRow(
            kind: VocabularyKind,
            index: Int,
            siblings: List<VocabularyEntry>,
        ): VocabularyRowState {
            val isLastOfAKindThatMatters = siblings.size <= 1 && kind.isLoadBearing
            val isBlockedByUse = kind.restrictsOnUse && usageCount > 0
            return VocabularyRowState(
                id = id,
                name = name,
                requiresResolution = requiresResolution,
                usageNote = usageCount.takeIf { it > 0 }?.let {
                    "Used by $it ${if (it == 1) "issue" else "issues"}"
                },
                isDeletable = !isLastOfAKindThatMatters && !isBlockedByUse && !isBusy,
                deleteBlockedReason = when {
                    isLastOfAKindThatMatters ->
                        "A project needs at least one ${kind.noun}. Add another first."
                    // The verb agrees with the count, like the server's own
                    // refusal does — this is the same sentence arriving a
                    // round-trip earlier, and "1 issue are in this status" is the
                    // tell that nobody read it.
                    isBlockedByUse -> {
                        val subject = if (usageCount == 1) "1 issue" else "$usageCount issues"
                        val verb = when {
                            kind == VocabularyKind.STATUS && usageCount == 1 -> "is in"
                            kind == VocabularyKind.STATUS -> "are in"
                            usageCount == 1 -> "still has"
                            else -> "still have"
                        }
                        val move = if (usageCount == 1) "Move it first." else "Move them first."
                        "$subject $verb this ${kind.noun}. $move"
                    }
                    else -> null
                },
                canMoveUp = kind.isOrdered && index > 0 && !isBusy,
                canMoveDown = kind.isOrdered && index < siblings.size - 1 && !isBusy,
                showsClosingFlag = kind == VocabularyKind.STATUS,
            )
        }
    }

    fun onNameChanged(value: String) {
        _stateFlow.value = _stateFlow.value.copy(name = value, errorMessage = null)
    }

    fun onPrefixChanged(value: String) {
        _stateFlow.value = _stateFlow.value.copy(namePrefix = value, errorMessage = null)
    }

    fun onPublicChanged(value: Boolean) {
        _stateFlow.value = _stateFlow.value.copy(isPublic = value)
    }

    /**
     * OK.
     *
     * The local name check runs first so the common case — a typo the dialog can
     * already see — never costs a round-trip. A clash the dialog *cannot* see
     * still comes back as a 409, and is shown with the server's own words.
     */
    fun onOkTapped() {
        val current = _stateFlow.value
        if (!current.isOkEnabled) return

        val clash = nameClash(current.name, current.namePrefix)
        if (clash != null) {
            _stateFlow.value = current.copy(errorMessage = clash)
            return
        }

        _stateFlow.value = current.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching {
                if (existing == null) {
                    storage.createProject(current.name, current.namePrefix, current.isPublic)
                } else {
                    storage.updateProject(existing.id, current.name, current.namePrefix, current.isPublic)
                }
            }
            result.fold(
                onSuccess = {
                    println("EditProject: saved ${it.name}")
                    onFinished(true, it)
                },
                onFailure = { t ->
                    println("EditProject: save failed: ${t.message}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not save that project."),
                    )
                },
            )
        }
    }

    /**
     * Cancel.
     *
     * Reports `changed` when a section has written something, even though Cancel
     * threw the *form* away. Not a contradiction — the two halves of this dialog
     * are different things, and the sections' writes already happened. Reporting
     * false here would leave a renamed column showing its old name on the board
     * until somebody refreshed. See this file's preamble.
     */
    fun onCancelTapped() {
        if (_stateFlow.value.isBusy) return
        onFinished(_stateFlow.value.hasWrittenSettings, null)
    }

    fun onDeleteTapped() {
        _stateFlow.value = _stateFlow.value.copy(isConfirmingDelete = true)
    }

    fun onDeleteCancelled() {
        _stateFlow.value = _stateFlow.value.copy(isConfirmingDelete = false)
    }

    fun onDeleteConfirmed() {
        val project = existing ?: return
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, isConfirmingDelete = false, errorMessage = null)
        scope.launch {
            runCatching { storage.deleteProject(project.id) }.fold(
                onSuccess = {
                    println("EditProject: deleted ${project.name}")
                    // No `saved`: the project this dialog was about no longer
                    // exists, so there is nothing for the board to select.
                    onFinished(true, null)
                },
                onFailure = { t ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not delete that project."),
                    )
                },
            )
        }
    }

    // ── The vocabularies ─────────────────────────────────────────────────────

    fun onVocabularyDraftChanged(kind: VocabularyKind, value: String) {
        _stateFlow.value = _stateFlow.value.copy(
            vocabularyDrafts = _stateFlow.value.vocabularyDrafts + (kind to value),
        )
    }

    /**
     * Add.
     *
     * The draft is cleared only on success. A refusal — the name is taken —
     * leaves what was typed in the field, because the fix is to edit it, and a
     * field that emptied itself on a 409 would make the admin retype the whole
     * name to change one letter of it.
     */
    fun onAddVocabularyTapped(kind: VocabularyKind) {
        val project = existing ?: return
        val name = _stateFlow.value.vocabularyDrafts[kind].orEmpty()
        if (name.isBlank()) return
        write("Could not add that ${kind.noun}.") {
            val settings = storage.addVocabulary(project.id, kind, name)
            _stateFlow.value = _stateFlow.value.copy(
                vocabularyDrafts = _stateFlow.value.vocabularyDrafts - kind,
            )
            settings
        }
    }

    /**
     * Rename a row, or set a status's closing flag.
     *
     * One intent for both, because the server takes them in one write — a status's
     * name and its flag are one row and one decision. The view sends whichever it
     * has just changed along with the other's current value; it does not get to
     * send half a row.
     *
     * A rename that changes nothing is dropped here rather than sent and ignored:
     * the field commits on blur, so tabbing through the sections would otherwise
     * fire a PUT per row, each one rewriting a name to itself.
     */
    fun onVocabularyEdited(kind: VocabularyKind, id: Long, name: String, requiresResolution: Boolean) {
        val project = existing ?: return
        val current = _stateFlow.value.settings?.entriesFor(kind)?.firstOrNull { it.id == id } ?: return
        if (current.name == name && current.requiresResolution == requiresResolution) return
        if (name.isBlank()) {
            // Refused here rather than sent: the server would refuse it too, with
            // the same sentence, but this way the field still holds what was typed
            // and an alert has not stolen focus from it.
            _stateFlow.value = _stateFlow.value.copy(
                settingsErrorMessage = "A ${kind.noun} needs a name.",
            )
            return
        }
        write("Could not save that ${kind.noun}.") {
            storage.editVocabulary(project.id, kind, id, name, requiresResolution)
        }
    }

    /**
     * Move a row one place earlier or later.
     *
     * Arrows rather than drag-and-drop: this is a settings dialog opened rarely,
     * the lists are five rows long, and a drag needs a drop target, a ghost and a
     * touch story. The board's cards earn that; a list of five statuses does not.
     *
     * The whole new order is sent, not "row 3 moved up" — see [VocabularyOrder].
     * A move that would fall off either end is dropped rather than clamped: the
     * view does not offer the button, so getting here means the state moved under
     * the click, and re-sending the order unchanged would be a write that does
     * nothing.
     */
    fun onMoveVocabulary(kind: VocabularyKind, id: Long, offset: Int) {
        val project = existing ?: return
        val ids = _stateFlow.value.settings?.entriesFor(kind)?.map { it.id } ?: return
        val from = ids.indexOf(id).takeIf { it >= 0 } ?: return
        val to = from + offset
        if (to !in ids.indices) return
        val reordered = ids.toMutableList().apply {
            removeAt(from)
            add(to, id)
        }
        write("Could not reorder those ${kind.plural}.") {
            storage.reorderVocabulary(project.id, kind, reordered)
        }
    }

    /**
     * Ask before deleting, and name what it costs.
     *
     * The message is built here, from the counts the server sent, because it is
     * the whole point of the confirmation: "Delete Bug?" tells an admin nothing
     * they did not know when they clicked. "Delete Bug? 12 issues will lose that
     * label" is the sentence that stops the click that should not have happened.
     */
    fun onDeleteVocabularyTapped(kind: VocabularyKind, id: Long) {
        val entry = _stateFlow.value.settings?.entriesFor(kind)?.firstOrNull { it.id == id } ?: return
        val issues = "${entry.usageCount} ${if (entry.usageCount == 1) "issue" else "issues"}"
        val consequence = when {
            entry.usageCount == 0 -> "Nothing uses it."
            // The only kinds that reach a confirmation while in use: the others
            // are refused before the button lights up. Deleting one of these
            // unlabels the issues; it does not touch the issues themselves. See
            // IssueLabels.sq.
            else -> "$issues will lose it. The issues themselves are not affected."
        }
        _stateFlow.value = _stateFlow.value.copy(
            pendingVocabularyDelete = PendingVocabularyDelete(
                kind = kind,
                id = id,
                title = "Delete ${kind.noun}",
                message = "Delete \"${entry.name}\"? $consequence This cannot be undone.",
            ),
        )
    }

    fun onVocabularyDeleteCancelled() {
        _stateFlow.value = _stateFlow.value.copy(pendingVocabularyDelete = null)
    }

    fun onVocabularyDeleteConfirmed() {
        val project = existing ?: return
        val pending = _stateFlow.value.pendingVocabularyDelete ?: return
        _stateFlow.value = _stateFlow.value.copy(pendingVocabularyDelete = null)
        write("Could not delete that ${pending.kind.noun}.") {
            storage.deleteVocabulary(project.id, pending.kind, pending.id)
        }
    }

    // ── The privileges ───────────────────────────────────────────────────────

    /**
     * Grant or revoke one role for one user.
     *
     * No confirmation, deliberately, and it is the one destructive-looking thing
     * here that does not get one: revoking is instantly reversible by ticking the
     * box again, and nothing is lost when you do. A confirmation on an action that
     * undoes itself teaches people to click through confirmations.
     */
    fun onRoleToggled(userId: Long, roleKey: String, isGranted: Boolean) {
        val project = existing ?: return
        write("Could not change that privilege.") {
            storage.setProjectRole(project.id, userId, roleKey, isGranted)
        }
    }

    // ── Loading and writing ──────────────────────────────────────────────────

    /**
     * Fetch the vocabularies and the grants.
     *
     * A failure is swallowed into "no settings section" rather than shown. The
     * only way to open this dialog is the cogwheel, which is admin-only, so a 403
     * here means the client's affordances and the server disagree — and the
     * honest rendering of "you may not configure this" is a dialog that does not
     * offer to. Shouting about it would put an alert over a form the user can
     * still legitimately use.
     */
    private fun loadSettings() {
        val project = existing ?: return
        scope.launch {
            runCatching { storage.projectSettings(project.id) }.fold(
                onSuccess = { _stateFlow.value = _stateFlow.value.copy(settings = it) },
                onFailure = { println("EditProject: settings unavailable: ${it.message}") },
            )
        }
    }

    /**
     * Run one settings write, and take the whole new state from its answer.
     *
     * Every one of these returns the entire [ProjectSettingsState], and this is
     * where that pays: the dialog never patches its own copy, so it cannot be
     * right about the row it touched and wrong about the rest — deleting a status
     * changes whether the last remaining one may be deleted, and adding a priority
     * moves the middle of the scale. See LunicleApi's project settings section.
     *
     * [State.hasWrittenSettings] is set on success and never unset: it means "the
     * board on screen is stale", which stays true no matter what happens
     * afterwards.
     *
     * @param fallback what to say when the failure is not the server's — a dropped
     *   connection has no sentence worth showing. A refusal does, and it wins; see
     *   `userMessage`.
     */
    private fun write(fallback: String, block: suspend () -> ProjectSettingsState) {
        if (_stateFlow.value.isBusy) return
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, settingsErrorMessage = null)
        scope.launch {
            runCatching { block() }.fold(
                onSuccess = { settings ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        settings = settings,
                        hasWrittenSettings = true,
                    )
                },
                onFailure = { t ->
                    println("EditProject: settings write failed: ${t.message}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        settingsErrorMessage = t.userMessage(fallback),
                    )
                },
            )
        }
    }

    /** The settings alert has been read. */
    fun onSettingsErrorDismissed() {
        _stateFlow.value = _stateFlow.value.copy(settingsErrorMessage = null)
    }

    /**
     * Is this name or prefix already someone else's?
     *
     * `lowercase()` rather than a case-insensitive compare, and it is the same
     * Unicode-aware fold the server's `ProjectRepository.validate` uses — so the
     * dialog and the server agree about `Ärenden` and `ärenden` being one name.
     * Kotlin/JS and the JVM both implement it over the full Unicode range, which
     * SQLite's `COLLATE NOCASE` does not: that folds ASCII A–Z only.
     *
     * @return the sentence to show, or null if there is no clash.
     */
    private fun nameClash(name: String, prefix: String): String? {
        val cleanName = name.trim().lowercase()
        val cleanPrefix = prefix.trim().lowercase()
        val others = otherProjects.filter { it.id != existing?.id }
        others.firstOrNull { it.name.lowercase() == cleanName }?.let {
            return "There is already a project called \"${it.name}\"."
        }
        others.firstOrNull { it.namePrefix.lowercase() == cleanPrefix }?.let {
            return "\"${it.namePrefix}\" is already used by \"${it.name}\"."
        }
        return null
    }
}
