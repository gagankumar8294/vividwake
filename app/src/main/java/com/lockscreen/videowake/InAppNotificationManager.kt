package com.lockscreen.videowake

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class InAppNotification(
    val id: String,
    val title: String,
    val message: String,
    val wallpaperId: String? = null,
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

    fun addNotification(title: String, message: String, wallpaperId: String? = null): InAppNotification {
        val list = getNotifications().toMutableList()
        val item = InAppNotification(
            id = "notif_${System.currentTimeMillis()}_${(100..999).random()}",
            title = title,
            message = message,
            wallpaperId = wallpaperId,
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
        val jsonStr = prefs.getString(KEY_NOTIFICATIONS, null) ?: return emptyList()
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

    private fun saveNotifications(list: List<InAppNotification>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("message", item.message)
                put("wallpaperId", item.wallpaperId)
                put("timestamp", item.timestamp)
                put("isRead", item.isRead)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_NOTIFICATIONS, arr.toString()).apply()
    }
}
