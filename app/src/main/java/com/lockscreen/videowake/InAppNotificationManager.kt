package com.lockscreen.videowake

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class InAppNotification(
    val id: String,
    val title: String,
    val message: String,
    val wallpaperId: String? = null,
    val imageUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    var isRead: Boolean = false
)

class InAppNotificationManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "vividwake_inapp_notifications"
        private const val KEY_NOTIFICATIONS = "notifications_json"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun addNotification(title: String, message: String, wallpaperId: String? = null, imageUrl: String? = null): InAppNotification {
        val list = getNotifications().toMutableList()
        val item = InAppNotification(
            id = "notif_${System.currentTimeMillis()}_${(100..999).random()}",
            title = title,
            message = message,
            wallpaperId = wallpaperId,
            imageUrl = imageUrl,
            timestamp = System.currentTimeMillis(),
            isRead = false
        )
        // Add to top of list
        list.add(0, item)
        // Keep max 50 recent notifications
        if (list.size > 50) {
            list.removeAt(list.size - 1)
        }
        saveNotifications(list)
        return item
    }

    fun getNotifications(): List<InAppNotification> {
        val jsonStr = prefs.getString(KEY_NOTIFICATIONS, null)
        if (jsonStr == null) {
            val initial = listOf(
                InAppNotification(
                    id = "welcome_01",
                    title = "Welcome to VividWake",
                    message = "Set stunning cinematic video wallpapers on your lock screen. Explore top live picks in Explore Store!",
                    wallpaperId = "wp_game_character_01",
                    timestamp = System.currentTimeMillis() - 3600000,
                    isRead = false
                ),
                InAppNotification(
                    id = "featured_drop_01",
                    title = "Cyberpunk 2077 Live Wallpaper Dropped",
                    message = "Exclusive live video wallpaper is now available in Explore Store.",
                    wallpaperId = "cyberpunk_neon_city_4k",
                    timestamp = System.currentTimeMillis() - 7200000,
                    isRead = false
                )
            )
            saveNotifications(initial)
            return initial
        }
        val list = mutableListOf<InAppNotification>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    InAppNotification(
                        id = obj.optString("id"),
                        title = obj.optString("title"),
                        message = obj.optString("message"),
                        wallpaperId = if (obj.has("wallpaperId")) obj.optString("wallpaperId") else null,
                        imageUrl = if (obj.has("imageUrl")) obj.optString("imageUrl") else null,
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        isRead = obj.optBoolean("isRead", false)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun getUnreadCount(): Int {
        return getNotifications().count { !it.isRead }
    }

    fun markAllAsRead() {
        val list = getNotifications()
        list.forEach { it.isRead = true }
        saveNotifications(list)
    }

    fun clearAll() {
        prefs.edit().remove(KEY_NOTIFICATIONS).apply()
    }

    suspend fun syncWithServer(
        userEmail: String?,
        userId: String?,
        showSystemAlertOnNew: Boolean = true
    ): Int = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val query = if (!userEmail.isNullOrBlank()) "?userEmail=${Uri.encode(userEmail)}" else ""
        val urls = listOf(
            "${CloudConfig.ANALYTICS_BASE_URL}/api/notifications/sync$query",
            "http://127.0.0.1:5000/api/notifications/sync$query"
        )

        var fetchedList: List<InAppNotification>? = null
        for (url in urls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            if (json.optBoolean("success", false)) {
                                val arr = json.optJSONArray("notifications") ?: JSONArray()
                                val parsed = mutableListOf<InAppNotification>()
                                for (i in 0 until arr.length()) {
                                    val obj = arr.getJSONObject(i)
                                    parsed.add(
                                        InAppNotification(
                                            id = obj.optString("id"),
                                            title = obj.optString("title"),
                                            message = obj.optString("body"),
                                            wallpaperId = if (obj.has("wallpaperId")) obj.optString("wallpaperId") else null,
                                            imageUrl = if (obj.has("imageUrl")) obj.optString("imageUrl") else null,
                                            timestamp = obj.optLong("sentAt", System.currentTimeMillis()),
                                            isRead = false
                                        )
                                    )
                                }
                                fetchedList = parsed
                                return@use
                            }
                        }
                    }
                }
                if (fetchedList != null) break
            } catch (e: Exception) {
                Log.d("InAppNotifMgr", "Sync failed from $url (${e.message})")
            }
        }

        if (fetchedList == null) return@withContext 0

        val currentLocal = getNotifications().toMutableList()
        val existingIds = currentLocal.map { it.id }.toSet()
        var newCount = 0

        for (cloudNotif in fetchedList!!) {
            if (!existingIds.contains(cloudNotif.id)) {
                currentLocal.add(0, cloudNotif)
                newCount++
                if (showSystemAlertOnNew) {
                    try {
                        AppFirebaseMessagingService.showSystemNotification(
                            context,
                            cloudNotif.title,
                            cloudNotif.message,
                            cloudNotif.wallpaperId,
                            cloudNotif.imageUrl
                        )
                    } catch (e: Exception) {
                        Log.w("InAppNotifMgr", "Could not show system notification alert: ${e.message}")
                    }
                }
            }
        }

        if (newCount > 0) {
            saveNotifications(currentLocal.take(50))
        }
        newCount
    }

    private fun saveNotifications(list: List<InAppNotification>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("message", item.message)
                put("wallpaperId", item.wallpaperId)
                put("imageUrl", item.imageUrl)
                put("timestamp", item.timestamp)
                put("isRead", item.isRead)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_NOTIFICATIONS, arr.toString()).apply()
    }
}
