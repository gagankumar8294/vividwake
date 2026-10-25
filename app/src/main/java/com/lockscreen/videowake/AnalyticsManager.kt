package com.lockscreen.videowake

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * AnalyticsManager — Native Application Performance Monitoring (APM) & Real-time Telemetry.
 *
 * Tracks every user action:
 * - Device brand, model, OS version, RAM
 * - Action name, target, and status (SUCCESS / FAILED)
 * - Exact duration (in ms and seconds) for every operation
 * - Full error payload (error type, message, stack trace)
 * - Flushes to backend in real-time
 */
class AnalyticsManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AnalyticsManager"
        private const val PREFS_ANALYTICS = "video_lock_analytics"
        private const val KEY_DEVICE_ID = "pref_device_id"
        private const val KEY_USER_ID = "pref_user_id"
        private const val KEY_USER_EMAIL = "pref_user_email"

        @Volatile
        private var instance: AnalyticsManager? = null

        fun getInstance(context: Context): AnalyticsManager {
            return instance ?: synchronized(this) {
                instance ?: AnalyticsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_ANALYTICS, Context.MODE_PRIVATE)

    val deviceId: String = getOrCreateDeviceId()
    val sessionId: String = UUID.randomUUID().toString()

    private val scope = CoroutineScope(Dispatchers.IO)
    private val eventQueue = mutableListOf<JSONObject>()
    private var sessionStartTime = System.currentTimeMillis()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    init {
        logEvent("app_open", mapOf(
            "cold_start" to true,
            "status" to "SUCCESS",
            "deviceBrand" to Build.MANUFACTURER,
            "deviceModel" to Build.MODEL,
            "osVersion" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        ))
        flushSync()
    }

    private fun getOrCreateDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = "dev_" + UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun linkUserToDevice(userId: String, email: String) {
        prefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, email)
            .apply()

        logEvent("user_linked", mapOf(
            "user_id" to userId,
            "email" to email,
            "status" to "SUCCESS"
        ))
        flushSync()
    }

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)
    fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)

    @Synchronized
    fun logEvent(eventType: String, properties: Map<String, Any> = emptyMap()) {
        try {
            val eventObj = JSONObject().apply {
                put("type", eventType)
                put("timestamp", System.currentTimeMillis())
                put("elapsedSessionMs", System.currentTimeMillis() - sessionStartTime)
                put("deviceModel", Build.MODEL)
                put("deviceBrand", Build.MANUFACTURER)
                for ((k, v) in properties) {
                    put(k, v)
                }
            }
            eventQueue.add(eventObj)
            Log.d(TAG, "APM Event: $eventType [${properties["status"] ?: "INFO"}]")

            // Auto-flush if 4+ events accumulated
            if (eventQueue.size >= 4) {
                flushSync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error logging event $eventType", e)
        }
    }

    /**
     * Log a completed action with explicit status, duration, and error details
     */
    fun logAction(
        actionName: String,
        status: String, // "SUCCESS" or "FAILED"
        durationMs: Long,
        details: Map<String, Any> = emptyMap(),
        error: Throwable? = null
    ) {
        val params = mutableMapOf<String, Any>(
            "action" to actionName,
            "status" to status,
            "durationMs" to durationMs,
            "durationSec" to String.format("%.2fs", durationMs / 1000.0)
        )
        if (error != null) {
            params["errorType"] = error.javaClass.simpleName
            params["errorMessage"] = error.message ?: "Unknown error"
        }
        params.putAll(details)
        logEvent(actionName, params)

        // Always flush failures immediately so Admin gets real-time crash/error alerts
        if (status == "FAILED") {
            flushSync()
        }
    }

    /**
     * Measure synchronous block duration and report status & latency
     */
    inline fun <T> traceAction(
        actionName: String,
        extraParams: Map<String, Any> = emptyMap(),
        block: () -> T
    ): T {
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            val durationMs = System.currentTimeMillis() - start
            logAction(actionName, "SUCCESS", durationMs, extraParams)
            result
        } catch (e: Throwable) {
            val durationMs = System.currentTimeMillis() - start
            logAction(actionName, "FAILED", durationMs, extraParams, e)
            throw e
        }
    }

    /**
     * Measure suspend coroutine block duration and report status & latency
     */
    suspend fun <T> traceSuspendAction(
        actionName: String,
        extraParams: Map<String, Any> = emptyMap(),
        block: suspend () -> T
    ): T {
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            val durationMs = System.currentTimeMillis() - start
            logAction(actionName, "SUCCESS", durationMs, extraParams)
            result
        } catch (e: Throwable) {
            val durationMs = System.currentTimeMillis() - start
            logAction(actionName, "FAILED", durationMs, extraParams, e)
            throw e
        }
    }

    /**
     * Flush batched events to CloudConfig.ANALYTICS_BASE_URL/api/analytics/sync
     */
    fun flushSync() {
        scope.launch {
            val batch: List<JSONObject>
            synchronized(this@AnalyticsManager) {
                if (eventQueue.isEmpty()) return@launch
                batch = ArrayList(eventQueue)
                eventQueue.clear()
            }

            try {
                val payload = JSONObject().apply {
                    put("sessionId", sessionId)
                    put("deviceId", deviceId)
                    put("userId", getUserId() ?: "")
                    put("email", getUserEmail() ?: "")
                    put("deviceBrand", Build.MANUFACTURER)
                    put("deviceModel", Build.MODEL)
                    put("osVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    put("totalDurationMs", System.currentTimeMillis() - sessionStartTime)

                    val eventsArray = JSONArray()
                    for (ev in batch) {
                        eventsArray.put(ev)
                    }
                    put("events", eventsArray)
                }

                val syncUrl = "${CloudConfig.ANALYTICS_BASE_URL}/api/analytics/sync"
                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(syncUrl)
                    .header("User-Agent", "VideoLockScreen-Telemetry")
                    .header("x-app-auth", "vividwake_secure_app_key_2026")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Synced ${batch.size} APM events to backend")
                    } else {
                        Log.d(TAG, "Backend sync HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Analytics sync deferred: ${e.message}")
            }
        }
    }
}
