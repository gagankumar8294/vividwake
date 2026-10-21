package com.lockscreen.videowake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            val prefs = context.getSharedPreferences(VideoWallpaperService.PREFS_NAME, Context.MODE_PRIVATE)
            val isOverlayEnabled = prefs.getBoolean("pref_overlay_service_enabled", false)
            if (isOverlayEnabled) {
                val serviceIntent = Intent(context, VideoWakeService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
