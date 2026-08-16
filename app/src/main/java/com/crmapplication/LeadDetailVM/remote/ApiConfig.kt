package com.crmapplication.LeadDetailVM.remote

import com.salescrm.BuildConfig

object ApiConfig {
    const val BASE_URL = "https://friskytrails-crm-pdte.vercel.app/"
    const val LEADS_ENDPOINT = "api/leads"

    /**
     * Host for the booking system (`POST api/bookings`), which is a **separate service** from the
     * leads API even though it shares the same JWT.
     *
     * Verified not to be on [BASE_URL]: every path under `api/bookings` there answers 404, while
     * `api/leads` and `api/config` answer 401 — i.e. those routes exist behind auth and the booking
     * ones don't exist at all. Point this at the booking backend's own base URL to make booking work.
     *
     * Left equal to [BASE_URL] rather than blank so nothing else changes shape while it's unset; the
     * 404 that results is translated into an agent-readable message by `friendlyBookingFailure`.
     */
    const val BOOKING_BASE_URL = BASE_URL

    val AUTH_TOKEN: String get() = BuildConfig.LEADS_AUTH_TOKEN

    val isConfigured: Boolean
        get() = BASE_URL.isNotBlank() && LEADS_ENDPOINT.isNotBlank()
}
