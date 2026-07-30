/*
 * AccountMenuReattachesTest.kt (jsTest)
 *
 * The account corner opens on hover, and this is the test that it keeps doing so
 * (LNL-208).
 *
 * The menu is built once in [SignInView.mount] and then simply left in the
 * document — nothing rebuilds it, because CSS is what opens and closes it. That
 * makes it uniquely fragile to anything that sweeps the document for
 * `.dt-hover-menu`, the toolkit surface class it wears so it paints like every
 * other menu in the app: the panel is removed, `render` keeps writing rows into
 * an element attached to nothing, and hovering the corner silently does nothing
 * for the rest of the session. That is what was reported.
 *
 * The toolkit no longer sweeps (it closes menus through their owners), but a
 * deployed Lunicle builds against the published toolkit artifact rather than the
 * source tree, so the corner does not get to assume it. The guard asserted here
 * is the corner putting its own menu back on the way in.
 *
 * @see SignInView
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import se.soderbjorn.lunicle.client.viewmodel.SessionBackingViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class AccountMenuReattachesTest {

    private val hosts = mutableListOf<HTMLElement>()

    @AfterTest
    fun tearDown() {
        hosts.forEach { it.remove() }
        hosts.clear()
    }

    /** A mounted corner, and the host it lives in. */
    private fun mountCorner(): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        document.body?.appendChild(host)
        hosts += host
        // No session is pushed: `mount` builds the whole corner regardless of
        // state, and what is under test is the DOM it leaves behind rather than
        // anything the view model decides.
        SignInView(SessionBackingViewModel(), dialogHost = host).mount(host)
        return host
    }

    private fun HTMLElement.enter() {
        dispatchEvent(MouseEvent("mouseenter", MouseEventInit(bubbles = false, cancelable = false)))
    }

    /**
     * The corner puts its menu back when something else has taken it out.
     *
     * `assertSame` rather than "a menu exists": re-attaching the *original*
     * element is the whole point, since the sign-out button and the
     * impersonation rows inside it are the ones `render` has been updating all
     * along. A fresh empty panel would satisfy a weaker assertion and still open
     * onto nothing.
     */
    @Test
    fun the_menu_is_restored_when_something_removes_it() {
        val host = mountCorner()
        val root = host.querySelector(".account") as HTMLElement
        val menu = root.querySelector(".account-menu") as HTMLElement

        // Exactly what a document-wide sweep of the surface class did to it.
        menu.remove()

        root.enter()

        assertNotNull(menu.parentElement, "hovering the corner did not put its menu back")
        assertSame(root, menu.parentElement, "the menu came back somewhere other than the corner")
        assertSame(
            menu,
            root.querySelector(".account-menu"),
            "the corner grew a second menu instead of restoring the one it renders into",
        )
    }

    /** And a hover that finds everything in place changes nothing. */
    @Test
    fun an_intact_corner_is_left_alone() {
        val host = mountCorner()
        val root = host.querySelector(".account") as HTMLElement
        val menu = root.querySelector(".account-menu") as HTMLElement
        val childrenBefore = root.childElementCount

        root.enter()

        assertSame(menu, root.querySelector(".account-menu"))
        assertEquals(
            childrenBefore,
            root.childElementCount,
            "the guard moved the menu even though it was already attached",
        )
    }
}
