package com.crmapplication.utils

import com.crmapplication.LeadDetailVM.local.BugReportDao
import com.crmapplication.LeadDetailVM.local.CallDao
import com.crmapplication.LeadDetailVM.local.LeadDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Erases everything the signed-in agent left on this device.
 *
 * Logout used to be `session.clear()` alone — it dropped the token and nothing else. Because the
 * repositories are `@Singleton` and logout doesn't restart the process, the next agent to sign in
 * inherited the previous one's Room rows, cached dashboard and sync throttles. `LeadsViewModel`
 * collects `observeLeads()` on init, so the previous agent's leads were on screen from the first
 * frame; worse, the 30s `syncLeads` throttle meant a login soon after a logout skipped the network
 * entirely and left them there.
 *
 * What is deliberately **kept**:
 * - `ProductCatalogStore` / `StatusCatalogStore` — org-wide config from `GET /api/config`, identical
 *   for every agent. Wiping it would blank the chips and dropdowns until a refetch, for no gain.
 * - `CallSyncStore`'s install id — identifies the device, not the agent, and is what makes a retried
 *   `POST /api/calls` idempotent.
 * - The `calls` rows themselves — this device's own call history, which the backend can only partly
 *   rebuild (answered calls only). Their `leadId` links are cut instead; see [CallDao.clearLeadLinks].
 *
 * Everything else goes, including notes and bug reports that never reached the server. Those exist
 * only on the device, so this does lose them — the right trade at an account boundary, since the
 * alternative is one agent's unsent note being pushed under another agent's token.
 */
@Singleton
class SessionCleaner @Inject constructor(
    private val leadDao: LeadDao,
    private val callDao: CallDao,
    private val bugReportDao: BugReportDao,
    private val dueDateStore: DueDateStore,
    private val callSyncStore: CallSyncStore,
) {

    /**
     * In-memory caches and throttles held by `@Singleton` repositories, which outlive a logout because
     * nothing recreates them. Registered rather than injected directly so this class doesn't have to
     * depend on every repository — and so a new one can opt in without touching the cleaner.
     */
    private val resettables = mutableListOf<SessionScopedState>()

    fun register(state: SessionScopedState) {
        synchronized(resettables) {
            if (resettables.none { it === state }) resettables.add(state)
        }
    }

    /**
     * Clears local state for the agent who is signing out. Call **before** the login screen can accept
     * new credentials, so a fast re-login can't race a half-finished wipe.
     *
     * Ordering matters: in-memory state is reset first, because a repository holding a stale
     * `lastSyncAt` could otherwise skip the first sync of the new session and leave the freshly emptied
     * UI with nothing to show.
     */
    suspend fun clearForLogout() {
        synchronized(resettables) { resettables.toList() }.forEach { it.resetSessionState() }

        // Cascades to `notes` and `status_history` — both declare onDelete = CASCADE on leadId.
        leadDao.deleteAll()
        callDao.clearLeadLinks()
        bugReportDao.deleteAll()

        dueDateStore.clearAll()
        callSyncStore.clearForSessionEnd()
    }
}

/**
 * Implemented by anything holding per-agent state in memory that has to die with the session.
 *
 * The contract is narrow on purpose: reset fields to their initial values, don't touch disk (the
 * cleaner owns that) and don't block.
 */
interface SessionScopedState {
    fun resetSessionState()
}
