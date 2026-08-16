package com.crmapplication

import com.crmapplication.LeadDetailVM.repository.BookingForm
import com.crmapplication.LeadDetailVM.repository.PaymentMode
import com.crmapplication.LeadDetailVM.repository.toFormFields
import com.crmapplication.LeadDetailVM.repository.toIndianMobileDigits
import com.crmapplication.LeadDetailVM.repository.validate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Guards the Add Booking form (`POST /api/bookings`).
 *
 * Worth testing despite being "just a form": submitting is the only route to `Booked`, and once a lead
 * is booked this app won't let the status change again. A validation hole therefore produces an
 * unfixable record — an empty booking, or a ₹0 trip, that the agent can't take back. The wire-shape
 * tests matter for a second reason: the field keys are the whole contract with the booking backend, and
 * a typo'd key fails as a validation error about a field the agent *did* fill in.
 */
class BookingFormTest {

    /** Local midnight for a given date, matching what the date picker hands the form. */
    private fun dateMillis(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun validForm() = BookingForm(
        fullName = "John Doe",
        emailId = "john.doe@example.com",
        contactNumber = "9876543210",
        emergencyContactNumber = "9123456780",
        adults = "2",
        children = "0",
        packageName = "Bali Honeymoon Package",
        location = "Ubud, Bali, Indonesia",
        startDateMillis = dateMillis(2026, 8, 15),
        endDateMillis = dateMillis(2026, 8, 20),
        totalAmount = "150000",
        paidAmount = "50000",
        transactionId = "TXN874291857",
        paymentMode = PaymentMode.FT_HDFC,
        screenshotUri = "content://media/external/images/1234",
        screenshotName = "payment.png",
    )

    @Test
    fun `a fully filled form validates`() {
        assertTrue(validForm().validate().isValid)
    }

    @Test
    fun `an empty form reports every field`() {
        val errors = BookingForm().validate()
        assertFalse(errors.isValid)
        listOf(
            errors.fullName, errors.emailId, errors.contactNumber, errors.emergencyContactNumber,
            errors.adults, errors.children, errors.packageName, errors.location,
            errors.startDate, errors.endDate, errors.totalAmount, errors.paidAmount,
            errors.transactionId, errors.screenshot,
        ).forEach { assertNotNull("every blank field should report an error", it) }
    }

    @Test
    fun `malformed email is rejected`() {
        assertNotNull(validForm().copy(emailId = "john.doe").validate().emailId)
        assertNotNull(validForm().copy(emailId = "john@doe").validate().emailId)
        assertNotNull(validForm().copy(emailId = "john doe@x.com").validate().emailId)
        assertNull(validForm().copy(emailId = "j.d+tag@sub.example.co.in").validate().emailId)
    }

    /** The API's rule, and stricter than the old 10-15 digit range this form used to allow. */
    @Test
    fun `phone must be 10 digits starting 6 to 9`() {
        assertNotNull(validForm().copy(contactNumber = "98765").validate().contactNumber)
        assertNotNull(validForm().copy(contactNumber = "98765432109").validate().contactNumber)
        assertNotNull(validForm().copy(contactNumber = "5876543210").validate().contactNumber)
        assertNull(validForm().copy(contactNumber = "6876543210").validate().contactNumber)
        assertNull(validForm().copy(contactNumber = "9876543210").validate().contactNumber)
    }

    /**
     * The phone is pre-filled from the lead, which commonly stores a `+91` prefix. Without
     * normalisation the form would open already invalid on a perfectly good number — so this is a
     * usability guard, not just tidiness.
     */
    @Test
    fun `a country code or formatting is normalised away`() {
        assertEquals("9876543210", "+91 98765-43210".toIndianMobileDigits())
        assertEquals("9876543210", "919876543210".toIndianMobileDigits())
        assertEquals("9876543210", "09876543210".toIndianMobileDigits())
        assertNull(validForm().copy(contactNumber = "+91 98765-43210").validate().contactNumber)
    }

    /**
     * The emergency number isn't sent to the booking API, so it keeps the looser rule — an agent
     * should be able to record an international next-of-kin number.
     */
    @Test
    fun `emergency contact allows an international number`() {
        assertNull(
            validForm().copy(emergencyContactNumber = "+1 415 555 0123").validate()
                .emergencyContactNumber
        )
        assertNotNull(
            validForm().copy(emergencyContactNumber = "12345").validate().emergencyContactNumber
        )
    }

    @Test
    fun `children may be zero but the party may not be empty`() {
        assertNull(validForm().copy(adults = "2", children = "0").validate().children)
        assertNotNull(validForm().copy(adults = "0", children = "0").validate().adults)
        assertNull(validForm().copy(adults = "0", children = "1").validate().adults)
    }

    /**
     * The mockup pre-fills `0` for the amounts. Those are the values most likely to be submitted
     * untouched, so they're the ones that must not pass.
     */
    @Test
    fun `a zero total does not pass`() {
        assertNotNull(validForm().copy(totalAmount = "0").validate().totalAmount)
    }

    @Test
    fun `paid above total is rejected`() {
        val errors = validForm().copy(totalAmount = "50000", paidAmount = "60000").validate()
        assertNotNull(errors.paidAmount)
    }

    @Test
    fun `a fully paid booking is allowed`() {
        val errors = validForm().copy(totalAmount = "150000", paidAmount = "150000").validate()
        assertTrue(errors.isValid)
    }

    @Test
    fun `transaction id rejects punctuation the backend forbids`() {
        assertNotNull(validForm().copy(transactionId = "TXN 8742").validate().transactionId)
        assertNotNull(validForm().copy(transactionId = "TXN/8742").validate().transactionId)
        assertNull(validForm().copy(transactionId = "TXN_874-291").validate().transactionId)
    }

    @Test
    fun `a booking without a screenshot is blocked`() {
        assertNotNull(validForm().copy(screenshotUri = null).validate().screenshot)
        assertNotNull(validForm().copy(screenshotUri = "  ").validate().screenshot)
    }

    @Test
    fun `end date before start date is rejected`() {
        val errors = validForm().copy(
            startDateMillis = dateMillis(2026, 8, 20),
            endDateMillis = dateMillis(2026, 8, 15),
        ).validate()
        assertNotNull(errors.endDate)
    }

    @Test
    fun `a same-day trip is allowed`() {
        val day = dateMillis(2026, 8, 15)
        val errors = validForm().copy(startDateMillis = day, endDateMillis = day).validate()
        assertTrue(errors.isValid)
    }

    @Test
    fun `amounts tolerate rupee formatting pasted from a quote`() {
        val errors = validForm().copy(totalAmount = "₹1,50,000", paidAmount = "50,000").validate()
        assertNull(errors.totalAmount)
        assertNull(errors.paidAmount)
    }

    @Test
    fun `due amount is total minus paid, floored at zero`() {
        assertEquals(
            100_000L,
            validForm().copy(totalAmount = "150000", paidAmount = "50000").impliedDueAmount,
        )
        // Over-payment is caught by validation; the helper still must not return a negative.
        assertEquals(
            0L,
            validForm().copy(totalAmount = "50000", paidAmount = "60000").impliedDueAmount,
        )
        assertNull(validForm().copy(totalAmount = "", paidAmount = "50000").impliedDueAmount)
    }

    /**
     * The wire shape against the documented FormData keys. Dates go out as `yyyy-MM-dd` (not the
     * `dd-MM-yyyy` the form displays), and the email is lowercased as the backend stores it.
     */
    @Test
    fun `form fields match the documented keys`() {
        val fields = validForm().toFormFields(leadId = "66b60e7f8a12bc0012345678")

        assertEquals("John Doe", fields["travellerName"])
        assertEquals("john.doe@example.com", fields["travellerEmail"])
        assertEquals("9876543210", fields["travellerPhone"])
        assertEquals("2", fields["adults"])
        assertEquals("0", fields["children"])
        assertEquals("Bali Honeymoon Package", fields["packageName"])
        assertEquals("Ubud, Bali, Indonesia", fields["location"])
        assertEquals("2026-08-15", fields["startDate"])
        assertEquals("2026-08-20", fields["endDate"])
        assertEquals("150000", fields["totalAmount"])
        assertEquals("50000", fields["paidAmount"])
        assertEquals("TXN874291857", fields["transactionId"])
        assertEquals("FT HDFC", fields["paymentMode"])
        assertEquals("66b60e7f8a12bc0012345678", fields["leadId"])
    }

    @Test
    fun `email is lowercased for the backend`() {
        val fields = validForm().copy(emailId = "John.Doe@Example.COM").toFormFields()
        assertEquals("john.doe@example.com", fields["travellerEmail"])
    }

    /**
     * The backend derives due from total minus paid and owns every id, status and ownership field.
     * Sending a client-side `dueAmount` or `status` would be a number the server discards at best, and
     * a fight with its "create always starts as Pending" rule at worst.
     */
    @Test
    fun `server-owned fields are never sent`() {
        val fields = validForm().toFormFields(leadId = "abc123")
        listOf("dueAmount", "status", "tripStatus", "bookingId", "paymentId", "createdBy")
            .forEach { key -> assertFalse("$key is server-owned", fields.containsKey(key)) }
    }

    /** An absent lead id must be omitted, not sent blank — a blank would read as "update lead ''". */
    @Test
    fun `a missing lead id is omitted`() {
        assertFalse(validForm().toFormFields(leadId = null).containsKey("leadId"))
        assertFalse(validForm().toFormFields(leadId = "  ").containsKey("leadId"))
    }

    @Test
    fun `every payment mode option matches the documented spelling`() {
        assertEquals(
            listOf(
                "Kalpana BOI", "Kalpana PNB", "Babita AU",
                "Hari Mohan BOB", "FT HDFC", "Pratyush SBI",
            ),
            PaymentMode.entries.map { it.apiValue },
        )
        // Fixed to HDFC because the form no longer lets an agent pick — the Payment Mode field is
        // read-only, so this default is the only value this app ever sends.
        assertEquals("FT HDFC", PaymentMode.DEFAULT.apiValue)
    }

    @Test
    fun `whitespace is trimmed before sending`() {
        val fields = validForm()
            .copy(fullName = "  John Doe  ", location = "  Bali  ", transactionId = " TXN1 ")
            .toFormFields()
        assertEquals("John Doe", fields["travellerName"])
        assertEquals("Bali", fields["location"])
        assertEquals("TXN1", fields["transactionId"])
    }
}
