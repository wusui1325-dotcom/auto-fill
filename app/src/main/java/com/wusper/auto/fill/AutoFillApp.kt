package com.wusper.auto.fill

import android.app.Application
import com.google.android.material.color.DynamicColors

class AutoFillApp : Application() {
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        prefs = Prefs(this)
        OtpSession.setMasterEnabled(prefs.masterEnabled)
        ListenerRebind.scheduleIfNeeded(this)
    }
}

fun Application.autoFillPrefs(): Prefs {
    return (this as AutoFillApp).prefs
}
