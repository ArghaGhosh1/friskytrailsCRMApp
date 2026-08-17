package com.crmapplication

import com.crmapplication.LeadDetailVM.local.LeadEntity
import com.crmapplication.LeadDetailVM.repository.formatIndianAmount
import com.crmapplication.LeadDetailVM.repository.monthlyBookingCount
import com.crmapplication.LeadDetailVM.repository.monthlySaleAmount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the dashboard's monthly sale figure — the "₹booked / ₹target" row on the Monthly card.
 *
 * The sale side is summed on-device from `leads.bookedAmount`, because the booking service has no GET
 * route to ask for it. That makes these two functions the whole of the calculation, so they carry the
 * cases that would otherwise only show up as a wrong number on an agent's dashboard.
 */
class MonthlySaleAmountTest {

    private fun lead(
        name: String,
        bookedAmount: Long? = null,
        bookedAt: Long? = null,
        status: String = "Booked",
        statusChangedAt: Long? = null,
    ) = LeadEntity(
        id = name,
        name = name,
        phone = "9876543210",
        status = status,
        statusChangedAt = statusChangedAt,
        bookedAmount = bookedAmount,
        bookedAt = bookedAt,
    )

    private val startOfMonth = 1_000_000L

    // region summing

    @Test
    fun `bookings inside the month are summed`() {
        val total = monthlySaleAmount(
            listOf(
                lead("a", bookedAmount = 150_000, bookedAt = startOfMonth),
                lead("b", bookedAmount = 50_000, bookedAt = startOfMonth + 500),
            ),
            startOfMonth,
        )
        assertEquals(200_000L, total)
    }

    /** Last month's sales belong to last month — the figure resets when the month rolls over. */
    @Test
    fun `a booking before the month start is excluded`() {
        val total = monthlySaleAmount(
            listOf(
                lead("last month", bookedAmount = 900_000, bookedAt = startOfMonth - 1),
                lead("this month", bookedAmount = 25_000, bookedAt = startOfMonth),
            ),
            startOfMonth,
        )
        assertEquals(25_000L, total)
    }

    /** The boundary itself counts as inside: `bookedAt >= startOfMonth`. */
    @Test
    fun `a booking exactly on the month start counts`() {
        assertEquals(
            7_000L,
            monthlySaleAmount(listOf(lead("a", bookedAmount = 7_000, bookedAt = startOfMonth)), startOfMonth),
        )
    }

    /** A lead with no booking trace at all contributes nothing, whatever else it carries. */
    @Test
    fun `a lead with no booking stamp is ignored`() {
        val leads = listOf(
            lead("never booked", status = "Fresh Leads"),
            lead("no stamps at all", bookedAmount = 500_000, bookedAt = null, statusChangedAt = null),
        )
        assertEquals(0L, monthlySaleAmount(leads, startOfMonth))
        assertEquals(0, monthlyBookingCount(leads, startOfMonth))
    }

    /**
     * A lead booked before `bookedAt` shipped (schema 16) has only its status-change time. It counts as
     * a booking, contributing ₹0 — the amount was never recorded and there's no route to recover it, so
     * a known booking of unknown value is the honest reading.
     */
    @Test
    fun `a pre-schema-16 booking falls back to its status change time`() {
        val leads = listOf(lead("old booking", bookedAt = null, statusChangedAt = startOfMonth + 10))
        assertEquals(1, monthlyBookingCount(leads, startOfMonth))
        assertEquals(0L, monthlySaleAmount(leads, startOfMonth))
    }

    /** The fallback is gated on Booked: elsewhere `statusChangedAt` is just the last status edit. */
    @Test
    fun `an unbooked lead's status change time is not treated as a booking`() {
        val leads = listOf(
            lead("moved to prospect", status = "Prospect Leads", statusChangedAt = startOfMonth + 10),
        )
        assertEquals(0, monthlyBookingCount(leads, startOfMonth))
        assertEquals(0L, monthlySaleAmount(leads, startOfMonth))
    }

    /** The real stamp wins when both exist, so a re-synced status edit can't move a booking's month. */
    @Test
    fun `bookedAt takes precedence over the fallback`() {
        val leads = listOf(
            lead("a", bookedAmount = 5_000, bookedAt = startOfMonth - 1, statusChangedAt = startOfMonth + 999),
        )
        assertEquals(0, monthlyBookingCount(leads, startOfMonth))
        assertEquals(0L, monthlySaleAmount(leads, startOfMonth))
    }

