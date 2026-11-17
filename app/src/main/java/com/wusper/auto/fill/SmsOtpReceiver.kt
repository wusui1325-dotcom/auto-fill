package com.wusper.auto.fill

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat

/**
 * Catches incoming SMS at arrival so a verification code can be offered
 * before the messaging app posts a notification. Does not read the SMS inbox.
 * The confirm UI still only receives the extracted code and a short source.
 */
class SmsOtpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            action != "android.provider.Telephony.SMS_RECEIVED"
        ) {
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!OtpSession.isArmed()) return

        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (_: Exception) {
            null
        } ?: return
        if (messages.isEmpty()) return

        val originating = messages.firstOrNull()?.displayOriginatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { msg ->
            msg.displayMessageBody ?: msg.messageBody.orEmpty()
        }
        val code = OtpParser.extract(title = originating, text = body) ?: return
        val fallback = context.getString(R.string.source_sms)
        val source = SourceLabel.short(
            appLabel = fallback,
            title = originating,
            fallback = fallback
        )
        OtpSession.offerOtp(
            PendingOtp(
                code = code,
                source = source,
                packageName = PACKAGE_SMS,
                notificationKey = "sms:$originating:$code",
                postedAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        const val PACKAGE_SMS = "sms"
    }
}
