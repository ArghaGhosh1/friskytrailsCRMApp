package com.crmapplication

import com.crmapplication.LeadDetailVM.repository.CALL_SETTLE_MS
import com.crmapplication.LeadDetailVM.repository.CallSyncAction
import com.crmapplication.LeadDetailVM.repository.callSyncAction
import com.crmapplication.calllog.CallLogEntry
import com.crmapplication.calllog.CallType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the three-way split that decides whether a call is posted, kept owed, or forgotten.
 *
 * The distinction that matters is RETRY_LATER vs DISCARD. A single "seen" cursor used to collapse
 * them: every non-qualifying call advanced it, so "skipped because its lead hadn't synced yet" was
 * indistinguishable from "already sent" and the call was never reconsidered — the agent's dial count
 * silently under-reported. Anything that could still become postable must return RETRY_LATER.
 */
class CallSyncActionTest {

    private val now = 1_800_000_000_000L
    private val dayMs = 86_400_000L
    private val windowStart = now - 2 * dayMs
    private val windowEnd = now + dayMs

    private fun entry(
        type: CallType = CallType.OUTGOING,
        durationSeconds: Long = 42L,
        dateMillis: Long = now - 60_000L,
    ) = CallLogEntry(
        id = 1L,
        number = "9001622113",
        type = type,
        dateMillis = dateMillis,
        durationSeconds = durationSeconds,
    )

    private fun action(
        entry: CallLogEntry,
        assignedAt: Long? = null,
        isLeadCall: Boolean = true,
    ) = callSyncAction(
        entry = entry,
        now = now,
        windowStart = windowStart,
        windowEnd = windowEnd,
        assignedAt = assignedAt,
        isLeadCall = isLeadCall,
    )

    @Test
    fun `a settled in-window call to an assigned lead is posted`() {
        assertEquals(CallSyncAction.POST, action(entry()))
    }

    @Test
    fun `a null cutoff means no restriction`() {
        assertEquals(CallSyncAction.POST, action(entry(), assignedAt = null))
    }

    @Test
    fun `a call at the cutoff instant counts as after it`() {
        val at = now - 60_000L
        assertEquals(CallSyncAction.POST, action(entry(dateMillis = at), assignedAt = at))
    }

    @Test
    fun `types with no backend status are discarded`() {
        // callStatusFor returns null for these, so there is nothing valid to send — ever.
        assertEquals(CallSyncAction.DISCARD, action(entry(type = CallType.BLOCKED)))
        assertEquals(CallSyncAction.DISCARD, action(entry(type = CallType.UNKNOWN)))
    }

    @Test
    fun `a call older than the reporting window is discarded`() {
        // windowStart only moves forward, so this can never come back into range.
        assertEquals(CallSyncAction.DISCARD, action(entry(dateMillis = windowStart - 1)))
    }

    /** The regression this split exists for: the lead may simply not have synced yet. */
    @Test
    fun `a call to a number with no assigned lead is kept owed, not dropped`() {
        assertEquals(CallSyncAction.RETRY_LATER, action(entry(), isLeadCall = false))
    }

    @Test
    fun `a call before its lead's cutoff is kept owed`() {
        // assignedAt takes the earliest value ever seen, so the cutoff can still move backwards.
        assertEquals(
            CallSyncAction.RETRY_LATER,
            action(entry(dateMillis = now - 5_000L), assignedAt = now - 1_000L),
        )
    }

    @Test
    fun `a future-stamped call is kept owed until the window catches up`() {
        assertEquals(CallSyncAction.RETRY_LATER, action(entry(dateMillis = windowEnd + 1_000L)))
    }

    @Test
    fun `a call whose duration is still settling is kept owed`() {
        // Android writes the row at call start and rewrites DURATION on hang-up; posting now would
        // record a real conversation as a zero-length Failed call, and $setOnInsert makes that final.
        assertEquals(
            CallSyncAction.RETRY_LATER,
            action(entry(durationSeconds = 0L, dateMillis = now - 2_000L)),
        )
    }

    @Test
    fun `a zero-duration dial that has stopped changing is posted`() {
        assertEquals(
            CallSyncAction.POST,
            action(entry(durationSeconds = 0L, dateMillis = now - CALL_SETTLE_MS - 1)),
        )
    }

    @Test
    fun `an unpostable type outranks a retryable reason`() {
        // A blocked call to a not-yet-synced lead is still unpostable, so it must not be owed forever.
        assertEquals(
            CallSyncAction.DISCARD,
            action(entry(type = CallType.BLOCKED), isLeadCall = false),
        )
    }
}
