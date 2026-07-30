/**
 * The Projects tab: one rail, two levels (LNL-194).
 *
 * ── The rail ────────────────────────────────────────────────────────────────
 *
 * Every project the caller holds something in — and nothing else — with what they
 * hold written under each name. The selected project's sections indent beneath it, so
 * the whole surface is one tab strip and one rail: selecting another project keeps you
 * on the same section, which makes comparing Access across two boards two clicks
 * rather than a round trip through a dialog each.
 *
 * Sections appear **per project, not per person**. Maintainer on one board and Viewer
 * on another shows different sections under each name, and the list is the server's
 * answer rather than this file's — see [ProjectSections], which says at length why.
 *
 * ── "New project…" at the bottom ────────────────────────────────────────────
 *
 * The **only** way to create a project. It was a row in the top bar's "+" menu, which
 * put "make a board" next to "open a board" — two very different acts, one of them
 * two clicks from anywhere and neither of them near the place you go to configure the
 * boards you have. Here it is at the end of the list of your projects, which is where
 * "and another one" belongs.
 *
 * ── One view model per project, built on selection ──────────────────────────
 *
 * A project's settings are a request, so the rail does not fetch thirteen of them: it
 * builds an [EditProjectBackingViewModel] and a [ProjectSections] for the *selected*
 * project and disposes them when the selection moves. That is also what makes the
 * pane's memory of "which project" cheap — the route carries an id, and everything
 * else is rebuilt from it.
 *
 * @see SettingsPane for the tab this is one of
 * @see ProjectSections for what hangs under each project
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.client.viewmodel.EditProjectBackingViewModel
import se.soderbjorn.lunicle.clientserver.ProjectSectionKeys
import se.soderbjorn.lunicle.clientserver.ProjectSummary

/**
 * Renders the Projects tab.
 *
 * @param storage the API, handed to each project's view model.
 * @param projects read fresh on every render — the caller's project list, already
 *   filtered by the server and already carrying what the caller holds in each. A
 *   lambda rather than a value because the list arrives a tick after the pane is built
 *   and can change under it; see [SettingsPane.refreshAvailability].
 * @param dialogHost where confirmations and the add-a-person dialog layer.
 * @param canCreateProject whether this caller may make one at all — the instance's
 *   per-tier setting, read fresh off the board state. False hides the row rather than
 *   greying it: unlike a rung out of reach on a board you are looking at, "your account
 *   may not make boards here" is not a fact about anything on this screen, and a dead
 *   row at the end of your project list would read as a broken button.
 * @param onNewProject "New project…" was pressed. Raised by the app rather than here,
 *   because creating one is a modal over the whole window and its outcome — a board
 *   opening — is the workspace's business.
 * @param onRouteChanged the reader moved within the tab: another project, or another
 *   section. The address bar follows.
 * @param onProjectWritten a project's settings changed in a way the rest of the app
 *   has to see — a rename, a board-display switch, a deletion. Triggers the project
 *   list and the open boards to reload.
 */
