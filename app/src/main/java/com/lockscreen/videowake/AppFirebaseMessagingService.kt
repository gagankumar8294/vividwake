package com.lockscreen.videowake

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class AppFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"
        const val CHANNEL_ID = "vividwake_wallpapers_channel"
        const val CHANNEL_NAME = "Live Wallpapers & Updates"
        const val ACTION_NEW_INAPP_NOTIFICATION = "com.lockscreen.videowake.NEW_INAPP_NOTIFICATION"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Token: $token")
        // If user is logged in, update token in Firestore
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            FirebaseFirestore.getInstance().collection("users").document(user.uid)
                .set(mapOf("fcmToken" to token, "lastActiveAt" to System.currentTimeMillis()), SetOptions.merge())
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM Message received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        val notifPayload = remoteMessage.notification

        val title = notifPayload?.title ?: data["title"] ?: "New Live Wallpaper"
        val body = notifPayload?.body ?: data["body"] ?: "A new live wallpaper is available in Explore Store!"
        val wallpaperId = data["wallpaperId"]
        // deliveryType: "system" (status bar only), "in_app" (inbox only), or "both" (default)
        val deliveryType = data["type"] ?: "both"

        val notificationManager = InAppNotificationManager(applicationContext)

        // 1. If in_app or both, save to In-App Notification Center
        if (deliveryType == "in_app" || deliveryType == "both") {
            notificationManager.addNotification(title, body, wallpaperId)
            // Broadcast intent to update UI badge in MainActivity if running
            sendBroadcast(Intent(ACTION_NEW_INAPP_NOTIFICATION).setPackage(packageName))
        }

        // 2. If system or both, show Android Status Bar Notification
        if (deliveryType == "system" || deliveryType == "both") {
            showSystemNotification(title, body, wallpaperId)
        }
    }

    private fun showSystemNotification(title: String, body: String, wallpaperId: String?) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when new live wallpapers or assignments arrive"
                enableLights(true)
                lightColor = Color.parseColor("#9D4EDD")
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "store")
            if (!wallpaperId.isNullOrBlank()) {
                putExtra("highlight_wallpaper_id", wallpaperId)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }
}
