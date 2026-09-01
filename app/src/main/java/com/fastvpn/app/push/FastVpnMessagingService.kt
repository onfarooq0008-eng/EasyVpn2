package com.fastvpn.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fastvpn.app.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

// No-ops safely if google-services.json was never added -- FCM never calls
// into this class in that case (there's no Firebase project registration
// underneath it), so this file is fine to ship either way.
//
// Typical uses here: "a server you use went offline", promo notifications,
// or a silent data message telling the app to refresh its server list early
// instead of waiting for the next poll.
class FastVpnMessagingService : FirebaseMessagingService() {

    companion object {
        private const val CHANNEL_ID = "fastvpn_push"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // If you want to target specific devices (rather than just topics),
        // send this token to your backend here, e.g.:
        //   POST /api/register-push-token { devicePublicKey, fcmToken }
        // and store it alongside the device's registration in store.js.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"] ?: ""
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "FastVPN notifications", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
}
