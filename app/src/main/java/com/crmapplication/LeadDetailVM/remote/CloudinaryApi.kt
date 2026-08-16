package com.crmapplication.LeadDetailVM.remote

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PartMap
import retrofit2.http.Url

/**
 * Cloudinary's public upload REST API, called directly from the device.
 *
 * Deliberately a separate interface from [UploadApi]: the base URL is Cloudinary's, not ours, and
 * these requests must **never** carry our `Authorization` header. The signed form fields are the
 * whole of the authentication, and the JWT would be leaking to a third party for no reason.
 */
interface CloudinaryApi {

    /**
     * @param url the full `https://api.cloudinary.com/v1_1/<cloudName>/auto/upload`. Passed per-call
     *   because the cloud name arrives at runtime in the signature response rather than being baked
     *   into the build — the backend owns which Cloudinary account we upload to.
     * @param fields the signed parameter set (`api_key`, `timestamp`, `signature`, and whichever of
     *   `folder` / `public_id` the backend actually signed). A [PartMap] rather than fixed [Part]s so
     *   an unsigned parameter can be *absent* instead of present-and-empty: Cloudinary verifies the
     *   signature against the exact set of parameters it receives, so sending a field the backend
     *   didn't sign fails the whole upload with "Invalid Signature".
     */
    @Multipart
    @POST
    suspend fun upload(
        @Url url: String,
        @Part file: MultipartBody.Part,
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>,
    ): Response<CloudinaryUploadDto>
}

/**
 * The subset of Cloudinary's upload response we use. Field names are snake_case on the wire and the
 * shared Gson has no naming policy configured, so each one needs its [SerializedName].
 */
data class CloudinaryUploadDto(
    @SerializedName("secure_url") val secureUrl: String? = null,
    @SerializedName("public_id") val publicId: String? = null,
    @SerializedName("resource_type") val resourceType: String? = null,
    val format: String? = null,
    val bytes: Long? = null,
    val error: CloudinaryErrorDto? = null,
)

/** Cloudinary nests failures as `{"error": {"message": "..."}}`, unlike our own flat `{"error": "..."}`. */
data class CloudinaryErrorDto(
    val message: String? = null,
)
