package com.crmapplication

import com.crmapplication.LeadDetailVM.repository.idleSecondsSinceLastCall
import com.crmapplication.calllog.CallLogEntry
import com.crmapplication.calllog.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Idle Time answers "how long has the agent been off the phone right now", measured from the end of
 * their most recent call today to the current moment.
 *
 * It deliberately does NOT sum the gaps between earlier calls: that reported downtime already banked,
 * so the tile stayed frozen while the agent stopped calling — precisely when idle should be climbing.
 */
class IdleTimeTest {

    private val now = 1_800_000_000_000L

    private fun call(
        id: Long = 1L,
        type: CallType = CallType.OUTGOING,
        startedAgoSeconds: Long,
        durationSeconds: Long,
    ) = CallLogEntry(
        id = id,
        number = "9001622113",
        type = type,
        dateMillis = now - startedAgoSeconds * 1000,
        durationSeconds = durationSeconds,
    )

    @Test
    fun `no calls today means no idle figure to show`() {
        // Null so the tile reads "—" rather than implying a measured idle stretch. Scoping to today is
        // what stops "hasn't called since yesterday" from printing a huge overnight number.
        assertNull(idleSecondsSinceLastCall(emptyList(), now))
    }

    @Test
    fun `idle runs from the end of the only call to now`() {
        // Started 10 min ago, talked 2 min -> ended 8 min ago.
        val calls = listOf(call(startedAgoSeconds = 600, durationSeconds = 120))
        assertEquals(480L, idleSecondsSinceLastCall(calls, now))
    }

    @Test
    fun `only the most recent call matters, not the gaps between earlier ones`() {
        val calls = listOf(
            call(id = 1, startedAgoSeconds = 3_600, durationSeconds = 60),
            call(id = 2, startedAgoSeconds = 300, durationSeconds = 60),
        )
        // Last call ended 240s ago. The 55-minute gap before it is history, not current idle.
        assertEquals(240L, idleSecondsSinceLastCall(calls, now))
    }

    @Test
    fun `a single call still yields idle`() {
        // The previous metric returned nothing until there were two calls, so an agent who made one
        // call and stopped showed no idle at all.
        val calls = listOf(call(startedAgoSeconds = 90, durationSeconds = 30))
        assertEquals(60L, idleSecondsSinceLastCall(calls, now))
    }

    @Test
    fun `a voicemail is not time on the phone, so idle runs from when it arrived`() {
        // talkTimeSeconds is 0 for voicemail, matching its exclusion from total talk time.
        val calls = listOf(
            call(type = CallType.VOICEMAIL, startedAgoSeconds = 600, durationSeconds = 300),
        )
        assertEquals(600L, idleSecondsSinceLastCall(calls, now))
    }

    @Test
    fun `idle is measured from the latest ending call, not the latest starting one`() {
        val calls = listOf(
            // Started earlier but ran long: ended 10s ago.
            call(id = 1, startedAgoSeconds = 600, durationSeconds = 590),
            // Started later but ended sooner: ended 299s ago.
            call(id = 2, startedAgoSeconds = 300, durationSeconds = 1),
        )
        assertEquals(10L, idleSecondsSinceLastCall(calls, now))
    }

    @Test
    fun `a future-stamped call reads as zero idle rather than negative`() {
        // Skewed device clock, or a call still in progress.
        val calls = listOf(call(startedAgoSeconds = -60, durationSeconds = 0))
        assertEquals(0L, idleSecondsSinceLastCall(calls, now))
    }
}
