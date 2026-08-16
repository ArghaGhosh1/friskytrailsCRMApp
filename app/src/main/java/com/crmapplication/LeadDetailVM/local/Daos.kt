package com.crmapplication.LeadDetailVM.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LeadDao {
    @Query("SELECT * FROM leads ORDER BY createdAt DESC")
    fun getAllLeads(): Flow<List<LeadEntity>>

    @Query("SELECT * FROM leads WHERE id = :id")
    suspend fun getLeadById(id: String): LeadEntity?

    @Upsert
    suspend fun upsertLeads(leads: List<LeadEntity>)

    @Upsert
    suspend fun upsertLead(lead: LeadEntity)

    @Query("UPDATE leads SET dueDate = :dueDate WHERE id = :leadId")
    suspend fun updateDueDate(leadId: String, dueDate: Long?)

    @Query("UPDATE leads SET status = :status, statusChangedAt = :changedAt WHERE id = :leadId")
    suspend fun updateStatus(leadId: String, status: String, changedAt: Long)

    // Targeted rather than an upsert of the whole row: a concurrent syncLeads writing the same lead
    // shouldn't have its other columns clobbered by a stale copy read before the edit.
    @Query(
        "UPDATE leads SET name = :name, travelDate = :travelDate, " +
            "numberOfPersons = :numberOfPersons WHERE id = :leadId"
    )
    suspend fun updateLeadInfo(
        leadId: String,
        name: String,
        travelDate: String?,
        numberOfPersons: Int?,
    )

    @Query("DELETE FROM leads WHERE id NOT IN (:keepIds)")
    suspend fun deleteLeadsNotIn(keepIds: List<String>)

    @Query("DELETE FROM leads")
    suspend fun deleteAll()
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE leadId = :leadId ORDER BY timestamp DESC")
    fun getNotesForLead(leadId: String): Flow<List<NoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotes(notes: List<NoteEntity>)

    @Delete
    suspend fun deleteNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNoteById(id: String)

    @Query("DELETE FROM notes WHERE leadId = :leadId AND id NOT LIKE '%-%'")
    suspend fun deleteServerNotesForLead(leadId: String)

    @Transaction
    suspend fun replaceServerNotes(leadId: String, serverNotes: List<NoteEntity>) {
        deleteServerNotesForLead(leadId)
        if (serverNotes.isNotEmpty()) insertNotes(serverNotes)
    }
}

/**
 * Persisted call history. Queries key off `phoneKey` rather than `leadId` because a call can be
 * ingested before its lead exists locally, and because a lead's id can churn while its number
 * doesn't (see [CallEntity]).
 *
 * Every read takes a `since` cutoff — the lead's `assignedAt` — so pre-assignment calls stay out of
 * history and counts. Pass 0 to mean "no cutoff".
 */
@Dao
interface CallDao {

    @Query(
        "SELECT * FROM calls WHERE phoneKey = :phoneKey AND dateMillis >= :since " +
            "ORDER BY dateMillis DESC"
    )
    fun observeCallsForNumber(phoneKey: String, since: Long): Flow<List<CallEntity>>

    @Query(
        "SELECT * FROM calls WHERE phoneKey = :phoneKey AND dateMillis >= :since " +
            "ORDER BY dateMillis DESC"
    )
    suspend fun getCallsForNumber(phoneKey: String, since: Long): List<CallEntity>

    // Upsert, not insert: re-reading a device row must correct a duration that was still 0 when the
    // row was first seen, rather than inserting a second copy of the same call.
    @Upsert
    suspend fun upsertCalls(calls: List<CallEntity>)

    @Query("SELECT COUNT(*) FROM calls WHERE phoneKey = :phoneKey AND dateMillis >= :since")
    suspend fun countForNumber(phoneKey: String, since: Long): Int

    @Query("DELETE FROM calls WHERE phoneKey = :phoneKey AND isFromServer = 1")
    suspend fun deleteServerCallsForNumber(phoneKey: String)

    /** Backfills [leadId] onto rows ingested before the lead was known locally. */
    @Query("UPDATE calls SET leadId = :leadId WHERE phoneKey = :phoneKey AND leadId IS NULL")
    suspend fun attachLeadId(phoneKey: String, leadId: String)

    /** Sets or clears the agent's "this call reached a machine" mark on one call. */
    @Query("UPDATE calls SET isVoicemail = :isVoicemail WHERE id = :callId")
    suspend fun setVoicemail(callId: String, isVoicemail: Boolean)

    /**
     * Ids of every call the agent has marked as voicemail.
     *
     * Two callers, both load-bearing. `ingestDeviceCalls` re-reads them so its upsert carries the mark
     * forward instead of resetting it, and the Dashboard — which computes from the device call log, not
     * from this table — reads them to subtract those calls from talk time.
     */
    @Query("SELECT id FROM calls WHERE isVoicemail = 1")
    suspend fun getVoicemailMarkedIds(): List<String>

    /**
     * Replaces the server-sourced rows for one number, leaving device-sourced rows untouched —
     * the same server-vs-local split [NoteDao.replaceServerNotes] uses.
     */
    @Transaction
    suspend fun replaceServerCalls(phoneKey: String, serverCalls: List<CallEntity>) {
        deleteServerCallsForNumber(phoneKey)
        if (serverCalls.isNotEmpty()) upsertCalls(serverCalls)
    }

    @Query("DELETE FROM calls")
    suspend fun deleteAll()
}

@Dao
interface StatusHistoryDao {

    @Query("SELECT * FROM status_history WHERE leadId = :leadId ORDER BY changedAt DESC")
    fun getHistoryForLead(leadId: String): Flow<List<StatusHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: StatusHistoryEntity)
}

@Dao
interface BugReportDao {

    @Query("SELECT * FROM bug_reports ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BugReportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: BugReportEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reports: List<BugReportEntity>)

    @Query("DELETE FROM bug_reports WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM bug_reports WHERE id NOT LIKE '%-%'")
    suspend fun deleteServerReports()

    /**
     * Swaps in a fresh server list while leaving unsent local reports (UUID ids) alone, so a sync
     * can't silently drop a report the agent filed offline. Mirrors `NoteDao.replaceServerNotes`.
     */
    @Transaction
    suspend fun replaceServerReports(serverReports: List<BugReportEntity>) {
        deleteServerReports()
        if (serverReports.isNotEmpty()) insertAll(serverReports)
    }
}
