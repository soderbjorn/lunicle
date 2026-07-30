/**
 * The three sprint dialogs, which are three because sprints have three moments.
 *
 * All of them are stateless views over a [Modal], like [ResolutionDialog] and
 * unlike [ProjectDialog]: none fetches anything, none has a backing view model,
 * and the only state any of them holds is the ticks in [PlanSprintDialog] — which
 * live here rather than in [MainScreenBackingViewModel] because a half-filled
 * checkbox list is not a fact about the board. Cancelling has to leave nothing
 * behind, and the cheapest way to guarantee that is for nothing to have left the
 * dialog.
 *
 * @see MainScreenBackingViewModel.ActiveDialog
 * @see Modal
 */
package se.soderbjorn.lunicle

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import se.soderbjorn.lunicle.client.viewmodel.ActiveDialog
import se.soderbjorn.lunicle.client.viewmodel.PendingSprintCompletion
import se.soderbjorn.lunicle.clientserver.IssueSummary

/**
 * "What is the new sprint called?"
 *
 * One field. A sprint needs a name and nothing else — no dates, no capacity, no
 * "activate this now" tick. The last of those is the one worth naming: a new
 * sprint is deliberately NOT activated, so that writing next quarter's three
 * sprints in advance does not yank the board out from under whoever is working
 * in this one. See SprintRepository.
 */
class NewSprintDialog(
    private val projectId: Long,
    private val onNamed: (projectId: Long, name: String) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val modal = Modal("New sprint", onDismiss = { onCancel() })
    private var name: String = ""
    private lateinit var saveButton: HTMLButtonElement

    fun mount(host: HTMLElement) {
        val field = textField("e.g. Sprint 14") {
            name = it
            saveButton.disabled = it.isBlank()
        }
        modal.body.children(
            element("label", "field-label", "Name"),
            field,
            element(
                "p",
                "field-hint",
                "It is created empty and inactive. Make it active when you are ready to work " +
                    "in it — creating one never changes what the board is showing.",
            ),
        )
        saveButton = button("Create", "btn btn-primary") { onNamed(projectId, name) } as HTMLButtonElement
        saveButton.disabled = true
        modal.footer.children(
            element("div", "modal-footer-spacer"),
            button("Cancel", "btn btn-quiet") { onCancel() },
            saveButton,
        )
        modal.mount(host)
        field.focus()
    }

    fun dismiss() = modal.dismiss()
}

/**
 * Sprint planning: tick what belongs in this sprint.
 *
 * The ticks are the whole set that will be sent, not a delta — unticking is how
 * an issue leaves. That is what makes the Save idempotent on a retry and what
 * stops two people planning at once from interleaving into a set neither chose;
 * see SprintMembership.
 *
 * What is offered is the backlog plus this sprint's own issues. An issue in
 * *another* sprint is absent deliberately: it is somebody else's plan, and
 * quietly taking it from a list of checkboxes is not a thing this should make
 * easy. Moving it here means going to that issue and saying so.
 */
