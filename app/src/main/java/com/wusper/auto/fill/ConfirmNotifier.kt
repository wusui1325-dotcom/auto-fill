package com.wusper.auto.fill

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * High-priority heads-up / full-screen notification that opens ConfirmActivity.
 * Content is only the extracted code and a short source, never the full message body.
 */
object ConfirmNotifier {
    const val CHANNEL_ID = "confirm_otp"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = mgr.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.confirm_notification_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.confirm_notification_channel_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            enableVibration(true)
            enableLights(false)
        }
        mgr.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun notify(context: Context, pending: PendingOtp): Boolean {
        if (!canPost(context)) return false
        val app = context.applicationContext
        ensureChannel(app)
        val text = app.getString(R.string.confirm_notification_text, pending.code, pending.source)
        val contentPi = ConfirmLauncher.pendingIntent(app, 0)
        val fullScreenPi = ConfirmLauncher.pendingIntent(app, 1)
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_confirm)
            .setContentTitle(app.getString(R.string.confirm_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .setFullScreenIntent(fullScreenPi, true)
            .setTimeoutAfter(OtpSession.PROMPT_TIMEOUT_MS)
        return try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, builder.build())
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun cancel(context: Context) {
        try {
            NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
            // Already gone, or the manager is unavailable.
        }
    }
}
