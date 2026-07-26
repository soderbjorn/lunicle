/**
 * The volume and the attachments table, kept in step.
 *
 * This is the repository the schema doc means by "wires up multiple data
 * sources", and it is the only place in the server where a database write and a
 * filesystem write have to agree. Write file → insert row; delete row → unlink
 * file; sweep orphans at startup.
 *
 * **The cost, honestly:** writes are not atomic and cannot be made so. A file
 * written whose row fails to insert leaks disk; a row deleted whose file fails
 * to unlink does the same. Neither loses data, and neither is recoverable by
 * being clever — so instead there is [sweepOrphans], the same shape as the
 * session sweep already in `Application.module`. It earns its keep twice: it
 * also collects the files behind cancelled drafts.
 *
 * @see AttachmentStore
 * @see Database
 */
package se.soderbjorn.lunicle

import se.soderbjorn.lunicle.clientserver.MAX_ATTACHMENT_BYTES
import se.soderbjorn.lunicle.clientserver.formatByteSize
import se.soderbjorn.lunicle.clientserver.normaliseMimeType
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * The shape a `Content-Type` has to have to be written to a row.
 *
 * ── What this is defending, and what it is not ──────────────────────────────
 *
 * Not the *set* of types — any type is storable now; see [validate]. This is
 * about the string itself. `mime_type` is echoed back as a `Content-Type`
 * response header by the download route, and it is written from a header the
 * caller sent, so a value carrying a newline is response-splitting: a second
 * header, or a second response, of the attacker's choosing, from our origin.
 *
 * Ktor parses the request header and would reject the worst of it long before
 * here, and the route parses the column again on the way out. This is the third
 * of three, and it is the cheap one: what reaches the column is `token/token`
 * with the RFC 2045 character set, or it is not stored at all. There is nothing
 * expressible in that set that a header parser can be surprised by.
 */
private val MIME_TYPE_SHAPE = Regex("^[A-Za-z0-9!#$&^_.+-]{1,64}/[A-Za-z0-9!#$&^_.+-]{1,64}$")

/** An upload we will not accept, carrying the sentence to show the user. */
class AttachmentRejected(val userMessage: String) : Exception(userMessage)

/**
 * A stored attachment, as its two identifiers.
 *
 * Both, because the callers genuinely need different ones and picking one would
 * make the other a second lookup: the routes answer with [publicId], because
 * that is what goes in a URL and in the markdown the editor writes, while the
 * ticketed-upload response also reports [id] for an importer reconciling what it
 * has uploaded against its own records.
 */
data class StoredAttachment(val id: Long, val publicId: String)

/**
 * Stores and serves attachment bytes.
 *
 * @param blobStore where the bytes go. [DiskAttachmentBlobStore] on the volume
 *   (the default, and the compatibility constructor below builds it from a
 *   directory), [GcsAttachmentBlobStore] on Cloud Storage. This repository keeps
 *   the byte store and the metadata store in step; which byte store it is, it does
 *   not care — with the one exception of [fileFor], the disk-only zero-copy
 *   download path. See [AttachmentBlobStore].
 */
