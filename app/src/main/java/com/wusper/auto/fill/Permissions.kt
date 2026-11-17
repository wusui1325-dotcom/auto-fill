package com.wusper.auto.fill

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat

object Permissions {

    fun isNotificationListenerEnabled(context: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, OtpAccessibilityService::class.java)
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null && am.isEnabled) {
            val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?: emptyList()
            if (enabled.any { it.resolveInfo?.serviceInfo?.let { si ->
                    si.packageName == expected.packageName && si.name == expected.className
                } == true }) {
                return true
            }
        }
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val wantFlat = expected.flattenToString()
        val wantShort = expected.flattenToShortString()
        return raw.split(':').any { it.equals(wantFlat, ignoreCase = true) || it.equals(wantShort, ignoreCase = true) }
    }

    fun openNotificationListenerSettings(context: Context) {
        val cn = ComponentName(context, OtpNotificationListener::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
            }
            if (detail.resolveActivity(context.packageManager) != null) {
                context.startActivity(detail)
                return
            }
        }
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openAccessibilitySettings(context: Context) {
        val cn = ComponentName(context, OtpAccessibilityService::class.java)
        val detail = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val specific = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
            data = Uri.parse("package:${context.packageName}")
        }
        try {
            if (specific.resolveActivity(context.packageManager) != null) {
                context.startActivity(specific)
                return
            }
        } catch (_: Exception) {
            // Fall through to the list.
        }
        context.startActivity(detail)
    }
}
