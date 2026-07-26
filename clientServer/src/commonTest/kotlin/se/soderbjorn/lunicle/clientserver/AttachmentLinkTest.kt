/**
 * The link re-spelling, pinned.
 *
 * This runs once, at startup, over every issue description and comment body on
 * the instance — user text that nothing else is allowed to rewrite. So the
 * interesting cases are not the ones it changes but the ones it must leave
 * exactly alone, and that is where most of this file is spent.
 *
 * @see retargetAttachmentLinks
 * @see se.soderbjorn.lunicle.AttachmentLinkRepair
 */
package se.soderbjorn.lunicle.clientserver

import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentLinkTest {

    @Test
    fun `a download link to a viewable attachment is re-spelled`() {
        assertEquals(
            "See [report.html (8.1 kB)](/api/attachments/12/view) for the details.",
            retargetAttachmentLinks(
                "See [report.html (8.1 kB)](/api/attachments/12) for the details.",
                setOf("12"),
            ),
        )
    }

    @Test
    fun `an attachment nobody may view is left alone`() {
        val markdown = "[archive.zip (2.3 MB)](/api/attachments/12)"
        assertEquals(markdown, retargetAttachmentLinks(markdown, setOf("7")))
    }

    @Test
    fun `a link already spelled for viewing is left alone`() {
        // What makes running this on every boot cost nothing on a repaired volume,
        // and what stops a second pass producing "/view/view".
        val markdown = "[report.html (8.1 kB)](/api/attachments/12/view)"
        assertEquals(markdown, retargetAttachmentLinks(markdown, setOf("12")))
    }

    @Test
    fun `it is idempotent`() {
        val once = retargetAttachmentLinks("[r](/api/attachments/12)", setOf("12"))
        assertEquals(once, retargetAttachmentLinks(once, setOf("12")))
    }

    @Test
    fun `an id is not matched inside a longer one`() {
        // The whole target has to match, or repairing attachment 1 would rewrite
        // attachment 12, 13 and 199 into URLs that answer 404.
        assertEquals(
            "[a](/api/attachments/12) [b](/api/attachments/1/view)",
            retargetAttachmentLinks("[a](/api/attachments/12) [b](/api/attachments/1)", setOf("1")),
        )
    }

    @Test
    fun `a URL loose in prose is left as prose`() {
        // The writer typed text. An autolinker may draw it as a link when the
        // issue is read, but this pass does not get to decide that the stored
        // document meant something else.
        val markdown = "The file is at /api/attachments/12 if you want it."
        assertEquals(markdown, retargetAttachmentLinks(markdown, setOf("12")))
    }

    @Test
    fun `several links in one document are all re-spelled`() {
        assertEquals(
            "[a](/api/attachments/1/view) and [b](/api/attachments/2/view) and [c](/api/attachments/3)",
            retargetAttachmentLinks(
                "[a](/api/attachments/1) and [b](/api/attachments/2) and [c](/api/attachments/3)",
                setOf("1", "2"),
            ),
        )
    }

    @Test
    fun `a document with no attachment links is returned untouched`() {
        val markdown = "Just some prose, with a [link](https://example.com) in it."
        assertEquals(markdown, retargetAttachmentLinks(markdown, setOf("12")))
    }

    @Test
    fun `an empty id set changes nothing`() {
        val markdown = "[report.html](/api/attachments/12)"
        assertEquals(markdown, retargetAttachmentLinks(markdown, emptySet()))
    }
}
