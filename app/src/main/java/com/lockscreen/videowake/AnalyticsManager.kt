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
 * AnalyticsManager — Native telemetry & SessionFlow journey engine.
 *
 * Captures user navigation, preview dwell times, download speeds, and conversions.
 * Batches events in memory and syncs to backend /api/analytics/sync endpoint
 * without impacting battery or main-thread performance.
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
        logEvent("app_open", mapOf("cold_start" to true))
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
            "email" to email
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
                for ((k, v) in properties) {
                    put(k, v)
                }
            }
            eventQueue.add(eventObj)
            Log.d(TAG, "Logged event: $eventType")

            // Flush automatically if queue has accumulated 8 events
            if (eventQueue.size >= 8) {
                flushSync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error logging event $eventType", e)
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
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully synced ${batch.size} telemetry events to backend")
                    } else {
                        Log.d(TAG, "Backend returned HTTP ${response.code} (endpoint ready for live config)")
                    }
                }
            } catch (e: Exception) {
                // Non-blocking catch — telemetry should never disturb user flow
                Log.d(TAG, "Analytics sync deferred: ${e.message}")
            }
        }
    }
}