class ProjectsTab(
    private val storage: StorageRepository,
    private val projects: () -> List<ProjectSummary>,
    private val dialogHost: HTMLElement,
    private val canCreateProject: () -> Boolean,
    private val onNewProject: () -> Unit,
    private val onRouteChanged: () -> Unit,
    private val onProjectWritten: () -> Unit,
) {
    private val root = element("div", "settings-tab-pane project-rail-split")
    private val rail = element("div", "project-rail")
    private val content = element("div", "project-rail-content")
    private val placeholder = element(
        "p",
        "admin-placeholder",
        "Choose a project on the left.",
    )

    /** Which project is selected, or null before the list has arrived. */
    private var selectedId: Long? = null

    /**
     * Which section the selected project is showing.
     *
     * Kept here and not only in [sections] so that it survives moving to another
     * project — which is the whole point of one rail rather than a tab strip per
     * project: "compare Access across two boards" must not put you back on General.
     */
    private var selectedSection: String = ProjectSectionKeys.GENERAL

    // ── The selected project's view, and its lifetime ──
    private var sections: ProjectSections? = null
    private var sectionsScope: CoroutineScope? = null
    private var sectionsHost: HTMLElement? = null
    private var mountedFor: Long? = null

    /** What the rail was last drawn from, so a redraw does not tear it down needlessly. */
    private var railSignature: String? = null

    val element: HTMLElement get() = root

    fun mount(): HTMLElement {
        content.children(placeholder)
        root.children(rail, content)
        return root
    }

    /**
     * Go to a project and a section.
     *
     * Silent about the address bar: the caller is its source here. Only a press on the
     * rail reports back through [onRouteChanged].
     */
    fun show(projectId: Long?, section: String?) {
        if (projectId != null) selectedId = projectId
        if (section != null) selectedSection = section
        render()
    }

    /** Where the tab is — what the address bar writes. */
    fun currentProjectId(): Long? = selectedId

    fun currentSection(): String = sections?.currentSection() ?: selectedSection

    /**
     * Redraw from the project list, and mount the selected project's sections.
     *
     * Called on every board-state tick, because the list is a flow this tab does not
     * collect: a project arriving, being renamed, or being deleted all have to reach the
     * rail, and the pane's own hook is the only tick it sees.
     */
    fun render() {
        val visible = projects()
        // The first project, when nothing is selected or the selection has gone — deleted,
        // or access withdrawn while the pane was open. Never "nothing selected with a list
        // on screen", which reads as a pane that stopped responding.
        if (visible.none { it.id == selectedId }) selectedId = visible.firstOrNull()?.id

        renderRail(visible)
        mountSections()
        // Pushed on every render, not only when the view is built — which is the bug this
        // line fixes. `refreshAvailability` fires on the first board tick, and on a reload
        // whose stored workspace already held a settings pane that tick arrives BEFORE the
        // deep link's route does: the sections were mounted at General, `show` then set
        // this field to "access", and `mountSections` returned early because the project
        // had not changed — so the already-mounted view was never told. The rail's field is
        // the one memory of which section is showing; the view is told, every time.
        //
        // Then adopted back, because the view may settle somewhere else: a stale bookmark
        // naming a section this caller's rung does not reach lands on the first one they
        // have, and the rail (and the address bar behind it) should say where they are.
        sections?.let { view ->
            view.showSection(selectedSection)
            selectedSection = view.currentSection()
        }
        placeholder.visible(selectedId == null)
    }

    /**
     * Re-fetch the open project's settings, because instance configuration changed.
     *
     * A no-op when no project's sections are mounted, which is the right answer: the next
     * mount fetches anyway. Only the *open* project needs telling — the rail's other rows
     * hold no loaded state to go stale.
     */
    fun reloadOpenProject() {
        sections?.reloadSettings()
    }

    /** Take everything down. Called when the pane closes. */
    fun dispose() {
        disposeSections()
    }

    // ── The rail ─────────────────────────────────────────────────────────────

    private fun renderRail(visible: List<ProjectSummary>) {
        val offered = sections?.let { s ->
            if (mountedFor == selectedId) currentSectionKeys() else emptyList()
        }.orEmpty()
        val mayCreate = canCreateProject()
        val signature = "$selectedId|$selectedSection|$mayCreate|" +
            visible.joinToString("|") { "${it.id}:${it.name}:${it.roleLabel}" } +
            "|" + offered.joinToString(",")
        if (signature == railSignature) return
        railSignature = signature

        rail.clear()
        visible.forEach { project ->
            val row = button("", "project-rail-row") {
                if (project.id != selectedId) {
                    selectedId = project.id
                    // The section is deliberately NOT reset. See selectedSection.
                    render()
                    onRouteChanged()
                }
            }
            row.classList.toggle("project-rail-selected", project.id == selectedId)
            row.children(
                // element(text = …) sets textContent, never innerHTML — a project name is
                // user-chosen, so this puts "<img onerror=…>" on the page as characters.
                element("span", "project-rail-name", project.name),
                // What they hold here, under the name. The whole reason the project list
                // carries a rung: a rail that had to fetch each project's settings to say
                // "Maintainer" would make opening this tab thirteen requests.
                element("span", "project-rail-role", project.roleLabel),
            )
            rail.appendChild(row)

            if (project.id == selectedId) {
                offered.forEach { (key, label) ->
                    val sectionRow = button(label, "project-rail-section") {
                        selectedSection = key
                        sections?.showSection(key)
                        renderRail(projects())
                        onRouteChanged()
                    }
                    sectionRow.classList.toggle(
                        "project-rail-section-selected",
                        key == sections?.currentSection(),
                    )
                    rail.appendChild(sectionRow)
                }
            }
        }

        // The only way to make one, for whoever this deployment lets make one. See this
        // file's preamble and the canCreateProject parameter.
        if (mayCreate) {
            rail.appendChild(button("New project…", "project-rail-new") { onNewProject() })
        }
    }

    /** The selected project's sections, as (key, label) pairs in the server's order. */
    private fun currentSectionKeys(): List<Pair<String, String>> =
        viewModel?.stateFlow?.value?.railSections?.map { it.key to it.label }.orEmpty()

    // ── The selected project's sections ──────────────────────────────────────

    private var viewModel: EditProjectBackingViewModel? = null

    /**
     * Build the selected project's view, or leave the one that is already up.
     *
     * Keyed on [mountedFor] rather than rebuilt on every render: the sections hold text
     * fields somebody may be typing in, and a rebuild on every board tick would destroy
     * a half-typed status name — the same reasoning `setValueIfChanged` runs on, one
     * level up.
     */
    private fun mountSections() {
        val id = selectedId
        if (id == mountedFor) {
            // Same project: the rail's second level still has to follow the section list,
            // which arrives with the settings a moment after this.
            return
        }
        disposeSections()
        if (id == null) return
        val project = projects().firstOrNull { it.id == id } ?: return

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val model = EditProjectBackingViewModel(
            existing = project,
            otherProjects = projects().filter { it.id != id },
            // Both seeded false and replaced by the server's own answer the moment the
            // settings land — see EditProjectBackingViewModel.loadSettings. The safe
            // direction: fields are dead until something says they are not.
            canConfigure = false,
            canConfigureIdentity = false,
            storage = storage,
            scope = scope,
            onFinished = { changed, _ ->
                // Not a close: there is no dialog to dismiss here. A save that changed
                // something has to reach the board and the project list — a rename shows in
                // the rail and in every tab title — and a delete has to take the project out
                // of the list, which the same reload does.
                if (changed) onProjectWritten()
            },
        )
        // Classed, and it is load-bearing: `.project-rail-content` hides its overflow so
        // each section pane scrolls inside itself, and a plain block wrapper between the
        // two breaks the flex chain — the pane's `flex: 1; min-height: 0` has nothing to
        // measure against, so nothing scrolls and the content is simply clipped. Found by
        // driving the app.
        val host = element("div", "project-sections-host")
        val view = ProjectSections(
            viewModel = model,
            scope = scope,
            dialogHost = dialogHost,
            // The server's section list arrived, so the rail's second level can be drawn.
            // Through the rail's own signature check, so this is cheap to fire on every
            // settings emission.
            onSectionsChanged = { renderRail(projects()) },
            onSettingsWritten = onProjectWritten,
        )
        content.appendChild(host)
        view.mount(host)
        view.showSection(selectedSection)

        viewModel = model
        sections = view
        sectionsScope = scope
        sectionsHost = host
        mountedFor = id
    }

    private fun disposeSections() {
        sections?.dismiss()
        sectionsScope?.cancel()
        sectionsHost?.remove()
        sections = null
        sectionsScope = null
        sectionsHost = null
        viewModel = null
        mountedFor = null
        // Forced, so the next renderRail redraws the second level rather than trusting a
        // signature computed against the view that has just gone.
        railSignature = null
    }
}
