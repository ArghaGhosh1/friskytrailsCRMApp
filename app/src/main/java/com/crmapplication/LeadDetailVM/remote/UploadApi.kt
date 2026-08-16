package com.crmapplication.LeadDetailVM.remote

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

data class UploadResponse(
    val message: String? = null,
    val fileUrl: String? = null,
    val fileData: UploadFileData? = null,

    val error: String? = null,
)

data class UploadFileData(
    val originalname: String? = null,
    val mimetype: String? = null,
    val path: String? = null,
    val size: Long? = null,
)

/**
 * Short-lived credentials that let the device upload straight to Cloudinary.
 *
 * [publicId] is the backend echoing back the id it actually signed. It is **not** in the published
 * response example but the web client reads it, and the distinction matters: sending a `public_id`
 * form field the backend didn't sign fails the upload with "Invalid Signature", so an absent
 * [publicId] has to mean "don't send one" rather than "reuse what we asked for".
 */
data class UploadSignatureDto(
    val timestamp: Long? = null,
    val signature: String? = null,
    val apiKey: String? = null,
    val cloudName: String? = null,
    val folder: String? = null,
    val publicId: String? = null,
    val message: String? = null,
    val error: String? = null,
)

interface UploadApi {

    /**
     * @param publicId the id we intend to store the file under. Sent so the backend can fold it into
     *   the signature; it decides whether to honour it, and reports that via
     *   [UploadSignatureDto.publicId].
     */
    @GET("api/upload/signature")
    suspend fun uploadSignature(
        @Header("Authorization") authorization: String?,
        @Query("public_id") publicId: String? = null,
    ): Response<UploadSignatureDto>

    /**
     * Legacy multipart upload through our own backend.
     *
     * Superseded by the signed direct-to-Cloudinary path, which sidesteps the host's 4.5 MB request
     * body limit. Kept as a fallback for builds running against a backend where
     * [uploadSignature] isn't deployed yet — see `LeadsRepository.uploadDocument`.
     */
    @Multipart
    @POST("api/upload")
    suspend fun upload(
        @Header("Authorization") authorization: String?,
        @Part file: MultipartBody.Part,
    ): Response<UploadResponse>
}
