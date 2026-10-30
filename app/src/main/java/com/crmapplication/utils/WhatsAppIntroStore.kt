package com.crmapplication.utils

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which leads this agent has already opened WhatsApp for, so the greeting is pre-filled
 * only the first time.
 *
 * Keyed by agent AND lead: a reassigned lead gives the new agent their own first greeting, and two
 * agents on one phone don't affect each other. Deliberately not registered with SessionCleaner, so
 * logging out and back in doesn't bring the greeting back.
 */
@Singleton
class WhatsAppIntroStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("whatsapp_intro", Context.MODE_PRIVATE)

    private fun key(agentId: String?, leadId: String) = "${agentId.orEmpty()}:$leadId"

    fun wasSent(agentId: String?, leadId: String): Boolean = prefs.contains(key(agentId, leadId))

    fun markSent(agentId: String?, leadId: String) {
        prefs.edit().putLong(key(agentId, leadId), System.currentTimeMillis()).apply()
    }
}