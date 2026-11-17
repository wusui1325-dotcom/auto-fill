package com.wusper.auto.fill

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads notifications only while the session is armed.
 * Does not open the SMS inbox. The confirm UI never receives the full body.
 */
class OtpNotificationListener : NotificationListenerService() {

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
        if (sbn.packageName == packageName) return
        if (!OtpSession.isArmed()) return
        if (sbn.isOngoing) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
        val code = OtpParser.extract(title, text, bigText) ?: return
        val label = appLabel(sbn.packageName)
        val fallback = getString(R.string.source_notification)
        val source = SourceLabel.short(label, title?.toString(), fallback)
        OtpSession.offerOtp(
            PendingOtp(
                code = code,
                source = source,
                packageName = sbn.packageName.orEmpty(),
                notificationKey = sbn.key.orEmpty(),
                postedAt = sbn.postTime
            )
        )
    }

    private fun appLabel(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info)?.toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
