package com.crmapplication.calllog

import android.provider.CallLog

data class CallLogEntry(
    val id: Long,
    val number: String,
    val type: CallType,
    val dateMillis: Long,
    val durationSeconds: Long,
    /**
     * Agent-marked: this answered call actually reached a voicemail machine, not a person.
     *
     * Android's call log cannot express this — a machine that picks up looks exactly like a human who
     * picks up ([CallType.OUTGOING] with a duration), so only the agent knows. Marked per call from the
     * lead's call-history dialog, never inferred and never applied to a number as a whole: the same
     * lead can go to voicemail once and answer the next time.
     *
     * Distinct from [CallType.VOICEMAIL], which is the provider's own row type. This flag removes the
     * call's [talkTimeSeconds] and its connected status but deliberately leaves [countsAsDial] intact —
     * the agent did place the call, so they keep credit for the attempt.
     */
    val isVoicemail: Boolean = false,
)

enum class CallType {
    INCOMING,
    OUTGOING,
    MISSED,
    VOICEMAIL,
    REJECTED,
    BLOCKED,
    UNKNOWN;

    val label: String
        get() = when (this) {
            INCOMING -> "Incoming"
            OUTGOING -> "Dialed"
            MISSED -> "Missed"
            VOICEMAIL -> "Voicemail"
            REJECTED -> "Rejected"
            BLOCKED -> "Blocked"
            UNKNOWN -> "Call"
        }

    val icon: String
        get() = when (this) {
            INCOMING -> "📥"
            OUTGOING -> "📤"
            MISSED -> "📵"
            VOICEMAIL -> "📨"
            REJECTED -> "🚫"
            BLOCKED -> "⛔"
            UNKNOWN -> "📞"
        }

    companion object {
        fun fromProviderType(value: Int): CallType = when (value) {
            CallLog.Calls.INCOMING_TYPE -> INCOMING
            CallLog.Calls.OUTGOING_TYPE -> OUTGOING
            CallLog.Calls.MISSED_TYPE -> MISSED
            CallLog.Calls.VOICEMAIL_TYPE -> VOICEMAIL
            CallLog.Calls.REJECTED_TYPE -> REJECTED
            CallLog.Calls.BLOCKED_TYPE -> BLOCKED
            else -> UNKNOWN
        }
    }
}

data class NumberCallStats(
    val totalCalls: Int,
    val dialedCount: Int,
    val incomingCount: Int,
    val missedCount: Int,
    val totalDurationSeconds: Long,
    val outgoingDurationSeconds: Long,
    val incomingDurationSeconds: Long,
) {
    val hasCalls: Boolean get() = totalCalls > 0
}

/**
 * Whether this call counts toward an agent's dial figures.
 *
 * The rule, which the Dashboard tile and the numbers pushed to the backend both read from here so
 * they can't drift apart:
 * - **Outgoing** always counts. The agent made the attempt, whether or not anyone picked up.
 * - **Incoming** counts only when it was actually answered, which is what a non-zero duration means.
 * - **Everything else does not** — missed, rejected and blocked calls took no effort from the agent,
 *   so counting them would inflate the figure with calls they never handled.
 *
 * Direction-specific reporting is separate: [callStats] keeps its own outgoing/incoming/missed
 * breakdown for the lead-detail screen, where the counts sit side by side and must not overlap.
 */
val CallLogEntry.countsAsDial: Boolean
    get() = when (type) {
        CallType.OUTGOING -> true
        CallType.INCOMING -> durationSeconds > 0
        else -> false
    }

/**
 * The part of this call's duration that counts as the agent actually talking to someone.
 *
 * Zero unless the call [countsAsDial], which makes talk time exactly "time on calls the agent
 * handled". **Voicemail is the case this exists for**: a voicemail carries a real duration, so
 * summing raw `durationSeconds` credited the agent with talk time for a recording nobody answered —
 * while the same voicemail contributed 0 to the dial and connected counts, so one call could read as
 * "0 dials, 30s talked". Missed, rejected and blocked calls are excluded by the same rule; they are
 * zero-duration in practice, so this only ever removes voicemail time.
 *
 * Every talk-time figure derives from this one property — the Dashboard tile, the numbers pushed to
 * `PUT api/leads/{id}/booking`, the lead-detail total, and the idle gap — so they cannot drift apart.
 * The raw `duration` POSTed to `api/calls` is deliberately left alone: that call carries
 * `status = "Voicemail"` too, so the backend can do its own exclusion without losing the real length.
 */
val CallLogEntry.talkTimeSeconds: Long
    get() = if (countsAsDial && !isVoicemail) durationSeconds else 0L

/**
 * Whether this call reached an actual person, which is what the Dashboard's "Connected Calls" counts.
 *
 * Derived from [talkTimeSeconds] rather than raw duration, so an agent-marked voicemail stops counting
 * as connected — it kept a duration but nobody answered. Always a subset of [countsAsDial], so
 * Connected can never print higher than Total Dial.
 */
val CallLogEntry.countsAsConnected: Boolean
    get() = countsAsDial && talkTimeSeconds > 0

/**
 * Whether the agent can mark this call as a voicemail from the call-history dialog.
 *
 * The single source for that decision: the dialog's explanatory hint and the per-call checkbox both
 * read it, so the hint can never promise a control that no row renders. Showing the hint
 * unconditionally was exactly that bug — on a lead whose every call was zero-duration it told the
 * agent to "tick any call" above a list with nothing tickable, which reads as the feature being
 * missing rather than inapplicable.
 *
 * Three conditions:
 * - **A real duration.** A voicemail reaches a machine that picks up, so it always has one. A 0s call
 *   connected to nothing, already contributes 0 [talkTimeSeconds] and isn't [countsAsConnected], so
 *   marking it could not change a single figure.
 * - **A device-sourced id.** Server-backfilled rows carry a synthetic negative id that can't be
 *   written back (`CallLogSyncRepository.setCallVoicemail` returns false for them).
 * - **Not already [CallType.VOICEMAIL].** The provider has itself classified that row; the agent's
 *   manual mark exists only for calls Android reports as ordinary answered ones.
 *
 * Gated on **raw** `durationSeconds`, deliberately not on [talkTimeSeconds] or [countsAsConnected]:
 * marking a call zeroes both, so gating on them would make the checkbox vanish the instant it was
 * used and leave a mis-mark impossible to undo.
 */
fun CallLogEntry.canBeMarkedVoicemail(): Boolean =
    id >= 0 && durationSeconds > 0 && type != CallType.VOICEMAIL

fun callStats(calls: List<CallLogEntry>): NumberCallStats = NumberCallStats(
    totalCalls = calls.size,
    dialedCount = calls.count { it.type == CallType.OUTGOING },
    incomingCount = calls.count { it.type == CallType.INCOMING },
    missedCount = calls.count { it.type == CallType.MISSED },
    // Sums talk time, not raw duration, so this equals outgoing + incoming below instead of
    // exceeding them by the voicemail time that has no chip of its own on the lead-detail screen.
    totalDurationSeconds = calls.sumOf { it.talkTimeSeconds },
    // Also talk time, not raw duration, so these stay consistent with the total above: an
    // agent-marked voicemail must not keep contributing to the Outgoing chip after being dropped
    // from the overall figure, or the chips would visibly fail to add up to it.
    outgoingDurationSeconds = calls.filter { it.type == CallType.OUTGOING }.sumOf { it.talkTimeSeconds },
    incomingDurationSeconds = calls.filter { it.type == CallType.INCOMING }.sumOf { it.talkTimeSeconds },
)
