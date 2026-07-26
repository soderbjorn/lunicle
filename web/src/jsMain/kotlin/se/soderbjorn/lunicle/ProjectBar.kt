/**
 * The project navigator that rides the shell's top bar, to the left of the tabs.
 *
 * LNL-84 lifted the project switcher out of the board window's toolbar and made
 * it a top-level control: picking a project here is the app's primary navigation,
 * the way switching tabs is one level down. The project-settings gear and the
 * statistics button travelled up with it — both act on whatever project the
 * picker names, so they belong beside it rather than in the board below.
 *
 * Mounted into [se.soderbjorn.lunula.web.shell.AppShellSpec.topbarLeading] via a
 * stable [root]: the toolkit rebuilds the top bar on every shell refresh and
 * simply re-appends this same element, so the picker's menu listeners survive.
 * Its children are re-driven by [render] from the board's state flow, exactly as
 * the board window is — see main.kt's board collector.
 *
 * A dumb view like the rest: it builds its elements once and forwards intent to
 * [MainScreenBackingViewModel]. The picker is a [Dropdown] — a button opening a
 * themed menu rather than a `<select>`, whose open list only the OS may draw; see
 * [Dropdown] for that story.
 *
 * @see MainScreenBackingViewModel
 * @see BoardWindow for what stayed behind in the board toolbar (scope + filter).
 */
package se.soderbjorn.lunicle

import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.viewmodel.MainScreenBackingViewModel

/** What the picker reads before a board has loaded, and when the user has no projects. */
private const val NO_PROJECT = "No project"

/**
 * Renders the top-bar project navigator.
 *
 * @param viewModel the shared backing view model; the view's only collaborator.
 */
class ProjectBar(
    private val viewModel: MainScreenBackingViewModel,
) {
    /** The leading-cluster element the shell mounts. Built eagerly, driven by [render]. */
    val root: HTMLElement = element("div", "topbar-project")

    private val picker = Dropdown("picker") { viewModel.onProjectSelected(it) }
    private val settingsButton: HTMLElement
    private val statisticsButton: HTMLElement

    init {
        settingsButton = button("", "icon-btn") { viewModel.onProjectSettingsTapped() }
        settingsButton.appendChild(gearIcon())
        settingsButton.title = "Project settings"

        statisticsButton = button("", "icon-btn") { viewModel.onStatisticsTapped() }
        statisticsButton.appendChild(chartIcon())
        statisticsButton.title = "Statistics"

        // The gear then statistics keep the order they had in the board toolbar:
        // the gear changes the project, statistics only reads it, and the
        // destructive-adjacent control keeps its established place.
        root.children(picker.element, settingsButton, statisticsButton)
    }

    /** Apply a state snapshot. Called for every emission of the board's state flow. */
    fun render(state: MainScreenBackingViewModel.State) {
        picker.render(
            items = state.projects.map { DropdownItem(it.id, it.name) },
            selectedId = state.currentProject?.id,
            placeholder = NO_PROJECT,
        )
        // Offered to every signed-in user now, not just admins: the dialog opens
        // for everyone and shows a non-admin only the notification toggle. See
        // MainScreenBackingViewModel.canOpenProjectSettings.
        settingsButton.visible(state.canOpenProjectSettings, displayValue = "inline-flex")
        // Shown to signed-out readers too, unlike the gear: a public project's
        // statistics count issues that visitor is already reading.
        statisticsButton.visible(state.canOpenStatistics, displayValue = "inline-flex")
    }
}
