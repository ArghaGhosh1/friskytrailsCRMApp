package com.crmapplication.di

import com.crmapplication.LeadDetailVM.remote.AgentsApi
import com.crmapplication.LeadDetailVM.remote.ApiConfig
import com.crmapplication.LeadDetailVM.remote.AuthApi
import com.crmapplication.LeadDetailVM.remote.BookingsApi
import com.crmapplication.LeadDetailVM.remote.BugReportApi
import com.crmapplication.LeadDetailVM.remote.CallsApi
import com.crmapplication.LeadDetailVM.remote.CloudinaryApi
import com.crmapplication.LeadDetailVM.remote.ConfigApi
import com.crmapplication.LeadDetailVM.remote.LeadsApi
import com.crmapplication.LeadDetailVM.remote.UploadApi
import com.salescrm.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

private const val CONNECT_TIMEOUT_SECONDS = 30L
private const val READ_TIMEOUT_SECONDS = 30L
private const val WRITE_TIMEOUT_SECONDS = 30L

/**
 * A 10 MB attachment on mobile data does not finish inside the 30s the API client allows, and the
 * failure looks identical to a dead network. Uploads get their own budget.
 */
private const val UPLOAD_WRITE_TIMEOUT_SECONDS = 180L
private const val UPLOAD_READ_TIMEOUT_SECONDS = 120L

/** Retrofit demands a base URL even when every call supplies its own via `@Url`. */
private const val CLOUDINARY_PLACEHOLDER_BASE_URL = "https://api.cloudinary.com/"

/** Extra attempts (beyond the first) for replay-safe requests. */
private const val MAX_GET_RETRIES = 2
private const val RETRY_BACKOFF_MS = 400L

/**
 * Retries only requests that are safe to replay. A dropped connection on a marginal mobile network
 * is usually transient, and OkHttp's own [OkHttpClient.Builder.retryOnConnectionFailure] doesn't
 * cover a request that failed after the connection was established.
 *
 * GET only, deliberately: replaying a POST could create a duplicate note or lead.
 */
private class RetryIdempotentInterceptor(private val maxRetries: Int) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.method.equals("GET", ignoreCase = true)) return chain.proceed(request)

        var lastError: IOException? = null
        repeat(maxRetries + 1) { attempt ->
            if (attempt > 0) Thread.sleep(RETRY_BACKOFF_MS * attempt)
            try {
                val response = chain.proceed(request)
                // 502/503/504 from a serverless host usually means the function hadn't woken yet.
                if (attempt == maxRetries || !response.isTransientServerError()) return response
                response.close()
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Request failed after ${maxRetries + 1} attempts")
    }
}

private fun Response.isTransientServerError(): Boolean = code == 502 || code == 503 || code == 504

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            // The backend is serverless (Vercel): a cold start can blow past OkHttp's default 10s
            // read timeout even on a healthy connection. Agents on mobile data were seeing this as
            // a hard "failed to connect" on the first request after an idle period.
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(RetryIdempotentInterceptor(MAX_GET_RETRIES))
            .addInterceptor(logging)
            .build()
    }

    /**
     * A separate client for direct-to-Cloudinary uploads, for three reasons — each of which would
     * break a large upload on the shared one:
     *
     * 1. **Logging.** The API client logs at [HttpLoggingInterceptor.Level.BODY] in debug, which
     *    buffers the entire request body into memory to decide whether it is printable. On a 10 MB
     *    file that is a second copy of the file plus a real risk of OOM on a low-end device, so this
     *    client stays at `HEADERS`.
     * 2. **Timeouts.** See [UPLOAD_WRITE_TIMEOUT_SECONDS].
     * 3. **Retries.** [RetryIdempotentInterceptor] is GET-only so it would not replay an upload
     *    anyway, but leaving it off makes that explicit: a retried upload means a duplicate asset.
     *
     * Also note what is *absent* — no auth interceptor. The signed form fields authenticate the
     * upload, and our JWT has no business reaching a third-party host.
     */
    @Provides
    @Singleton
    @Named("cloudinary")
    fun provideCloudinaryOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(UPLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(UPLOAD_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideCloudinaryApi(@Named("cloudinary") client: OkHttpClient): CloudinaryApi =
        Retrofit.Builder()
            .baseUrl(CLOUDINARY_PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CloudinaryApi::class.java)

    @Provides
    @Singleton
    @Named("leads")
    fun provideLeadsRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL.withTrailingSlash())
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideLeadsApi(@Named("leads") retrofit: Retrofit): LeadsApi =
        retrofit.create(LeadsApi::class.java)

    @Provides
    @Singleton
    fun provideAuthApi(@Named("leads") retrofit: Retrofit): AuthApi =
        retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideUploadApi(@Named("leads") retrofit: Retrofit): UploadApi =
        retrofit.create(UploadApi::class.java)

    @Provides
    @Singleton
    fun provideAgentsApi(@Named("leads") retrofit: Retrofit): AgentsApi =
        retrofit.create(AgentsApi::class.java)

    @Provides
    @Singleton
    fun provideCallsApi(@Named("leads") retrofit: Retrofit): CallsApi =
        retrofit.create(CallsApi::class.java)

    @Provides
    @Singleton
    fun provideConfigApi(@Named("leads") retrofit: Retrofit): ConfigApi =
        retrofit.create(ConfigApi::class.java)

    @Provides
    @Singleton
    fun provideBugReportApi(@Named("leads") retrofit: Retrofit): BugReportApi =
        retrofit.create(BugReportApi::class.java)

    /**
     * Booking creation posts a multipart body with a screenshot in it, so it runs on the
     * upload-tuned client rather than the shared one: a 5 MB body doesn't reliably finish inside the
     * API client's 30s write timeout on mobile data, and its `BODY` logging would buffer the whole
     * image in debug builds.
     *
     * Unlike the Cloudinary client this one is pointed at our own host, and auth travels per-call via
     * `@Header` — see [BookingsApi.createBooking].
     */
    @Provides
    @Singleton
    fun provideBookingsApi(@Named("cloudinary") client: OkHttpClient): BookingsApi =
        Retrofit.Builder()
            // Its own base URL, not the leads one — the booking system is a separate service. See
            // [ApiConfig.BOOKING_BASE_URL].
            .baseUrl(ApiConfig.BOOKING_BASE_URL.withTrailingSlash())
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BookingsApi::class.java)

    private fun String.withTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"
}
