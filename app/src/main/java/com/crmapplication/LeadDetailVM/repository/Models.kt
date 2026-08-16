package com.crmapplication.LeadDetailVM.repository

import com.crmapplication.LeadDetailVM.local.BugReportEntity
import com.crmapplication.LeadDetailVM.local.CallEntity
import com.crmapplication.LeadDetailVM.local.LeadEntity
import com.crmapplication.LeadDetailVM.local.NoteEntity
import com.crmapplication.LeadDetailVM.local.StatusHistoryEntity
import com.crmapplication.LeadDetailVM.remote.ApiLeadDto
import com.crmapplication.LeadDetailVM.remote.ApiNoteDto
import com.crmapplication.LeadDetailVM.remote.BugReportDto
import com.crmapplication.LeadDetailVM.remote.BugStatus
import com.crmapplication.LeadDetailVM.remote.AuthUser
import com.crmapplication.LeadDetailVM.remote.LeadDto
import com.crmapplication.LeadDetailVM.remote.LongCallDto
import com.crmapplication.LeadDetailVM.remote.MeResponse
import com.crmapplication.LeadDetailVM.remote.NoteDto
import com.crmapplication.calllog.CallLogEntry
import com.crmapplication.calllog.CallType
import com.crmapplication.calllog.normalizedPhoneKey
import java.time.Instant
import kotlin.math.abs

data class Lead(
    val id: String,
    val name: String,
    val phone: String,
    val totalDial: Int = 0,
    val connected: Int = 0,
    val talkTime: String = "",
    val firstCall: String? = null,
    val lastCall: String? = null,
    val labels: List<String> = emptyList(),
    val status: String = DEFAULT_LEAD_STATUS,

    val product: String? = null,

    val source: String? = null,

    /**
     * When the party travels, as the server stores it — a free-form string, usually `yyyy-MM-dd`.
     * Kept verbatim rather than parsed to millis: the web dashboard may write an already-formatted
     * date, and re-formatting a string we can't parse would lose what the agent typed.
     */
    val travelDate: String? = null,

    /** Party size. Null means "not set" (the backend default), which is distinct from 0. */
    val numberOfPersons: Int? = null,

    val statusChangedAt: Long? = null,
    val createdAt: Long,
    val dueDate: Long?,
    val assignedAt: Long? = null,
    val notes: List<Note> = emptyList(),
)

/**
 * Status a lead falls back to when the server sends none. Stays a compile-time constant rather than
 * following `GET /api/config`: it's used in the DTO → domain mapping, which has no config access.
 *
 * The live status list lives in `LeadsUiState.statuses` (see [DEFAULT_LEAD_STATUSES] for the
 * pre-sync fallback).
 */
const val DEFAULT_LEAD_STATUS = "Fresh Leads"

/**
 * The one status that isn't reachable from the plain status dropdown: it requires the booking form
 * (`PUT api/leads/{id}/book`), and once a lead is here this app won't let the status change again.
 */
const val BOOKED_STATUS = "Booked"

/** True when the lead is booked, so its status is locked and the booking form is closed. */
fun Lead.isBooked(): Boolean = status.equals(BOOKED_STATUS, ignoreCase = true)

data class Note(
    val id: String,
    val leadId: String,
    val text: String,
    val timestamp: Long,

    val authorName: String? = null,

    val authorId: String? = null,

    val imageUrl: String? = null,

    val timeLabel: String? = null,
) {

    val hasAttachment: Boolean get() = !imageUrl.isNullOrBlank()

    val isDocument: Boolean
        get() {
            val url = imageUrl?.substringBefore('?')?.lowercase() ?: return false
            return url.endsWith(".pdf") || url.endsWith(".doc") || url.endsWith(".docx") ||
                (!IMAGE_EXTENSIONS.any { url.endsWith(it) } && url.contains("/raw/upload/"))
        }
}

private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp")

data class Profile(
    val id: String,
    val name: String,
    val email: String,
    val isAdmin: Boolean = false,
    val isVerified: Boolean = false,
)

data class StatusChange(
    val id: String,
    val leadId: String,
    val previousStatus: String?,
    val newStatus: String,
    val changedBy: String,
    val changedAt: Long,
)

/**
 * What the server confirmed after a booking was created — the bit worth showing the agent once the
 * form closes.
 *
 * Read back from the response rather than echoed from the form on purpose. The backend owns these
 * numbers: it derives `dueAmount`, and recalculates `paidAmount` from payments that are already
 * **verified**. A deposit submitted moments ago is still `VERIFICATION-REQUIRED`, so [paidAmount] of
 * 0 alongside a non-zero total is the normal, correct result — not a lost payment.
 *
 * Every field is nullable because a booking that saved is a success even if the response was thinner
 * than documented; the UI just falls back to a plain confirmation.
 */
