package com.crmapplication.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentPartFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class DocumentMeta(val fileName: String, val mimeType: String)

    /** A note attachment, for `api/upload` / Cloudinary. Part name `file`. */
    fun build(uri: Uri): Pair<MultipartBody.Part, DocumentMeta>? {
        val (bytes, meta) = read(uri) ?: return null

        require(bytes.isNotEmpty()) { "The selected file is empty." }
        require(bytes.size <= MAX_FILE_SIZE_BYTES) {
            tooLargeMessage(bytes.size, MAX_FILE_SIZE_MB)
        }
        require(meta.isAllowedType(ALLOWED_MIME_TYPES, ALLOWED_EXTENSIONS)) {
            "Unsupported file type. Allowed: JPG, PNG, GIF, WEBP, PDF, DOC, DOCX."
        }

        return meta.toPart(bytes, DOCUMENT_PART_NAME) to meta
    }

    /**
     * A booking's transaction screenshot, for `POST api/bookings`. Part name `screenshot`.
     *
     * Tighter than [build] on both axes because the booking backend is: 5 MB rather than 10, and no
     * GIF/WEBP/DOC. Enforcing that here rather than letting the server reject it saves the agent
     * re-filling a long form after uploading a file that was never going to be accepted.
     */
    fun buildScreenshot(uri: Uri): Pair<MultipartBody.Part, DocumentMeta>? {
        val (bytes, meta) = read(uri) ?: return null

        require(bytes.isNotEmpty()) { "The selected screenshot is empty." }
        require(bytes.size <= MAX_SCREENSHOT_SIZE_BYTES) {
            tooLargeMessage(bytes.size, MAX_SCREENSHOT_SIZE_MB)
        }
        require(meta.isAllowedType(SCREENSHOT_MIME_TYPES, SCREENSHOT_EXTENSIONS)) {
            "Unsupported file type. Allowed: PNG, JPG, JPEG, PDF."
        }

        return meta.toPart(bytes, SCREENSHOT_PART_NAME) to meta
    }

    /** Returns null only when the URI can't be opened at all — a revoked permission or a dead file. */
    private fun read(uri: Uri): Pair<ByteArray, DocumentMeta>? {
        val resolver = context.contentResolver
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null

        val meta = DocumentMeta(
            fileName = resolver.displayName(uri),
            mimeType = resolver.getType(uri) ?: "application/octet-stream",
        )
        return bytes to meta
    }

    private fun DocumentMeta.toPart(bytes: ByteArray, partName: String): MultipartBody.Part =
        MultipartBody.Part.createFormData(
            partName,
            fileName,
            bytes.toRequestBody(mimeType.toMediaTypeOrNull()),
        )

    /**
     * MIME type first, extension as the fallback: a file picked from some providers arrives as
     * `application/octet-stream` even when the name plainly says `.pdf`.
     */
    private fun DocumentMeta.isAllowedType(
        allowedMimeTypes: Set<String>,
        allowedExtensions: Set<String>,
    ): Boolean {
        if (mimeType.lowercase(Locale.US) in allowedMimeTypes) return true
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in allowedExtensions
    }

    private fun tooLargeMessage(sizeBytes: Int, limitMb: Int): String {
        val mb = String.format(Locale.US, "%.1f", sizeBytes / (1024.0 * 1024.0))
        return "File is too large ($mb MB). The maximum is $limitMb MB."
    }

    private fun ContentResolver.displayName(uri: Uri): String {
        val fromCursor = runCatching {
            query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        }.getOrNull()
        return fromCursor?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "document"
    }

    companion object {

        /**
         * The limit the web client states in its own upload error ("exceeds 10 MB limit for
         * documents/images"), so both clients refuse the same files. Cloudinary enforces its own
         * account-level cap on top of this; if it turns out to be lower, its message surfaces via
         * `cloudinaryFailureMessage` rather than this check.
         *
         * Checking it here as well as server-side is worth the duplication: the file has to be read
         * into memory to be uploaded at all, so rejecting it now costs nothing and saves the agent
         * watching a progress spinner for a 30 MB video that was always going to be refused. This is
         * the reason the old 5 MB cap could be lifted — the direct-to-Cloudinary path no longer
         * passes the body through our own serverless host and its 4.5 MB request limit.
         */
        const val MAX_FILE_SIZE_MB = 10
        const val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024

        /**
         * The booking backend's own documented upload limit. Lower than [MAX_FILE_SIZE_MB] because
         * this body goes through our API host rather than direct to Cloudinary, so it's bounded by
         * the server's multipart config, not Cloudinary's account limit.
         */
        const val MAX_SCREENSHOT_SIZE_MB = 5
        const val MAX_SCREENSHOT_SIZE_BYTES = MAX_SCREENSHOT_SIZE_MB * 1024 * 1024

        /** Field names the two backends expect. Renaming either makes the upload silently arrive empty. */
        private const val DOCUMENT_PART_NAME = "file"
        private const val SCREENSHOT_PART_NAME = "screenshot"

        private val SCREENSHOT_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "application/pdf",
        )

        private val SCREENSHOT_EXTENSIONS = setOf("png", "jpg", "jpeg", "pdf")

        private val ALLOWED_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )

        private val ALLOWED_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "pdf", "doc", "docx",
        )
    }
}
