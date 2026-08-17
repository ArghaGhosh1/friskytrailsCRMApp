package com.crmapplication.utils

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.callSyncDataStore by preferencesDataStore(name = "call_sync")

@Singleton
class CallSyncStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private val WATERMARK_KEY = longPreferencesKey("last_logged_call_id")
        private val INSTALL_ID_KEY = stringPreferencesKey("install_id")
        private val PENDING_KEY = stringSetPreferencesKey("pending_call_ids")
        const val NO_WATERMARK = -1L

        /**
         * Ceiling on the pending set, so a pathological device can't grow this preference without
         * bound. The reporting-window prune normally keeps the set to a few days of calls, well under
         * this; if it is ever hit, the newest ids are kept because those are the ones still inside the
         * window and therefore still postable.
         */
        const val MAX_PENDING = 500
    }

    suspend fun getWatermark(): Long =
        context.callSyncDataStore.data.first()[WATERMARK_KEY] ?: NO_WATERMARK

    suspend fun setWatermark(id: Long) {
        context.callSyncDataStore.edit { it[WATERMARK_KEY] = id }
    }

    /**
     * Device call-log ids that were seen but not posted for a reason that may resolve later — the
     * lead hadn't synced yet, the call predated a cutoff that can still move backwards, the duration
     * hadn't settled, or the push failed transiently.
     *
     * This exists because the watermark alone cannot express "seen but unfinished". It used to be
     * advanced past every non-qualifying call, which made a call to a not-yet-synced lead
     * indistinguishable from one already sent — so it was dropped permanently and the agent's dial
     * count silently under-reported. The watermark now means "seen up to here" and this set means
     * "still owed to the server".
     */
    suspend fun getPending(): Set<Long> =
        context.callSyncDataStore.data.first()[PENDING_KEY]
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    suspend fun setPending(ids: Set<Long>) {
        val capped = if (ids.size <= MAX_PENDING) ids else ids.sortedDescending().take(MAX_PENDING).toSet()
        context.callSyncDataStore.edit { prefs ->
            prefs[PENDING_KEY] = capped.map(Long::toString).toSet()
        }
    }

    suspend fun hasBaseline(): Boolean =
        context.callSyncDataStore.data.first()[WATERMARK_KEY] != null

    /**
     * A stable UUID for this install, generated once and persisted. Combined with a device
     * call-log id it yields a [clientCallId] that stays identical across retries, so a
     * timed-out POST /api/calls can be safely re-sent without creating a duplicate.
     */
    suspend fun getInstallId(): String {
        context.callSyncDataStore.data.first()[INSTALL_ID_KEY]?.let { return it }
        val generated = UUID.randomUUID().toString()
        context.callSyncDataStore.edit { it[INSTALL_ID_KEY] = generated }
        return generated
    }

    /**
     * Resets the push bookkeeping on logout: the watermark and the pending set both describe what the
     * *previous* agent still owed the server, and replaying that under a new token would post one
     * agent's calls against another's account.
     *
     * [INSTALL_ID_KEY] is deliberately kept. It identifies the device, not the agent, and it's what
     * makes a retried `POST /api/calls` idempotent — regenerating it would turn an in-flight retry into
     * a duplicate.
     *
     * Clearing the watermark means the next `syncNewCalls` re-establishes a baseline rather than
     * pushing the whole device log as the new agent's work.
     */
    suspend fun clearForSessionEnd() {
        context.callSyncDataStore.edit { prefs ->
            prefs.remove(WATERMARK_KEY)
            prefs.remove(PENDING_KEY)
        }
    }
}
