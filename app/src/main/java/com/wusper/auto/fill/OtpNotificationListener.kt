package com.wusper.auto.fill

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.LruCache

/**
 * Reads notifications only while the session is armed.
 * Does not open the SMS inbox. The confirm UI never receives the full body.
 */
class OtpNotificationListener : NotificationListenerService() {

    private val labelCache = LruCache<String, String>(64)

    override fun onListenerConnected() {
        super.onListenerConnected()
        OtpSession.setListenerConnected(true)
    }

    override fun onListenerDisconnected() {
        OtpSession.setListenerConnected(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!OtpSession.isArmed()) return
        val pkg = sbn.packageName
        if (pkg == packageName) return
        if (sbn.isOngoing) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val parts = NotificationTexts.fromNotification(notification)
        val code = OtpParser.extract(parts) ?: return
        val fallback = getString(R.string.source_notification)
        val source = SourceLabel.short(appLabel(pkg), parts.title?.toString(), fallback)
        OtpSession.offerOtp(
            PendingOtp(
                code = code,
                source = source,
                packageName = pkg.orEmpty(),
                notificationKey = sbn.key.orEmpty(),
                postedAt = sbn.postTime
            )
        )
    }

    private fun appLabel(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        labelCache.get(pkg)?.let { return it }
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info)?.toString()?.also { labelCache.put(pkg, it) }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
