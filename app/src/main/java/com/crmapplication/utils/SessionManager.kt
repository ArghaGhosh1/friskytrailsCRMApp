package com.crmapplication.utils

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "session")

/**
 * The signed-in agent's token and identity.
 *
 * **Reads are served from memory, not from disk.** [getToken] is called on essentially every API
 * request, and each call used to be a `runBlocking { dataStore.data.first() }` — a disk read blocking
 * whichever thread asked, including the main thread via `AuthViewModel`'s initial state. DataStore is
 * only touched once now, on the first read, and the cache is updated in step with every write.
 *
 * Writes stay synchronous. They happen on login, logout and profile-save only, so blocking costs
 * nothing there — and a token that reached memory but not disk would silently sign the agent out on
 * next launch, which is worse than a few milliseconds.
 */
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val TOKEN_KEY = stringPreferencesKey("auth_token")
        private val AGENT_KEY = stringPreferencesKey("agent_name")
        private val EMAIL_KEY = stringPreferencesKey("agent_email")
        private val AGENT_ID_KEY = stringPreferencesKey("agent_id")

        const val DEFAULT_AGENT_NAME = "Agent"
    }

    /** What's on disk, as far as this process knows. Null until the first read loads it. */
    private data class Session(
        val token: String? = null,
        val name: String? = null,
        val email: String? = null,
        val agentId: String? = null,
    )

    @Volatile
    private var cached: Session? = null

    private val lock = Any()

    /**
     * The session, loading from disk at most once per process.
     *
     * Double-checked so the common path is a single volatile read. Two threads racing the first read
     * would both get the same values from DataStore anyway, so the lock is about avoiding duplicate
     * disk work, not correctness.
     */
    private fun session(): Session {
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: runBlocking { load() }.also { cached = it }
        }
    }

    private suspend fun load(): Session = context.dataStore.data.first().toSession()

    private fun Preferences.toSession() = Session(
        token = this[TOKEN_KEY],
        name = this[AGENT_KEY],
        email = this[EMAIL_KEY],
        agentId = this[AGENT_ID_KEY],
    )

    /**
     * Persists, then mirrors into the cache.
     *
     * Cache-after-disk on purpose: if the write throws, memory still matches what's actually stored
     * rather than reporting a token that was never saved.
     */
    private fun write(update: (MutablePreferences) -> Unit) = runBlocking {
        context.dataStore.edit { prefs -> update(prefs) }
        cached = load()
    }

    fun saveToken(token: String) = write { it[TOKEN_KEY] = token }

    fun saveAgentName(name: String) = write { it[AGENT_KEY] = name }

    fun saveAgentEmail(email: String) = write { it[EMAIL_KEY] = email }

    fun saveAgentId(id: String) = write { it[AGENT_ID_KEY] = id }

    fun getToken(): String? = session().token

    fun getAgentName(): String = session().name ?: DEFAULT_AGENT_NAME

    fun getAgentEmail(): String = session().email.orEmpty()

    fun getAgentId(): String? = session().agentId

    /**
     * Forgets the signed-in agent, in memory and on disk.
     *
     * The cache is set to an empty session rather than null, so a read racing this can't re-load the
     * agent it just cleared — null would mean "not loaded yet" and send the next reader back to disk,
     * which may not have finished the edit.
     *
     * This clears identity only. Everything else the agent left behind on the device is
     * `SessionCleaner`'s job — clearing a token while another agent's leads sit in Room is what let
     * the previous account's data show on the next login.
     */
    fun clear() = runBlocking {
        context.dataStore.edit { it.clear() }
        cached = Session()
    }
}
