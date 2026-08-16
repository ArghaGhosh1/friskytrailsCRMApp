package com.crmapplication.LeadDetailVM.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "leads")
data class LeadEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    val totalDial: Int = 0,
    val connected: Int = 0,
    val talkTime: String = "",
    val firstCall: String? = null,
    val lastCall: String? = null,
    val labels: List<String> = emptyList(),
    val status: String = "Fresh Leads",

    val product: String? = null,

    val source: String? = null,

    // Agent-editable lead fields (PUT api/leads/{id}). Free-form string, because the backend schema
    // stores it that way — usually `yyyy-MM-dd`, but the web dashboard can write a formatted date.
    val travelDate: String? = null,

    // Party size. Null means "not set", matching the backend default — distinct from 0.
    val numberOfPersons: Int? = null,

    val statusChangedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val dueDate: Long? = null,

    // Cutoff for call-log analytics: calls before this instant belong to a prior owner and are
    // excluded from history, counts and pushes.
    //
    // Derived from the lead's server `createdAt`, so it survives reinstalls and the destructive
    // migration — a stamp based on "first seen on this device" was reset on every schema bump and
    // silently erased each lead's call history. Only ever moves backwards; see
    // LeadsRepository.syncLeads for the precedence rule and why creation stands in for assignment.
    val assignedAt: Long? = null,
)

/**
 * One call to a lead, persisted so history survives closing the dialog, app restarts and offline.
 *
 * **Deliberately no foreign key to `leads`**, unlike [NoteEntity]. Two reasons: a call can be ingested
 * before its lead exists locally (agent dials a brand-new lead), so [leadId] must be nullable and
 * backfilled later; and `LeadsRepository.syncLeads` prunes leads with `deleteLeadsNotIn`, which under
 * CASCADE would silently destroy the call history of a lead that was briefly absent from one payload.
 * Rows are matched to leads by [phoneKey] instead, which is stable regardless of lead id churn.
 *
 * [id] is prefixed by origin — `d-<callLogId>` for a device row, `s-<serverId>` for one backfilled
 * from the API — so the two sources can never collide on a primary key. Re-reading the device log
 * upserts the same id, which is what lets a duration corrected on hang-up overwrite the earlier 0
 * instead of inserting a duplicate.
 */
@Entity(
    tableName = "calls",
    indices = [Index("leadId"), Index("phoneKey"), Index("dateMillis")],
)
data class CallEntity(
    @PrimaryKey val id: String,
    // Null when no assigned lead matched this number at ingest time.
    val leadId: String? = null,
    // Last-10-digit key (see normalizedPhoneKey) — the join key to a lead's phone.
    val phoneKey: String,
    val number: String,
    // CallType.name. Stored as text, not an enum ordinal, so a reordered enum can't reinterpret
    // existing rows; an unrecognised value degrades to CallType.UNKNOWN on read.
    val type: String,
    val dateMillis: Long,
    val durationSeconds: Long,
    // True for a row backfilled from GET api/calls/long-calls rather than read from this device.
    // Server rows carry no clientCallId, so they are deduped heuristically against device rows.
    val isFromServer: Boolean = false,
    /**
     * Agent-marked: this answered call reached a voicemail machine, not a person.
     *
     * The device call log cannot express this, so it is the one field here the device is NOT the source
     * of truth for — this row is the only copy. `ingestDeviceCalls` must carry it forward when it
     * re-upserts a row, or re-reading the log would silently erase the agent's mark.
     *
     * `defaultValue` is declared so Room's generated CREATE TABLE carries `DEFAULT 0` too. SQLite
     * cannot add a NOT NULL column to a populated table without a default, so `MIGRATION_14_15` must
     * specify one — and Room compares column defaults during its identity check. Declaring it on both
     * sides keeps a migrated database identical to a freshly created one instead of relying on which
     * direction of that comparison Room happens to tolerate.
     */
    @ColumnInfo(defaultValue = "0")
    val isVoicemail: Boolean = false,
)

@Entity(
    tableName = "notes",
    foreignKeys = [ForeignKey(
        entity = LeadEntity::class,
        parentColumns = ["id"],
        childColumns = ["leadId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("leadId")]
)
data class NoteEntity(

    @PrimaryKey val id: String,
    val leadId: String,
    val text: String,

    val timestamp: Long = System.currentTimeMillis(),

    val authorName: String? = null,

    val authorId: String? = null,

    val imageUrl: String? = null,

    val timeLabel: String? = null,
)

@Entity(
    tableName = "status_history",
    foreignKeys = [ForeignKey(
        entity = LeadEntity::class,
        parentColumns = ["id"],
        childColumns = ["leadId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("leadId")]
)
data class StatusHistoryEntity(
    @PrimaryKey val id: String,
    val leadId: String,
    val previousStatus: String?,
    val newStatus: String,
    val changedBy: String,
    val changedAt: Long = System.currentTimeMillis(),
)

/**
 * An agent-filed bug report. Deliberately not tied to a lead, so no foreign key.
 *
 * Reports are visible to every logged-in agent via `GET api/bugs`. [isSynced] false means this row
 * exists only on this device because the push failed — the UI says so rather than implying the team
 * has seen it.
 *
 * Id convention matches notes: a local report gets a UUID (contains '-'), a server one won't, which
 * is what lets [BugReportDao.replaceServerReports] refresh server rows without touching unsent ones.
 */
@Entity(tableName = "bug_reports")
data class BugReportEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val reporterName: String,
    val reporterId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    /**
     * Server-owned triage state. Stored as free text, not an enum, so a status added on the backend
     * shows up instead of failing to parse. Literal default mirrors `BugStatus.OPEN` — spelled out
     * to keep this Room layer free of remote-package imports.
     */
    val status: String = "Open",
)