data class BookingReceipt(
    /** The human-readable `FT…` code an agent can quote to the customer. */
    val bookingId: String? = null,
    val totalAmount: Long? = null,
    val paidAmount: Long? = null,
    val dueAmount: Long? = null,
)

/**
 * A bug report filed by an agent. [isSynced] is false while the report exists only on this device —
 * the UI surfaces that so an agent isn't left thinking a report reached the team when it hasn't.
 *
 * [status] is whatever the backend currently says (see `BugStatus`), so an agent can tell a fixed
 * report from one nobody has picked up.
 */
data class BugReport(
    val id: String,
    val title: String,
    val description: String,
    val reporterName: String,
    val reporterId: String? = null,
    val createdAt: Long,
    val isSynced: Boolean = false,
    val status: String = BugStatus.OPEN,
)

data class DashboardStats(
    val date: String,
    val totalDials: Int,
    val totalTalktime: String,
    val connectedCalls: Int,
    val uniqueCalls: Int,
    val callMoreThan: Int,
    val firstCall: String?,
    val lastCall: String?,
    val idleTime: String,
    val attendance: String,
)

data class MonthlyStats(
    val month: String,
    val monthlyTarget: String,
    val bookingCount: String,
    val totalSaleAmount: String,
    val attendance: String,
)

data class DashboardData(
    val daily: DashboardStats,
    val monthly: MonthlyStats,
)

fun LeadDto.toEntity() = LeadEntity(
    id = id,
    name = name,
    phone = phone,
    createdAt = createdAt,
    dueDate = dueDate,
)

fun LeadEntity.toDomain() = Lead(
    id = id,
    name = name,
    phone = phone,
    totalDial = totalDial,
    connected = connected,
    talkTime = talkTime,
    firstCall = firstCall,
    lastCall = lastCall,
    labels = labels,
    status = status,
    product = product,
    source = source,
    travelDate = travelDate,
    numberOfPersons = numberOfPersons,
    statusChangedAt = statusChangedAt,
    createdAt = createdAt,
    dueDate = dueDate,
    assignedAt = assignedAt,
)

fun ApiLeadDto.toEntity(): LeadEntity {
    val stableId = id ?: mongoId ?: leadId?.toString() ?: phone.orEmpty()
    return LeadEntity(
        id = stableId,
        name = name?.trim().orEmpty().ifBlank { phone.orEmpty().ifBlank { "Unknown" } },
        phone = phone.orEmpty(),
        totalDial = booking?.totalDial ?: 0,
        connected = booking?.connected ?: 0,
        talkTime = booking?.talkTime.orEmpty(),
        firstCall = booking?.firstCall,
        lastCall = booking?.lastCall,
        labels = labels.orEmpty(),
        status = status?.takeIf { it.isNotBlank() } ?: DEFAULT_LEAD_STATUS,
        product = product?.trim()?.takeIf { it.isNotBlank() },
        source = leadSource?.trim()?.takeIf { it.isNotBlank() },
        // The backend defaults travelDate to "", so blank and absent both mean "not set" here.
        travelDate = travelDate?.trim()?.takeIf { it.isNotBlank() },
        numberOfPersons = numberOfPersons?.takeIf { it > 0 },
        createdAt = createdAt.toEpochMillisOrNow(),
        // `dates.reminderDate` is the server's copy of the reminder (date + time, set via
        // PUT /api/leads/:id/reminder). Deliberately NOT `dates.dueDate`: that one is date-only and
        // the call-log sync rewrites it on every refresh, so it would stomp the agent's reminder.
        // Null here means "no reminder on the server" — LeadsRepository.syncLeads decides whether
        // that outranks the locally stored value.
        dueDate = dates?.reminderDate.toEpochMillisOrNull(),
    )
}

private fun String?.toEpochMillisOrNow(): Long =
    toEpochMillisOrNull() ?: System.currentTimeMillis()

/** Parses an ISO-8601 instant, or null if absent/unparseable. */
private fun String?.toEpochMillisOrNull(): Long? =
    this?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** Prefix marking a [CallEntity] read from this device's call log. See [CallEntity] for why. */
const val DEVICE_CALL_ID_PREFIX = "d-"

