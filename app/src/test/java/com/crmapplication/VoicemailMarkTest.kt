package com.crmapplication

import com.crmapplication.LeadDetailVM.repository.deviceCallId
import com.crmapplication.LeadDetailVM.repository.deviceCallLogIds
import com.crmapplication.LeadDetailVM.repository.withVoicemailMarks
import com.crmapplication.calllog.CallLogEntry
import com.crmapplication.calllog.CallType
import com.crmapplication.calllog.canBeMarkedVoicemail
import com.crmapplication.calllog.talkTimeSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plumbing that carries an agent's voicemail mark across the device-log boundary.
 *
 * This matters because the mark is the one piece of call data the device is NOT the source of truth
 * for — the `calls` row is its only copy. Two places would silently erase or ignore it:
 *  - `ingestDeviceCalls` re-upserts rows read from the log, so it must carry the mark forward.
 *  - the Dashboard computes from the raw call log, not the `calls` table, so it must re-apply marks
 *    or it would keep counting a marked voicemail's duration as talk time.
 */
class VoicemailMarkTest {

    private fun entry(id: Long, durationSeconds: Long = 120L) = CallLogEntry(
        id = id,
        number = "9001622113",
        type = CallType.OUTGOING,
        dateMillis = 1_700_000_000_000L,
        durationSeconds = durationSeconds,
    )

    @Test
    fun `a device call id round-trips`() {
        assertEquals(setOf(42L), deviceCallLogIds(listOf(deviceCallId(42L))))
    }

    @Test
    fun `server-backfilled ids are not device ids`() {
        // Server rows carry a synthetic negative domain id, so there is no call-log row to mark.
        // Passing one through must yield nothing rather than a wrong device id.
        assertEquals(emptySet<Long>(), deviceCallLogIds(listOf("s-64f1a2b3c4d5e6f7a8b9c0d1")))
    }

    @Test
    fun `malformed stored ids are skipped rather than throwing`() {
        val ids = listOf(deviceCallId(7L), "d-not-a-number", "", "s-abc")
        assertEquals(setOf(7L), deviceCallLogIds(ids))
    }

    @Test
    fun `marks are applied to matching call-log entries only`() {
        val calls = listOf(entry(1L), entry(2L), entry(3L))

        val marked = calls.withVoicemailMarks(listOf(deviceCallId(2L)))

        assertFalse(marked[0].isVoicemail)
        assertTrue("only the marked call", marked[1].isVoicemail)
        assertFalse(marked[2].isVoicemail)
    }

    @Test
    fun `applying marks removes exactly that call's talk time`() {
        // This is the Dashboard path: total talktime is summed from talkTimeSeconds after marks land.
        val calls = listOf(entry(1L, durationSeconds = 120L), entry(2L, durationSeconds = 300L))

        val marked = calls.withVoicemailMarks(listOf(deviceCallId(2L)))

        assertEquals("120s remains, the 300s marked call is discounted", 120L, marked.sumOf { it.talkTimeSeconds })
    }

    @Test
    fun `no marks leaves the list untouched`() {
        val calls = listOf(entry(1L), entry(2L))
        assertEquals(calls, calls.withVoicemailMarks(emptyList()))
    }

    @Test
    fun `a mark for a call no longer in the log is simply ignored`() {
        // The agent cleared their call history after marking; nothing to apply, and no crash.
        val calls = listOf(entry(1L))
        val marked = calls.withVoicemailMarks(listOf(deviceCallId(999L)))
        assertFalse(marked.single().isVoicemail)
        assertEquals(120L, marked.single().talkTimeSeconds)
    }

    // canBeMarkedVoicemail — the single gate the dialog's hint AND its per-call checkbox both read.
    // Tested because the two used to decide independently: the hint rendered unconditionally while the
    // checkbox required a duration, so a lead whose calls were all 0s showed "tick any call that went
    // to voicemail" above a list with nothing tickable, which reads as the feature being absent.

    @Test
    fun `an answered outgoing call can be marked`() {
        assertTrue(entry(1L, durationSeconds = 30L).canBeMarkedVoicemail())
    }

    @Test
    fun `a zero-duration call cannot be marked`() {
        // Nobody and nothing picked up, so there is no talk time to discount — marking it could not
        // change any figure. This is the case that made the feature look missing on a real device.
        assertFalse(entry(1L, durationSeconds = 0L).canBeMarkedVoicemail())
    }

    @Test
    fun `a server-backfilled call cannot be marked`() {
        // Synthetic negative id: there is no call-log row to write the mark against.
        assertFalse(entry(-5L, durationSeconds = 60L).canBeMarkedVoicemail())
    }

    @Test
    fun `a call the provider already classified as voicemail cannot be marked`() {
        val providerVoicemail = entry(1L, durationSeconds = 45L).copy(type = CallType.VOICEMAIL)
        assertFalse(providerVoicemail.canBeMarkedVoicemail())
    }

    @Test
    fun `an already-marked call can still be unmarked`() {
        // The gate reads raw duration, not talk time. Marking zeroes talk time, so gating on that
        // would make the checkbox disappear the moment it was ticked and strand a mis-mark.
        val marked = entry(1L, durationSeconds = 90L).copy(isVoicemail = true)
        assertEquals(0L, marked.talkTimeSeconds)
        assertTrue("the control must survive its own use", marked.canBeMarkedVoicemail())
    }
}
