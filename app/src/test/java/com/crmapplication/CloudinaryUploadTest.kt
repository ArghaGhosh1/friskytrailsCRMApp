package com.crmapplication

import com.crmapplication.LeadDetailVM.remote.CloudinaryUploadDto
import com.crmapplication.LeadDetailVM.remote.UploadSignatureDto
import com.crmapplication.LeadDetailVM.repository.cloudinaryFailureMessage
import com.crmapplication.LeadDetailVM.repository.parseCloudinaryError
import com.crmapplication.LeadDetailVM.repository.parseSignatureError
import com.crmapplication.utils.cloudinaryPublicId
import com.crmapplication.utils.randomSuffix
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the direct-to-Cloudinary upload pipeline: `GET api/upload/signature`, then a signed
 * multipart POST to Cloudinary, then a note carrying the returned `secure_url`.
 *
 * What's actually fragile here is the *signature*. Cloudinary recomputes it over the exact parameter
 * set it receives, so a `public_id` that differs by one character between the signing request and the
 * upload — or a field the backend never signed — fails the whole upload with an opaque "Invalid
 * Signature" rather than storing the file under a different name. Hence the emphasis on the id
 * builder being deterministic and on the DTO shapes parsing as documented.
 */
class CloudinaryUploadTest {

    private val gson = Gson()

    // region public_id

    @Test
    fun `an image keeps its name and drops its extension`() {
        // Cloudinary appends an image's real format to the delivery URL itself, so carrying ".jpg"
        // in the id would produce photo_abc123.jpg.jpg.
        assertEquals("photo_abc123", cloudinaryPublicId("photo.jpg", suffix = "abc123"))
        assertEquals("photo_abc123", cloudinaryPublicId("photo.JPEG", suffix = "abc123"))
        assertEquals("shot_abc123", cloudinaryPublicId("shot.png", suffix = "abc123"))
        assertEquals("clip_abc123", cloudinaryPublicId("clip.webp", suffix = "abc123"))
    }

    /**
     * The asymmetry that makes a PDF readable: raw assets get no format appended by Cloudinary, so
     * without the extension in the id the delivery URL arrives with no extension at all and the
     * browser has to guess.
     */
    @Test
    fun `a document keeps its extension`() {
        assertEquals("invoice_abc123.pdf", cloudinaryPublicId("invoice.pdf", suffix = "abc123"))
        assertEquals("quote_abc123.docx", cloudinaryPublicId("quote.docx", suffix = "abc123"))
        assertEquals("terms_abc123.pdf", cloudinaryPublicId("terms.PDF", suffix = "abc123"))
    }

    @Test
    fun `unsafe characters collapse to underscores`() {
        assertEquals(
            "Rahul_s_Passport__1__abc123.pdf",
            cloudinaryPublicId("Rahul's Passport (1).pdf", suffix = "abc123"),
        )
        // Hyphen and underscore are already id-safe, so they survive untouched.
        assertEquals("client-file_v2_abc123.pdf", cloudinaryPublicId("client-file_v2.pdf", suffix = "abc123"))
    }

    /**
     * A name in a non-Latin script sanitizes to nothing usable. Falling back beats sending an id of
     * bare underscores, which Cloudinary would accept but nobody could recognise in the media library.
     */
    @Test
    fun `a name with nothing to keep falls back`() {
        assertEquals("file_abc123.pdf", cloudinaryPublicId("पासपोर्ट.pdf", suffix = "abc123"))
        assertEquals("file_abc123", cloudinaryPublicId(null, suffix = "abc123"))
        assertEquals("file_abc123", cloudinaryPublicId("", suffix = "abc123"))
        assertEquals("file_abc123", cloudinaryPublicId("   ", suffix = "abc123"))
        assertEquals("file_abc123.pdf", cloudinaryPublicId("...pdf", suffix = "abc123"))
    }

    @Test
    fun `a name without an extension is still valid`() {
        assertEquals("scan_abc123", cloudinaryPublicId("scan", suffix = "abc123"))
    }

    @Test
    fun `only the last dot separates the extension`() {
        assertEquals("archive_tar_abc123.gz", cloudinaryPublicId("archive.tar.gz", suffix = "abc123"))
    }

    /**
     * A trailing segment that isn't extension-shaped — a dotted version number, say — must not be
     * mistaken for one and appended, or the stored id would end in a meaningless ".v2 final".
     */
    @Test
    fun `a trailing segment that is not an extension is not treated as one`() {
        assertEquals("report_v2_final_abc123", cloudinaryPublicId("report.v2 final", suffix = "abc123"))
    }

    @Test
    fun `the generated suffix is six base36 characters and varies`() {
        val suffixes = List(50) { randomSuffix() }
        suffixes.forEach {
            assertEquals(6, it.length)
            assertTrue("unexpected character in $it", it.all { c -> c.isDigit() || c in 'a'..'z' })
        }
        assertTrue("50 draws should not all collide", suffixes.distinct().size > 1)
    }