/** Prefix marking a [CallEntity] backfilled from the calls API. */
const val SERVER_CALL_ID_PREFIX = "s-"

/**
 * Persists a device call-log row. [leadId] is null when no assigned lead matched the number yet —
 * `CallDao.attachLeadId` fills it in once the lead syncs.
 */
fun CallLogEntry.toEntity(leadId: String?, isVoicemail: Boolean = false): CallEntity = CallEntity(
    id = deviceCallId(id),
    leadId = leadId,
    phoneKey = number.normalizedPhoneKey(),
    number = number,
    type = type.name,
    dateMillis = dateMillis,
    durationSeconds = durationSeconds,
    isFromServer = false,
    // Passed in, not taken from `this`: the device log has no such concept, so the stored row is the
    // only copy. Callers must supply the existing mark or the upsert would erase it.
    isVoicemail = isVoicemail,
)

/** The `calls.id` for a device call-log row. */
fun deviceCallId(callLogId: Long): String = "$DEVICE_CALL_ID_PREFIX$callLogId"

/**
 * Device call-log ids from stored `calls.id` values, dropping any that aren't device rows.
 *
 * Lets the Dashboard — which computes from the raw call log rather than the `calls` table — match the
 * agent's voicemail marks back onto the entries it read.
 */
fun deviceCallLogIds(storedIds: List<String>): Set<Long> = storedIds
    .filter { it.startsWith(DEVICE_CALL_ID_PREFIX) }
    .mapNotNull { it.removePrefix(DEVICE_CALL_ID_PREFIX).toLongOrNull() }
    .toSet()

/**
 * Applies the agent's stored voicemail marks to entries read straight from the device call log.
 *
 * The Dashboard computes its stats from the call log rather than the `calls` table, so without this it
 * would never see a mark and would keep counting a voicemail's duration as talk time. [storedIds] are
 * raw `calls.id` values from `CallDao.getVoicemailMarkedIds`.
 */
fun List<CallLogEntry>.withVoicemailMarks(storedIds: List<String>): List<CallLogEntry> {
    val marked = deviceCallLogIds(storedIds)
    if (marked.isEmpty()) return this
    return map { if (it.id in marked) it.copy(isVoicemail = true) else it }
}

/**
 * Back to the domain type the call-log code already speaks, so `callStats`, `bookingFromCalls` and
 * the history list work on stored rows exactly as they did on freshly-read ones.
 *
 * [CallLogEntry.id] is a Long, so it is recovered from the numeric suffix of a device id. A server
 * row has no numeric id; it gets a negative synthetic one derived from its string id, which keeps it
 * distinct from every device row (those are positive) and stable across reads — it is only ever used
 * as a list key, never to address the row.
 */
fun CallEntity.toDomain(): CallLogEntry = CallLogEntry(
    id = id.removePrefix(DEVICE_CALL_ID_PREFIX).toLongOrNull()
        ?: -(abs(id.hashCode().toLong()) + 1L),
    number = number,
    // An unrecognised stored value degrades to UNKNOWN rather than throwing — the reason `type` is
    // stored as a name instead of an enum ordinal.
    type = runCatching { CallType.valueOf(type) }.getOrDefault(CallType.UNKNOWN),
    dateMillis = dateMillis,
    durationSeconds = durationSeconds,
    isVoicemail = isVoicemail,
)

/**
 * Best-effort direction for a call the backend reported, which stores an outcome (`Connected` |
 * `Missed` | `Failed` | `Voicemail`) but no direction.
 *
 * The inversion is lossy and deliberately biased toward **outgoing**: this app posts an agent's calls
 * to their assigned leads, which are overwhelmingly dials. Getting it wrong only mislabels the icon on
 * a backfilled row — `countsAsDial` and `talkTimeSeconds` still land correctly, because an outgoing
 * call with a duration counts either way and `Voicemail` maps to the type they both exclude.
 */
private fun callTypeForServerStatus(status: String?): CallType = when (status?.trim()?.lowercase()) {
    "connected" -> CallType.OUTGOING
    "missed" -> CallType.MISSED
    "failed" -> CallType.OUTGOING
    "voicemail" -> CallType.VOICEMAIL
    else -> CallType.UNKNOWN
}

/**
 * Maps one row of `GET api/calls/long-calls` into a storable call.
 *
 * Null when the row can't be placed on a lead's timeline — no `contactNumber` to match, no digits in
 * it, or an unparseable `timestamp`. Dropping those is deliberate: a call with no number or no time
 * can't be shown in history or deduped against a device row.
 *
 * [fallbackLeadId] is used when the payload's own `leadId` is absent, which is the common case for the
 * lead this backfill was requested for.
 */
