package com.crmapplication.LeadDetailVM.remote

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PartMap

/**
 * The `{ success, data }` / `{ success, error }` wrapper every `/api/bookings` route replies with.
 *
 * [error] carries the backend's own validation wording ("Phone Number must be exactly 10 digits…"),
 * which is more specific than anything the client could infer from a 400, so it's surfaced verbatim.
 */
data class BookingEnvelopeDto(
    val success: Boolean? = null,
    val data: BookingDto? = null,
    val error: String? = null,
)

/**
 * A created booking as the server sees it.
 *
 * Only the fields this app reads are declared — Gson drops the rest (`comments`, `tasks`, `payments`,
 * `feedbackRating`, …), which belong to back-office screens the mobile app doesn't have.
 *
 * The amounts are worth re-reading rather than assuming: the backend derives `dueAmount` from total
 * minus paid, and recalculates `paidAmount` from payments that are already **verified**. A freshly
 * submitted payment is `VERIFICATION-REQUIRED`, so [paidAmount] here is usually 0 even when the agent
 * entered a deposit. That's the server's authoritative view, not a bug.
 */
data class BookingDto(
    @SerializedName("_id")
    val id: String? = null,

    /** Human-readable `FT…` code. What an agent quotes to a customer, so it's the one worth showing. */
    val bookingId: String? = null,
    val paymentId: String? = null,

    val travellerName: String? = null,
    val travellerEmail: String? = null,
    val travellerPhone: String? = null,
    val adults: Int? = null,
    val children: Int? = null,

    val packageName: String? = null,
    val location: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,

    val totalAmount: Long? = null,
    val paidAmount: Long? = null,
    val dueAmount: Long? = null,
    val transactionId: String? = null,

    /** Cloudinary URL of the uploaded screenshot, not the bytes. */
    val screenshot: String? = null,

    val status: String? = null,
    val tripStatus: String? = null,
    val createdAt: String? = null,
)

/**
 * The booking system (`ft_booking_system`), which is a separate database behind the same host and JWT
 * as the leads API.
 *
 * **Not the same thing as `LeadsApi.bookLead`/`updateBooking`.** Those live under `api/leads/{id}/…`;
 * this is the standalone booking record with its own ids and payment ledger.
 */
interface BookingsApi {

    /**
     * Creates a booking, uploads the screenshot to Cloudinary, and opens a `VERIFICATION-REQUIRED`
     * payment ledger entry.
     *
     * Pass the lead's Mongo `_id` as a `leadId` field in [fields] to have the backend sync that lead
     * to `Booked` — that sync is the only thing tying this record back to the CRM.
     *
     * @param fields the text fields from `BookingForm.toFormFields`.
     * @param screenshot the transaction proof. Required on create; the backend accepts PNG/JPG/JPEG/PDF
     *   up to 5 MB and rejects anything else with its own message.
     */
    @Multipart
    @POST("api/bookings")
    suspend fun createBooking(
        @Header("Authorization") authorization: String?,
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>,
        @Part screenshot: MultipartBody.Part,
    ): Response<BookingEnvelopeDto>
}
