package com.wusper.auto.fill

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Starts [ConfirmActivity] as a separate task so it does not hijack MainActivity
 * and is not destroyed when the launcher icon is opened.
 */
object ConfirmLauncher {

    fun confirmIntent(context: Context): Intent {
        return Intent(context, ConfirmActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
        }
    }

    fun start(context: Context): Boolean {
        return try {
            val intent = confirmIntent(context)
            if (Build.VERSION.SDK_INT >= 34) {
                context.startActivity(intent, backgroundStartOptions()?.toBundle())
            } else {
                context.startActivity(intent)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun pendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = confirmIntent(context)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val options = backgroundStartOptions()?.toBundle()
        return if (options != null) {
            PendingIntent.getActivity(context, requestCode, intent, flags, options)
        } else {
            PendingIntent.getActivity(context, requestCode, intent, flags)
        }
    }

    private fun backgroundStartOptions(): ActivityOptions? {
        if (Build.VERSION.SDK_INT < 34) return null
        return try {
            ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
        } catch (_: Exception) {
            ActivityOptions.makeBasic()
        }
    }
}