    /** Server confirmed the booking but sent no total: a real sale, contributing zero rupees. */
    @Test
    fun `a stamped booking with no amount counts as zero rather than being skipped`() {
        val total = monthlySaleAmount(
            listOf(
                lead("no amount", bookedAmount = null, bookedAt = startOfMonth),
                lead("with amount", bookedAmount = 1_000, bookedAt = startOfMonth),
            ),
            startOfMonth,
        )
        assertEquals(1_000L, total)
    }

    /**
     * Filtering is by stamp, not by current status. Money booked this month stays in this month's
     * total even if an admin later moves the lead out of `Booked`.
     */
    @Test
    fun `a booking still counts after the status moves away from Booked`() {
        val total = monthlySaleAmount(
            listOf(lead("moved", bookedAmount = 80_000, bookedAt = startOfMonth, status = "Rejected Leads")),
            startOfMonth,
        )
        assertEquals(80_000L, total)
    }

    @Test
    fun `no leads is zero, not a crash`() {
        assertEquals(0L, monthlySaleAmount(emptyList(), startOfMonth))
        assertEquals(0, monthlyBookingCount(emptyList(), startOfMonth))
    }

    // endregion

    // region booking count

    @Test
    fun `the count covers this month's bookings only`() {
        val leads = listOf(
            lead("this month", bookedAmount = 10_000, bookedAt = startOfMonth + 1),
            lead("also this month", bookedAmount = 20_000, bookedAt = startOfMonth + 2),
            lead("last month", bookedAmount = 90_000, bookedAt = startOfMonth - 1),
            lead("open", status = "Fresh Leads"),
        )
        assertEquals(2, monthlyBookingCount(leads, startOfMonth))
        assertEquals(30_000L, monthlySaleAmount(leads, startOfMonth))
    }

    /**
     * The behaviour that makes this a *monthly* card: a lead booked last month keeps its `Booked`
     * status forever, but must not be counted once the month rolls over. Both figures reset together.
     *
     * This is what the old implementation got wrong — it counted every `Booked` lead ever held, so the
     * number only ever grew.
     */
    @Test
    fun `both figures reset when the month rolls over`() {
        val booked = listOf(lead("won", bookedAmount = 250_000, bookedAt = startOfMonth + 5))

        assertEquals(1, monthlyBookingCount(booked, startOfMonth))
        assertEquals(250_000L, monthlySaleAmount(booked, startOfMonth))

        // Same leads, viewed from the following month — the booking is now history.
        val nextMonth = startOfMonth + 100_000
        assertEquals(0, monthlyBookingCount(booked, nextMonth))
        assertEquals(0L, monthlySaleAmount(booked, nextMonth))
    }

    /** Counted as a booking even though it contributes no rupees — the two figures differ here. */
    @Test
    fun `a booking with no amount still counts toward the count`() {
        val leads = listOf(lead("no total", bookedAmount = null, bookedAt = startOfMonth))
        assertEquals(1, monthlyBookingCount(leads, startOfMonth))
        assertEquals(0L, monthlySaleAmount(leads, startOfMonth))
    }

    // endregion

    // region formatting

    /** Indian grouping: last three digits, then pairs. This is what the backend shows an admin. */
    @Test
    fun `amounts group the Indian way`() {
        assertEquals("₹1,50,000", formatIndianAmount(150_000))
        assertEquals("₹5,00,000", formatIndianAmount(500_000))
        assertEquals("₹1,00,00,000", formatIndianAmount(10_000_000))
        assertEquals("₹12,34,567", formatIndianAmount(1_234_567))
    }

    @Test
    fun `short amounts are left ungrouped`() {
        assertEquals("₹0", formatIndianAmount(0))
        assertEquals("₹999", formatIndianAmount(999))
        assertEquals("₹1,000", formatIndianAmount(1_000))
    }

    /** A target the metrics API never sent reads as ₹0, which is the honest rendering of "unknown". */
    @Test
    fun `zero renders as a rupee amount rather than blank`() {
        assertEquals("₹0", formatIndianAmount(0))
    }

    // endregion
}
