package com.lockscreen.videowake

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * PreviewCooldownManager
 *
 * Enforces Guest & User Preview Quotas:
 * - 10 Initial Free Previews.
 * - After limit reached: 5 Previews per 10-minute cooldown.
 * - Dispatches a clean local notification when the cooldown timer finishes.
 */
class PreviewCooldownManager(private val context: Context) {

    companion object {
        private const val PREFS_PREVIEWS = "vividwake_preview_quota"
        private const val KEY_INITIAL_PREVIEWS_USED = "initial_previews_used"
        private const val KEY_COOLDOWN_START_TIME = "cooldown_start_time"
        private const val KEY_COOLDOWN_PREVIEWS_USED = "cooldown_previews_used"
        private const val KEY_NOTIFICATION_SCHEDULED = "notification_scheduled"

        const val INITIAL_FREE_PREVIEWS = 10
        const val COOLDOWN_BATCH_PREVIEWS = 5
        const val COOLDOWN_DURATION_MS = 10L * 60L * 1000L // 10 Minutes
        const val CHANNEL_ID = "vividwake_preview_alerts"
        const val NOTIFICATION_ID = 4040
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_PREVIEWS, Context.MODE_PRIVATE)

    data class PreviewStatus(
        val canPreview: Boolean,
        val remainingPreviews: Int,
        val isCooldownActive: Boolean,
        val cooldownRemainingMs: Long
    )

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Wallpaper Previews"
            val descriptionText = "Alerts when your preview batch is ready"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun getStatus(): PreviewStatus {
        val initialUsed = prefs.getInt(KEY_INITIAL_PREVIEWS_USED, 0)
        if (initialUsed < INITIAL_FREE_PREVIEWS) {
            return PreviewStatus(
                canPreview = true,
                remainingPreviews = INITIAL_FREE_PREVIEWS - initialUsed,
                isCooldownActive = false,
                cooldownRemainingMs = 0L
            )
        }

        val cooldownStart = prefs.getLong(KEY_COOLDOWN_START_TIME, 0L)
        val now = System.currentTimeMillis()
        val elapsed = now - cooldownStart

        // Cooldown has completed -> replenish 5 previews!
        if (elapsed >= COOLDOWN_DURATION_MS) {
            // Check if we should fire notification for replenished previews
            if (prefs.getBoolean(KEY_NOTIFICATION_SCHEDULED, false)) {
                sendReplenishedNotification()
                prefs.edit().putBoolean(KEY_NOTIFICATION_SCHEDULED, false).apply()
            }

            val batchUsed = prefs.getInt(KEY_COOLDOWN_PREVIEWS_USED, 0)
            val batchRemaining = (COOLDOWN_BATCH_PREVIEWS - batchUsed).coerceAtLeast(0)

            return if (batchRemaining > 0) {
                PreviewStatus(
                    canPreview = true,
                    remainingPreviews = batchRemaining,
                    isCooldownActive = false,
                    cooldownRemainingMs = 0L
                )
            } else {
                // All 5 used in this cycle, start a new 10 min cooldown
                val remainingMs = COOLDOWN_DURATION_MS - (now - cooldownStart)
                PreviewStatus(
                    canPreview = false,
                    remainingPreviews = 0,
                    isCooldownActive = true,
                    cooldownRemainingMs = remainingMs.coerceAtLeast(0L)
                )
            }
        }

        // Still cooling down
        val remainingMs = COOLDOWN_DURATION_MS - elapsed
        return PreviewStatus(
            canPreview = false,
            remainingPreviews = 0,
            isCooldownActive = true,
            cooldownRemainingMs = remainingMs
        )
    }

    /**
     * Consume one preview credit. Returns true if granted.
     */
    fun consumePreview(): Boolean {
        val status = getStatus()
        if (!status.canPreview) return false

        val initialUsed = prefs.getInt(KEY_INITIAL_PREVIEWS_USED, 0)
        if (initialUsed < INITIAL_FREE_PREVIEWS) {
            prefs.edit().putInt(KEY_INITIAL_PREVIEWS_USED, initialUsed + 1).apply()
            return true
        }

        // Consume from cooldown batch
        val cooldownStart = prefs.getLong(KEY_COOLDOWN_START_TIME, 0L)
        val elapsed = System.currentTimeMillis() - cooldownStart
        if (elapsed >= COOLDOWN_DURATION_MS) {
            // Reset for new batch
            prefs.edit()
                .putLong(KEY_COOLDOWN_START_TIME, System.currentTimeMillis())
                .putInt(KEY_COOLDOWN_PREVIEWS_USED, 1)
                .apply()
            return true
        }

        val batchUsed = prefs.getInt(KEY_COOLDOWN_PREVIEWS_USED, 0)
        if (batchUsed < COOLDOWN_BATCH_PREVIEWS) {
            val newUsed = batchUsed + 1
            val editor = prefs.edit().putInt(KEY_COOLDOWN_PREVIEWS_USED, newUsed)
            // If this was the last preview in batch, start timer & schedule alert
            if (newUsed >= COOLDOWN_BATCH_PREVIEWS) {
                editor.putLong(KEY_COOLDOWN_START_TIME, System.currentTimeMillis())
                editor.putBoolean(KEY_NOTIFICATION_SCHEDULED, true)
            }
            editor.apply()
            return true
        }

        return false
    }

    private fun sendReplenishedNotification() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent: PendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sparkle)
                .setContentTitle("5 Previews Unlocked ✨")
                .setContentText("Your preview cooldown has ended. Discover new live wallpapers now!")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {}
    }
}
