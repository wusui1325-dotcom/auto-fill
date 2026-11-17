package com.wusper.auto.fill

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("auto_fill", Context.MODE_PRIVATE)

    var masterEnabled: Boolean
        get() = sp.getBoolean(KEY_MASTER, true)
        set(value) {
            sp.edit().putBoolean(KEY_MASTER, value).apply()
        }

    var firstRunDone: Boolean
        get() = sp.getBoolean(KEY_FIRST_RUN, false)
        set(value) {
            sp.edit().putBoolean(KEY_FIRST_RUN, value).apply()
        }

    private companion object {
        const val KEY_MASTER = "master_enabled"
        const val KEY_FIRST_RUN = "first_run_done"
    }
}