class AttachmentRepository(
    private val attachments: se.soderbjorn.lunicle.store.AttachmentStore,
    private val blobStore: AttachmentBlobStore,
) {
    private val random = SecureRandom()

    /**
     * The volume-backed repository — the default, and every caller that predates the
     * blob-store seam. Derives the directory into a [DiskAttachmentBlobStore], so
     * the disk path is byte-for-byte what it always was.
     *
     * @param directory where the files go. Derived from the same [DatabaseLocation]
     *   the database uses rather than from a second setting — one resolution means
     *   attachments cannot land on a different disk from the rows describing them,
     *   which is exactly the failure `Database.kt`'s preamble is about: it works
     *   perfectly until the container is replaced.
     */
    constructor(attachments: se.soderbjorn.lunicle.store.AttachmentStore, directory: File) :
        this(attachments, DiskAttachmentBlobStore(directory))

    /**
     * Store [bytes] and record them against an issue.
     *
     * File first, row second, deliberately: the reverse leaves a row pointing at
     * a file that does not exist, which is a broken image in someone's issue
     * forever. This order leaks a file instead, and [sweepOrphans] collects it.
     *
     * @param filename as uploaded. Stored for the download name and *never* used
     *   as a path — see [newStorageKey].
     * @param declaredMimeType what the caller's `Content-Type` claimed. A claim,
     *   not a fact, and never treated as one: it decides only whether the
     *   download route may serve these bytes inline, and a lie there can only
     *   make the uploader's own file useless. See [validate].
     * @throws AttachmentRejected if the upload is too large or empty. Not for
     *   its type — any type is storable; see [validate].
     */
    suspend fun storeForIssue(
        issueId: Long,
        filename: String,
        declaredMimeType: String,
        bytes: ByteArray,
        author: Author,
        createdAt: Long? = null,
    ): StoredAttachment {
        val mimeType = validate(declaredMimeType, bytes)
        val key = newStorageKey()
        val publicId = newPublicId()
        blobStore.store(key, bytes)
        return runCatching {
            val id = attachments.insertForIssue(
                issueId, cleanFilename(filename), mimeType, bytes.size.toLong(), key, publicId, author, createdAt,
            )
            StoredAttachment(id, publicId)
        }.getOrElse { failure ->
            // The row did not land, so the file is already an orphan. Unlink it
            // now rather than leaving it for the next restart's sweep — this is
            // the one moment we know for certain which file it was.
            blobStore.delete(key)
            throw failure
        }
    }

    /** As [storeForIssue], against a comment. */
    suspend fun storeForComment(
        commentId: Long,
        filename: String,
        declaredMimeType: String,
        bytes: ByteArray,
        author: Author,
        createdAt: Long? = null,
    ): StoredAttachment {
        val mimeType = validate(declaredMimeType, bytes)
        val key = newStorageKey()
        val publicId = newPublicId()
        blobStore.store(key, bytes)
        return runCatching {
            val id = attachments.insertForComment(
                commentId, cleanFilename(filename), mimeType, bytes.size.toLong(), key, publicId, author, createdAt,
            )
            StoredAttachment(id, publicId)
        }.getOrElse { failure ->
            blobStore.delete(key)
            throw failure
        }
    }

    /** As [storeForIssue], against a forum post. */
    suspend fun storeForForumPost(
        forumPostId: Long,
        filename: String,
        declaredMimeType: String,
        bytes: ByteArray,
        author: Author,
        createdAt: Long? = null,
    ): StoredAttachment {
        val mimeType = validate(declaredMimeType, bytes)
        val key = newStorageKey()
        val publicId = newPublicId()
        blobStore.store(key, bytes)
        return runCatching {
            val id = attachments.insertForForumPost(
                forumPostId, cleanFilename(filename), mimeType, bytes.size.toLong(), key, publicId, author, createdAt,
            )
            StoredAttachment(id, publicId)
        }.getOrElse { failure ->
            blobStore.delete(key)
            throw failure
        }
    }

    /** As [storeForIssue], against a forum comment. */
    suspend fun storeForForumComment(
        forumCommentId: Long,
        filename: String,
        declaredMimeType: String,
        bytes: ByteArray,
        author: Author,
        createdAt: Long? = null,
    ): StoredAttachment {
        val mimeType = validate(declaredMimeType, bytes)
        val key = newStorageKey()
        val publicId = newPublicId()
        blobStore.store(key, bytes)
        return runCatching {
            val id = attachments.insertForForumComment(
                forumCommentId, cleanFilename(filename), mimeType, bytes.size.toLong(), key, publicId, author, createdAt,
            )
            StoredAttachment(id, publicId)
        }.getOrElse { failure ->
            blobStore.delete(key)
            throw failure
        }
    }

    /** As [storeForIssue], against a private message. */
    suspend fun storeForMessage(
        messageId: Long,
        filename: String,
        declaredMimeType: String,
        bytes: ByteArray,
        author: Author,
        createdAt: Long? = null,
    ): StoredAttachment {
        val mimeType = validate(declaredMimeType, bytes)
        val key = newStorageKey()
        val publicId = newPublicId()
        blobStore.store(key, bytes)
        return runCatching {
            val id = attachments.insertForMessage(
                messageId, cleanFilename(filename), mimeType, bytes.size.toLong(), key, publicId, author, createdAt,
            )
            StoredAttachment(id, publicId)
        }.getOrElse { failure ->
            blobStore.delete(key)
            throw failure
        }
    }

    /**
     * The file behind a record, for the download route to stream — and for the
     * cascade-delete callers that unlink a doomed file directly.
     *
     * Disk-only, and deliberately so: it is the zero-copy `LocalFileContent`
     * download path (see [AttachmentBlobStore]'s preamble on why the bytes must
     * never land in heap). It delegates to the [DiskAttachmentBlobStore] and errors
     * under any other byte store — the GCS backend serves and unlinks bytes through
     * the [AttachmentBlobStore] seam, not through a `File`. The GCS download and
     * cascade-delete wiring is integration work for when that backend is assembled;
     * every caller of this today runs on the disk backend.
     */
    fun fileFor(storageKey: String): File =
        (blobStore as? DiskAttachmentBlobStore)?.fileFor(storageKey)
            ?: error("fileFor is disk-only; the GCS backend serves and deletes bytes through AttachmentBlobStore.")

    /**
     * Delete the row and then the file.
     *
     * Row first here, the opposite of the write, and for the same reason: the
     * failure this order produces is a leaked file, which is collectable. The
     * other order produces a row pointing at nothing, which is not.
     */
    suspend fun delete(record: AttachmentRecord) {
        attachments.delete(record.id)
        blobStore.delete(record.storageKey)
    }

    /**
     * Delete every file under the directory with no matching `storage_key`.
     *
     * Called once at startup by `Application.module`, alongside the session
     * sweep and for the same reasons. It collects three things: files whose row
     * never landed, files whose row was deleted by a cascade this repository
     * never saw (deleting an issue takes its attachment *rows* with it, and
     * SQLite has no way to reach the filesystem), and the files behind drafts
     * somebody abandoned by closing the tab.
     *
     * @return how many files were removed, for the log.
     */
    suspend fun sweepOrphans(): Int = blobStore.sweepOrphans(attachments.allStorageKeys())

    /**
     * Check the size, and reduce the caller's type claim to something storable.
     *
     * ── Why there is no longer a list of allowed types ──────────────────────
     *
     * There used to be, and it allowed five bitmap formats. It was doing two
     * jobs at once and only one of them was real:
     *
     *  - It kept a `text/html` upload from being served back *inline* from our
     *    origin, where it would run with `lunicle_session` in scope. That is
     *    stored XSS, and it is the reason the list was security code.
     *  - It also decided what could be *stored*, which nothing ever required.
     *
     * Those are separable, and separating them is what lets an issue carry the
     * crash log that explains it. What matters is never the bytes on the volume
     * — they are inert there — but the headers they come back under. So the
     * check moved to where it belongs: the download route serves the types in
     * `INLINE_IMAGE_MIME_TYPES` inline and *everything else* as
     * `Content-Disposition: attachment` with `nosniff`, which a browser saves
     * rather than executes no matter what is in it. See
     * `BoardRoutes.attachmentRoutes`.
     *
     * The one thing that would undo this: adding a type to
     * `INLINE_IMAGE_MIME_TYPES` that is a document rather than a bitmap. Its
     * KDoc says so at length, and `image/svg+xml` is the specific trap.
     *
     * @return the mime type to store: the caller's claim, lowercased and
     *   stripped of parameters, or `application/octet-stream` if it is not a
     *   well-formed type at all. Never rejected for being unrecognised — an
     *   unrecognised type is a download, which is a correct outcome, where a 400
     *   would mean this app refusing files over an unfamiliar extension.
     */
    private fun validate(declaredMimeType: String, bytes: ByteArray): String {
        if (bytes.size > MAX_ATTACHMENT_BYTES) {
            throw AttachmentRejected(
                "That file is ${formatByteSize(bytes.size.toLong())}, and the limit is " +
                    "${formatByteSize(MAX_ATTACHMENT_BYTES)}. Link to it instead, or send a smaller version.",
            )
        }
        if (bytes.isEmpty()) throw AttachmentRejected("That file is empty.")
        val type = normaliseMimeType(declaredMimeType)
        // octet-stream rather than a refusal: "we could not parse your
        // Content-Type" is not a sentence a user can act on, and the bytes are
        // perfectly storable regardless. It is also the inert answer — nothing
        // is served inline under it — so guessing wrong here fails safe.
        return if (MIME_TYPE_SHAPE.matches(type)) type else "application/octet-stream"
    }

    /**
     * A random name for the file on disk.
     *
     * Random, and *not* derived from the uploaded filename. A user-supplied name
     * reaching the filesystem is a path-traversal bug waiting for
     * "../../lunicle.db"; this is the only thing that ever names a path, and
     * nothing a user types reaches it. Base64url so it is a legal filename
     * everywhere without escaping.
     *
     * Its unguessability is a second line of defence and not the first: the
     * download route checks `canReadProject` before it streams a byte. A random
     * key that was the *only* defence would be security by URL secrecy, which
     * fails the moment one is pasted anywhere.
     */
    private fun newStorageKey(): String = randomToken(bytes = 24)

    /**
     * The name this attachment will have in a URL.
     *
     * A second random string rather than reusing [newStorageKey]'s, and the
     * separation is the point: the storage key names a path on the volume, so
     * publishing it would tell every reader the filename on disk and would weld
     * the public URL to the storage layout — re-organise the volume and every
     * link ever pasted dies. Two tokens cost sixteen bytes.
     *
     * 16 bytes, where the storage key uses 24. Both are far past guessing; the
     * difference is only that this one is read by humans out of a URL bar and
     * pasted into chat, so it is worth being 22 characters rather than 32.
     *
     * Same standing as the storage key on what it is *for*: `serveAttachment`
     * still runs `canReadProject` before streaming a byte, so this is the second
     * line of defence and not the first. What it fixes (LNL-51) is narrower and
     * real — with the row id in the URL, the attachments on this instance could
     * be enumerated by counting, which told an outsider how many there were and
     * let a private project's files be distinguished from ones that never
     * existed.
     */
    private fun newPublicId(): String = randomToken(bytes = 16)

    private fun randomToken(bytes: Int): String =
        ByteArray(bytes).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /**
     * The filename, made safe to hand back in a `Content-Disposition`.
     *
     * Not a path defence — nothing here reaches the filesystem — but a header
     * defence: a newline in a filename would let a caller inject a second
     * response header, and quotes would break out of the one it lands in.
     */
    private fun cleanFilename(filename: String): String =
        filename.filterNot { it == '"' || it == '\\' || it == '\n' || it == '\r' }
            .trim()
            .take(200)
            .ifBlank { "attachment" }
}