    /** Two uploads of the same file must not overwrite one another in the media library. */
    @Test
    fun `the same file name yields different ids`() {
        assertNotEquals(cloudinaryPublicId("photo.jpg"), cloudinaryPublicId("photo.jpg"))
    }

    // endregion

    // region signature response

    @Test
    fun `the documented signature response parses`() {
        val dto = gson.fromJson(
            """
            {
              "timestamp": 1785934123,
              "signature": "9d4f8e7c1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d",
              "apiKey": "123456789012345",
              "cloudName": "your-cloud-name",
              "folder": "crm_attachments"
            }
            """.trimIndent(),
            UploadSignatureDto::class.java,
        )
        assertEquals(1785934123L, dto.timestamp)
        assertEquals("9d4f8e7c1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d", dto.signature)
        assertEquals("123456789012345", dto.apiKey)
        assertEquals("your-cloud-name", dto.cloudName)
        assertEquals("crm_attachments", dto.folder)
    }

    /**
     * The documented example omits `publicId`, and that absence is meaningful rather than incidental:
     * it tells the client not to send a `public_id` form field, because the signature was computed
     * without one. Sending it anyway fails the upload.
     */
    @Test
    fun `an absent publicId parses as null, not as the id we asked for`() {
        val dto = gson.fromJson("""{"signature": "abc", "cloudName": "c"}""", UploadSignatureDto::class.java)
        assertNull(dto.publicId)
    }

    @Test
    fun `an echoed publicId parses`() {
        val dto = gson.fromJson("""{"publicId": "invoice_abc123.pdf"}""", UploadSignatureDto::class.java)
        assertEquals("invoice_abc123.pdf", dto.publicId)
    }

    @Test
    fun `a signature error message is surfaced`() {
        assertEquals("Not authorised", parseSignatureError("""{"error":"Not authorised"}"""))
        assertEquals("Cloudinary not configured", parseSignatureError("""{"message":"Cloudinary not configured"}"""))
        assertNull(parseSignatureError(null))
        assertNull(parseSignatureError(""))
        assertNull(parseSignatureError("<html>502 Bad Gateway</html>"))
    }

    // endregion

    // region cloudinary response

    @Test
    fun `the documented upload response yields a secure url`() {
        val dto = gson.fromJson(
            """
            {
              "public_id": "crm_attachments/sample_doc_a1b2c3",
              "format": "png",
              "resource_type": "image",
              "bytes": 5242880,
              "url": "http://res.cloudinary.com/demo/image/upload/v1/crm_attachments/sample_doc_a1b2c3.png",
              "secure_url": "https://res.cloudinary.com/demo/image/upload/v1/crm_attachments/sample_doc_a1b2c3.png"
            }
            """.trimIndent(),
            CloudinaryUploadDto::class.java,
        )
        assertTrue("must be the https url", dto.secureUrl!!.startsWith("https://"))
        assertEquals("crm_attachments/sample_doc_a1b2c3", dto.publicId)
        assertEquals("image", dto.resourceType)
        assertEquals(5242880L, dto.bytes)
    }

    /** Cloudinary nests its failures, unlike our own backend's flat `{"error": "..."}`. */
    @Test
    fun `a nested cloudinary error is surfaced`() {
        assertEquals(
            "Invalid Signature abc. String to sign - 'folder=crm_attachments'.",
            parseCloudinaryError(
                """{"error":{"message":"Invalid Signature abc. String to sign - 'folder=crm_attachments'."}}"""
            ),
        )
        assertNull(parseCloudinaryError("""{"error":{}}"""))
        assertNull(parseCloudinaryError(null))
    }

    // endregion

    // region failure messages

    @Test
    fun `an oversized file reports our own limit`() {
        val message = cloudinaryFailureMessage(
            "File size too large. Got 15728640. Maximum is 10485760.",
            400,
        )
        assertTrue("should state the limit in MB, got: $message", message.contains("10 MB"))
        assertTrue(message.contains("too large"))
    }

    /**
     * "Invalid Signature" is the one failure an agent can do nothing about — it means the parameters
     * the backend signed and the ones we sent disagreed. The message has to point at reporting it
     * rather than reading like a rejected file.
     */
    @Test
    fun `a signature mismatch reads as something to report`() {
        val message = cloudinaryFailureMessage("Invalid Signature abc. String to sign - 'x'.", 401)
        assertTrue("should not leak the raw signing string, got: $message", !message.contains("String to sign"))
        assertTrue(message.contains("report", ignoreCase = true))
    }

    @Test
    fun `an unrecognised message passes through and a missing one falls back to the code`() {
        assertEquals("Upload preset not found", cloudinaryFailureMessage("Upload preset not found", 400))
        assertEquals("Upload failed (HTTP 500).", cloudinaryFailureMessage(null, 500))
        assertEquals("Upload failed (HTTP 500).", cloudinaryFailureMessage("   ", 500))
    }

    // endregion
}