class PlanSprintDialog(
    private val dialog: ActiveDialog.PlanSprint,
    private val onSave: (projectId: Long, sprintId: Long, issueIds: Set<Long>) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val modal = Modal("Plan ${dialog.sprintName}", onDismiss = { onCancel() }, isLarge = true)

    /**
     * The ticks, held here and nowhere else.
     *
     * Seeded from what is in the sprint right now, so opening the dialog and
     * pressing Save changes nothing — the operation is idempotent from the very
     * first frame, which is what makes it safe to open just to look.
     */
    private val selected: MutableSet<Long> = dialog.selected.toMutableSet()

    private lateinit var countLabel: HTMLElement

    fun mount(host: HTMLElement) {
        countLabel = element("p", "field-hint")
        modal.body.appendChild(countLabel)

        if (dialog.candidates.isEmpty()) {
            // Not an empty list with a Save button under it, which reads as a
            // broken dialog. This is a real and common state — everything is
            // already scheduled somewhere — and it deserves a sentence.
            modal.body.appendChild(
                element(
                    "p",
                    "modal-message",
                    "Nothing to plan: every issue on this board is already in a sprint. " +
                        "Issues in other sprints are not offered here — open one to move it.",
                ),
            )
        } else {
            val list = element("div", "sprint-plan-list")
            dialog.candidates.forEach { list.appendChild(row(it)) }
            modal.body.appendChild(list)
        }

        modal.footer.children(
            element("div", "modal-footer-spacer"),
            button("Cancel", "btn btn-quiet") { onCancel() },
            button("Save", "btn btn-primary") { onSave(dialog.projectId, dialog.sprintId, selected) },
        )
        renderCount()
        modal.mount(host)
    }

    private fun row(issue: IssueSummary): HTMLElement {
        val row = element("label", "sprint-plan-row")
        val box = element("input", "sprint-plan-check") as HTMLInputElement
        box.type = "checkbox"
        box.checked = issue.id in selected
        box.onchange = {
            if (box.checked) selected.add(issue.id) else selected.remove(issue.id)
            renderCount()
        }
        row.children(
            box,
            element("span", "sprint-plan-key", "${dialog.prefix}-${issue.number}"),
            element("span", "sprint-plan-title", issue.title),
        )
        return row
    }

    /**
     * "7 issues in this sprint."
     *
     * Live rather than only on save, because the number is the thing being
     * decided — a planning session is people arguing about whether it is seven or
     * nine, and a count that only appears afterwards is not in the conversation.
     */
    private fun renderCount() {
        val n = selected.size
        countLabel.setTextIfChanged(
            if (n == 1) "1 issue in this sprint." else "$n issues in this sprint.",
        )
    }

    fun dismiss() = modal.dismiss()
}

/**
 * "This sprint is over — where does the unfinished work go?"
 *
 * Asked rather than defaulted. Rolling into the next sprint and dropping back to
 * the backlog are different intentions, and the wrong one is tedious to undo:
 * the issues end up spread across two places with nothing recording which of them
 * moved.
 *
 * A button per destination, like [ResolutionDialog], for the same reason — a
 * dropdown plus an OK is three interactions to express one choice, and the
 * choices here are usually two.
 *
 * Raised from the project's **Sprints section** since LNL-196, not from the board's
 * scope picker; the prompt it renders is
 * [se.soderbjorn.lunicle.client.viewmodel.PendingSprintCompletion]. It takes the prompt
 * and one callback rather than an `ActiveDialog`, so the project id and the sprint id
 * stay with the view model that raised it — this view never needed either.
 */
class CompleteSprintDialog(
    private val prompt: PendingSprintCompletion,
    private val onComplete: (moveUnfinishedTo: Long?) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val modal = Modal("Complete ${prompt.sprintName}", onDismiss = { onCancel() })

    fun mount(host: HTMLElement) {
        val n = prompt.unfinishedCount
        modal.body.appendChild(
            element(
                "p",
                "modal-message",
                when {
                    // The finished work is not mentioned, on purpose: it stays in
                    // this sprint, which is the only thing it could do and the
                    // thing "completing a sprint" already means.
                    n == 0 -> "Everything in ${prompt.sprintName} is finished. " +
                        "Completing it now closes it out."
                    n == 1 -> "1 issue in ${prompt.sprintName} is not finished. Where should it go?"
                    else -> "$n issues in ${prompt.sprintName} are not finished. Where should they go?"
                },
            ),
        )

        val choices = element("div", "resolution-choices")
        // The open sprints first, then the backlog. Rolling forward is the common
        // answer when there is somewhere to roll to, so it gets the primary style
        // and the first position — and the backlog stays available rather than
        // being the thing you have to notice.
        prompt.destinations.forEachIndexed { index, sprint ->
            val style = if (index == 0) "btn btn-primary" else "btn"
            choices.appendChild(
                button("Move to ${sprint.name}", "$style resolution-choice") {
                    onComplete(sprint.sprintId)
                } as HTMLButtonElement,
            )
        }
        val backlogStyle = if (prompt.destinations.isEmpty()) "btn btn-primary" else "btn"
        choices.appendChild(
            button("Move to the backlog", "$backlogStyle resolution-choice") {
                onComplete(null)
            } as HTMLButtonElement,
        )
        modal.body.appendChild(choices)

        modal.footer.children(
            element("div", "modal-footer-spacer"),
            button("Cancel", "btn btn-quiet") { onCancel() },
        )
        modal.mount(host)
    }

    fun dismiss() = modal.dismiss()
}