fun LongCallDto.toEntity(fallbackLeadId: String?): CallEntity? {
    val callNumber = contactNumber?.takeIf { it.isNotBlank() } ?: return null
    val key = callNumber.normalizedPhoneKey()
    if (key.isEmpty()) return null
    val at = timestamp.toEpochMillisOrNull() ?: return null
    // Falls back to a number+instant key so a payload without `_id` still upserts stably instead of
    // inserting a fresh duplicate on every backfill.
    val rowId = id?.takeIf { it.isNotBlank() } ?: "$key-$at"
    return CallEntity(
        id = "$SERVER_CALL_ID_PREFIX$rowId",
        leadId = leadId?.takeIf { it.isNotBlank() } ?: fallbackLeadId,
        phoneKey = key,
        number = callNumber,
        type = callTypeForServerStatus(status).name,
        dateMillis = at,
        durationSeconds = duration ?: 0L,
        isFromServer = true,
    )
}

fun NoteDto.toEntity() = NoteEntity(id, leadId, text, timestamp)

fun NoteEntity.toDomain() = Note(
    id = id,
    leadId = leadId,
    text = text,
    timestamp = timestamp,
    authorName = authorName,
    authorId = authorId,
    imageUrl = imageUrl,
    timeLabel = timeLabel,
)

fun ApiNoteDto.toEntity(leadId: String): NoteEntity {
    val noteId = id ?: mongoId ?: return NoteEntity(
        id = "srv-${System.nanoTime()}", leadId = leadId, text = text.orEmpty(),
    )
    return NoteEntity(
        id = noteId,
        leadId = leadId,
        text = text.orEmpty(),
        timestamp = objectIdToEpochMillis(noteId) ?: System.currentTimeMillis(),
        authorName = author?.trim()?.takeIf { it.isNotBlank() },
        authorId = authorId?.trim()?.takeIf { it.isNotBlank() },
        imageUrl = imageUrl?.trim()?.takeIf { it.isNotBlank() },
        timeLabel = null,
    )
}

private fun objectIdToEpochMillis(id: String): Long? {
    if (id.length < 8) return null
    return runCatching { id.substring(0, 8).toLong(16) * 1000L }.getOrNull()
}

fun BugReportEntity.toDomain() = BugReport(
    id = id,
    title = title,
    description = description,
    reporterName = reporterName,
    reporterId = reporterId,
    createdAt = createdAt,
    isSynced = isSynced,
    status = status,
)

/**
 * Server report → local row.
 *
 * The fallback id for a response missing both `id` and `_id` deliberately has **no hyphen**. The
 * hyphen is load-bearing: `BugReportDao.deleteServerReports` keys off `id NOT LIKE '%-%'` to tell
 * server rows from unsent local ones (which get UUIDs). A `srv-…` id would contain a hyphen, so it
 * would be misread as local, survive every `replaceServerReports`, and pile up a duplicate per sync.
 */
fun BugReportDto.toEntity(): BugReportEntity {
    val reportId = id ?: mongoId ?: "srv${System.nanoTime()}"
    return BugReportEntity(
        id = reportId,
        title = title?.trim().orEmpty().ifBlank { "(no title)" },
        description = description?.trim().orEmpty(),
        reporterName = reporterName?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown agent",
        // `reportedBy` on the wire — not `reporterId`, and not the `authorId` that notes use.
        reporterId = reportedBy?.trim()?.takeIf { it.isNotBlank() },
        createdAt = createdAt.toEpochMillisOrNow(),
        // Came from the server, so by definition every agent can see it.
        isSynced = true,
        // A response without a status is treated as untriaged rather than blank, so the badge can't
        // render empty.
        status = status?.trim()?.takeIf { it.isNotBlank() } ?: BugStatus.OPEN,
    )
}

fun MeResponse.toDomain() = Profile(
    id = id.orEmpty(),
    name = name.orEmpty(),
    email = email.orEmpty(),
    isAdmin = isAdmin,
    isVerified = isVerified,
)

fun AuthUser.toProfile() = Profile(
    id = id.orEmpty(),
    name = name.orEmpty(),
    email = email.orEmpty(),
    isAdmin = isAdmin,
    isVerified = isVerified,
)

fun StatusHistoryEntity.toDomain() =
    StatusChange(id, leadId, previousStatus, newStatus, changedBy, changedAt)
