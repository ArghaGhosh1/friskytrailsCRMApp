package com.crmapplication.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** Counts the app's own network requests, so traffic can be checked without looking at the server. Debug builds only. */
object RequestMeter {
    private const val TAG = "RequestMeter"
    private val events = ConcurrentLinkedQueue<Pair<Long, String>>()
    private val objectId = Regex("[0-9a-fA-F]{24}")
    private val number = Regex("/\\d+")
    @Volatile private var lastReportAt = System.currentTimeMillis()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("request_meter", Context.MODE_PRIVATE)
    }

    fun record(method: String, rawPath: String) {
        val now = System.currentTimeMillis()
        val key = "$method " + rawPath.replace(objectId, "{id}").replace(number, "/{n}")
        events.add(now to key)
        while (events.peek()?.let { now - it.first > 3_600_000L } == true) events.poll()

        prefs?.let { p ->        // hourly totals survive the app being killed (needed for the background test)
            val hour = "h_" + SimpleDateFormat("yyyyMMdd_HH", Locale.US).format(Date(now))
            p.edit().putInt(hour, p.getInt(hour, 0) + 1).apply()
        }
        if (now - lastReportAt >= 60_000L) {
            lastReportAt = now
            Log.d(TAG, summary(60_000L))
        }
    }

    fun summary(windowMs: Long): String {
        val cutoff = System.currentTimeMillis() - windowMs
        val recent = events.filter { it.first >= cutoff }
        val byPath = recent.groupingBy { it.second }.eachCount().entries.sortedByDescending { it.value }
        return "last ${windowMs / 1000}s: ${recent.size} requests " +
                byPath.joinToString(prefix = "[", postfix = "]") { "${it.key} x${it.value}" }
    }

    /** Requests per clock hour, for the last 12 hours that had any. */
    fun hourlyTotals(): String {
        val all = prefs?.all.orEmpty().filterKeys { it.startsWith("h_") }
        return all.entries.sortedBy { it.key }.takeLast(12)
            .joinToString("\n") { "${it.key.removePrefix("h_")}  ${it.value}" }
    }
}

class RequestMeterInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        RequestMeter.record(request.method, request.url.encodedPath)
        return chain.proceed(request)
    }
}