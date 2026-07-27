/**
 * The version picker (LNL-134): one control reused everywhere a release version is
 * chosen — the issue editor's planned- and fixed-version fields, and the resolution
 * dialog's inline fixed-version prompt.
 *
 * Built on the same `.dt-hover-menu` chrome as [Dropdown] rather than being one,
 * because it does two things a plain dropdown does not:
 *
 *  - **Add inline.** The bottom row is "Add new version…", which turns into a field
 *    in place. The moment you notice the version you want is missing is the moment
 *    you have the dropdown open, and making somebody close it, find project
 *    settings and come back is enough friction that it goes unversioned. Same
 *    reasoning as the board's inline "New sprint…". Offered only to a caller who may
 *    manage the vocabulary — the route behind it is admin-gated, so a non-admin sees
 *    only what exists.
 *  - **Delete per row.** Each row carries an ellipsis that opens a one-item menu —
 *    "Delete" — behind a confirmation, because deleting a version releases the issues
 *    that named it and there is no undo. Admin-only for the same reason as add.
 *
 * The menu exists only while open, [Dropdown]'s trick: built at open time and thrown
 * away on close, so [render] is free to run on every emission without rebuilding a
 * list underneath an open menu.
 *
 * @param allowNone whether the menu offers a "None" row that clears the choice. True
 *   for the editor's optional fields, false for the resolution dialog's required
 *   picker — there, "no version" is not an answer the project accepts.
 * @param canManage whether to offer Add and Delete. The affordance in front of the
 *   admin-gated vocabulary routes; a non-admin picks from what is there.
 * @param onSelect the chosen version, or null for "None".
 * @param onAdd a new version's name, typed into the inline field.
 * @param onDelete a version to delete, already confirmed.
 *
 * @see Dropdown
 * @see openContextMenu
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunicle.clientserver.VocabularyItem
import se.soderbjorn.lunula.web.shell.buildMenuTrigger
import se.soderbjorn.lunula.web.shell.setMenuTriggerLabel
import se.soderbjorn.lunula.web.shell.setMenuTriggerOpen

class VersionDropdown(
    isField: Boolean = false,
    className: String = "",
    private val allowNone: Boolean = true,
    canManage: Boolean = false,
    private val onSelect: (Long?) -> Unit,
    private val onAdd: (String) -> Unit = {},
    private val onDelete: (Long) -> Unit = {},
) {
    /** The closed control — the toolkit's trigger, exactly as [Dropdown]'s is. */
    val element: HTMLButtonElement =
        buildMenuTrigger(isField = isField, extraClass = "dropdown $className".trim())

    /**
     * Whether Add and Delete are offered. A settable var rather than a constructor
     * constant because a caller — the issue editor — learns whether this user may
     * manage the vocabulary only once the board loads, and sets it on every render.
     * Read at open time, so a change takes effect the next time the menu is opened.
     */
    var canManage: Boolean = canManage

    private var versions: List<VocabularyItem> = emptyList()
    private var selectedId: Long? = null
    private var placeholder: String = ""
    private var menu: HTMLElement? = null
    private var dismiss: (() -> Unit)? = null

    init {
        element.onclick = { if (menu != null) close() else open(); Unit }
    }

    /** Render the closed control. Its label is the chosen version's name, or [placeholder]. */
    fun render(versions: List<VocabularyItem>, selectedId: Long?, placeholder: String = "None") {
        this.versions = versions
        this.selectedId = selectedId
        this.placeholder = placeholder
        val chosen = versions.firstOrNull { it.id == selectedId }?.name
        // "None" is written out rather than left blank — a field that merely
        // looked empty would be indistinguishable from one that failed to load —
        // and it is a row you can pick, taking the check like any other. But it
        // reads DIM here, because in a row of six fields the useful glance is
        // which ones are still unanswered.
        setMenuTriggerLabel(element, text = chosen ?: placeholder, isUnset = chosen == null)
    }

    /** Tears the menu down, listeners included. A no-op when already closed. */
    fun close() {
        menu?.remove()
        menu = null
        setMenuTriggerOpen(element, false)
        dismiss?.invoke()
        dismiss = null
    }

    private fun open() {
        val box = element("div", "$MENU_CLASS dt-hover-menu")
        box.setAttribute("role", "menu")

        if (allowNone) box.appendChild(choiceRow(null, "None"))
        versions.forEach { box.appendChild(versionRow(it)) }
        if (canManage) box.appendChild(addRow(box))

        document.body?.appendChild(box)
        anchorUnder(box)
        menu = box
        setMenuTriggerOpen(element, true)
        installDismissal(box)
    }

    /** A plain choice row — "None", or a version whose management affordances are off. */
    private fun choiceRow(id: Long?, label: String): HTMLElement {
        val active = id == selectedId
        val row = element("div", "dt-hover-menu-item dt-world-row" + if (active) " dt-menu-selected" else "")
        row.setAttribute("role", "menuitem")
        val check = element("span", "dt-hover-menu-icon dt-world-check")
        if (active) check.innerHTML = CHECK_SVG
        row.children(check, element("span", "dt-hover-menu-label", label))
        row.onclick = { close(); onSelect(id); Unit }
        return row
    }

    /** A version row: the choice, plus — when managed — an ellipsis that offers Delete. */
    private fun versionRow(version: VocabularyItem): HTMLElement {
        val row = choiceRow(version.id, version.name)
        if (!canManage) return row
        val menuButton = button("⋯", "version-row-menu") {}
        menuButton.setAttribute("aria-label", "Version options")
        menuButton.onclick = { event ->
            // Stop the row's own click: the ellipsis is a second control sitting on
            // the row, and a bubble would both open the item menu and select the
            // version underneath it.
            event.stopPropagation()
            val rect = menuButton.getBoundingClientRect()
            // Leave the version list up: the Delete menu opens as a small popover
            // over it, not in place of it. Closing the whole dropdown the moment
            // the ellipsis is pressed reads as the list vanishing before you have
            // chosen anything (LNL-134 follow-up). This click stopped propagating
            // and openContextMenu defers its own dismissal by a tick, so neither
            // popover closes the other on the very click that opened the menu.
            openContextMenu(rect.left, rect.bottom, listOf(DropdownItem(version.id, "Delete"))) {
                // Choosing Delete dismisses the version list too — its outside-click
                // dismissal fires on the same click, since the click lands on the
                // context menu rather than inside the list — and the confirm mounts
                // on the body like every other confirmation.
                confirmDelete(version)
            }
        }
        row.appendChild(menuButton)
        return row
    }

    private fun confirmDelete(version: VocabularyItem) {
        val host = document.body ?: return
        ConfirmDialog(
            title = "Delete version",
            message = "Delete \"${version.name}\"? Any issue planned for it or fixed in it loses that " +
                "version. This cannot be undone.",
            destructiveLabel = "Delete",
            onConfirm = { onDelete(version.id) },
            onCancel = {},
        ).mount(host)
    }

    /** The bottom "Add new version…" row, which turns into a field in place. */
    private fun addRow(box: HTMLElement): HTMLElement {
        val row = element("div", "dt-hover-menu-item dt-world-row version-add-row")
        row.setAttribute("role", "menuitem")
        row.children(
            element("span", "dt-hover-menu-icon", "+"),
            element("span", "dt-hover-menu-label", "Add new version…"),
        )
        row.onclick = { event ->
            // Stop the click here. showAddField empties the box, which detaches
            // this very row before the click finishes bubbling to the document's
            // outside-click dismissal — that handler would then find its target
            // no longer inside the box and close the whole menu, so the add field
            // never gets its moment. See installDismissal.
            event.stopPropagation()
            showAddField(box)
        }
        return row
    }

    /**
     * Swap the menu for a single committing field, so the version is named without
     * leaving the open dropdown. Enter adds and closes; Escape (or an outside click,
     * via the dismissal already installed) backs out.
     */
    private fun showAddField(box: HTMLElement) {
        box.clear()
        val field = document.createElement("input") as HTMLInputElement
        field.type = "text"
        field.className = "field version-add-field"
        field.placeholder = "Version name"
        field.onkeydown = { event ->
            when (event.key) {
                "Enter" -> {
                    val name = field.value.trim()
                    if (name.isNotEmpty()) {
                        close()
                        onAdd(name)
                    }
                }
                "Escape" -> close()
                else -> Unit
            }
        }
        box.appendChild(field)
        field.focus()
    }

    /** Under the control's left edge, clamped to the viewport — [Dropdown.open]'s math. */
    private fun anchorUnder(box: HTMLElement) {
        val anchor = element.getBoundingClientRect()
        box.style.minWidth = "${anchor.width}px"
        val laid = box.getBoundingClientRect()
        val left = anchor.left.coerceAtMost(window.innerWidth - laid.width - 4.0).coerceAtLeast(4.0)
        box.style.left = "${left}px"
        // Flips above the control when below would clip it, exactly as a
        // [Dropdown] does — the two are the same object to a reader, and a
        // version list that opened upwards where a status list opened downwards
        // would be the one difference between them anybody noticed.
        box.style.top = "${anchorTop(anchor, laid.height)}px"
    }

    /** Closes on Escape or an outside click — [Dropdown.installDismissal], verbatim in intent. */
    private fun installDismissal(box: HTMLElement) {
        val outside: (Event) -> Unit = handler@{ event ->
            val target = event.target as? HTMLElement ?: return@handler
            if (box.contains(target) || element.contains(target)) return@handler
            close()
        }
        val escape: (Event) -> Unit = { event ->
            if ((event as? KeyboardEvent)?.key == "Escape") close()
        }
        document.addEventListener("click", outside)
        document.addEventListener("keydown", escape)
        dismiss = {
            document.removeEventListener("click", outside)
            document.removeEventListener("keydown", escape)
        }
    }

    private companion object {
        // The same marker Dropdown uses; see Dom.kt's MENU_CLASS for the prefix rule.
        const val MENU_CLASS = "lunicle-dropdown-menu"
    }
}
