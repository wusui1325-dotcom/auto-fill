package com.wusper.auto.fill

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.os.Parcelable

/**
 * Collects notification title/text/bigText/inbox lines/MessagingStyle bodies
 * for OTP parsing only. The confirm UI never receives these strings.
 */
object NotificationTexts {

    fun fromNotification(notification: Notification): NotificationParts {
        val extras = notification.extras
        val extraChunks = ArrayList<CharSequence>(8)
        append(extraChunks, charSequence(extras, Notification.EXTRA_SUB_TEXT))
        append(extraChunks, charSequence(extras, Notification.EXTRA_INFO_TEXT))
        append(extraChunks, charSequence(extras, Notification.EXTRA_SUMMARY_TEXT))
        append(extraChunks, charSequence(extras, Notification.EXTRA_TITLE_BIG))
        append(extraChunks, charSequence(extras, Notification.EXTRA_BIG_TEXT))
        append(extraChunks, notification.tickerText)
        charSequenceArray(extras, Notification.EXTRA_TEXT_LINES)?.forEach { append(extraChunks, it) }
        return NotificationParts(
            title = charSequence(extras, Notification.EXTRA_TITLE),
            text = charSequence(extras, Notification.EXTRA_TEXT),
            extraChunks = extraChunks,
            messages = messagingTexts(extras)
        )
    }

    private fun append(out: MutableList<CharSequence>, value: CharSequence?) {
        if (value.isNullOrBlank()) return
        // Skip exact duplicates of a shorter field already collected.
        if (out.any { it.contentEquals(value) }) return
        out += value
    }

    private fun charSequence(extras: Bundle, key: String): CharSequence? {
        return extras.getCharSequence(key) ?: extras.getString(key)
    }

    private fun charSequenceArray(extras: Bundle, key: String): Array<CharSequence>? {
        extras.getCharSequenceArray(key)?.let { return it }
        extras.getStringArray(key)?.let { return it as Array<CharSequence> }
        return null
    }

    private fun messagingTexts(extras: Bundle): List<CharSequence> {
        val raw = parcelableArray(extras, Notification.EXTRA_MESSAGES) ?: return emptyList()
        val texts = ArrayList<CharSequence>(raw.size)
        for (item in raw) {
            val bundle = item as? Bundle ?: continue
            val text = bundle.getCharSequence("text") ?: bundle.getString("text")
            if (!text.isNullOrBlank()) texts += text
        }
        return texts
    }

    private fun parcelableArray(extras: Bundle, key: String): Array<out Parcelable>? {
        return if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelableArray(key, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(key)
        }
    }
}
