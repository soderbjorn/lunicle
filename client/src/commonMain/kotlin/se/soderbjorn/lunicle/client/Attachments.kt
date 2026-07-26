/**
 * How an uploaded file is spelled in markdown.
 *
 * Shared by both view models that can upload — an issue's description and a
 * comment — because the decision is the same one in both places and a second
 * copy of it is a second chance to spell an attachment two different ways.
 *
 * In :client rather than beside the rest of the attachment rules in
 * :clientServer because it needs [escapeMarkdown], and that belongs to the
 * renderer. The markdown *spelling* of an attachment and the markdown escaping
 * rules have to move together or the escaping is wrong; the size limit and the
 * inline list have no such tie and live where both sides can read them.
 *
 * @see se.soderbjorn.lunicle.client.viewmodel.IssueBackingViewModel.uploadAttachment
 * @see renderMarkdown
 */
package se.soderbjorn.lunicle.client

import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.formatByteSize
import se.soderbjorn.lunicle.clientserver.isInlineImageType
import se.soderbjorn.lunicle.clientserver.isSandboxedDocumentType

/**
 * The markdown for a file that has just been uploaded.
 *
 * ── The one decision in this file ───────────────────────────────────────────
 *
 * An image gets `![name](url)`, so it renders where it was inserted. Everything
 * else — a PDF, a zip, a screen recording, a log — gets `[name (2.3 MB)](url)`,
 * a plain link, which the renderer draws as a download because it recognises the
 * URL. See [renderMarkdown].
 *
 * That split is not a preference. It has to match, exactly, the server's
 * inline-versus-download decision, or an `<img>` ends up pointing at bytes the
 * server insists the browser download and the issue shows a broken image
 * forever. [isInlineImageType] is the single list both sides read, and this
 * function is the only place on this side that asks.
 *
 * The size goes in the *label* rather than being carried beside it, because
 * markdown has nowhere else to put it: `[text](url)` has exactly two slots. The
 * label is also the honest place for it — it is what the reader is deciding on
 * before they click, and it stays legible if the document is ever read as plain
 * markdown by something that is not this app.
 *
 * @param filename as the user's disk had it. Reaches the reader's screen, so it
 *   is escaped on the way through [renderMarkdown] like all other text.
 * @param mimeType the browser's claim about the file. Only ever used to choose
 *   between the two spellings above.
 * @param byteSize what was actually uploaded.
 * @param attachmentId what the server called it — the public id it answered the
 *   upload with, which is the only name this side ever learns. Opaque here on
 *   purpose: this string is written into a stored document, and the less it
 *   means to the client the less there is to go stale.
 */
fun attachmentMarkdown(filename: String, mimeType: String, byteSize: Long, attachmentId: String): String {
    val url = ApiRoutes.attachment(attachmentId)
    // Escaped here, not by the caller: this string is stored, and the filename is
    // the one part of it a user chose. "report[final].pdf" would otherwise close
    // the label early and leave the rest of the name loose in the document, and
    // "2*3.png" would come back with an italic in it. See ESCAPABLE.
    val name = escapeMarkdown(filename)
    if (isInlineImageType(mimeType)) return "![$name]($url)"
    // A file the browser can be trusted to *show* — today that means an HTML
    // report — is spelled with the view URL instead, which is what tells the
    // renderer to open it in a tab rather than hand it to the disk. Same two
    // slots, same label; only the URL differs. See ApiRoutes.attachmentView.
    val target = if (isSandboxedDocumentType(mimeType)) ApiRoutes.attachmentView(attachmentId) else url
    return "[$name (${formatByteSize(byteSize)})]($target)"
}
