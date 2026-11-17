package com.wusper.auto.fill

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.wusper.auto.fill.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var unobserve: (() -> Unit)? = null
    private var updatingSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = application.autoFillPrefs()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.toolbar.title = getString(R.string.app_name)
        binding.cardFirstRun.visibility = if (prefs.firstRunDone) View.GONE else View.VISIBLE
        binding.btnFirstRunGotIt.setOnClickListener {
            prefs.firstRunDone = true
            binding.cardFirstRun.visibility = View.GONE
        }

        binding.switchMaster.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            prefs.masterEnabled = checked
            OtpSession.setMasterEnabled(checked)
            render(OtpSession.snapshot())
        }
        binding.btnListener.setOnClickListener { Permissions.openNotificationListenerSettings(this) }
        binding.btnAccessibility.setOnClickListener { Permissions.openAccessibilitySettings(this) }
        binding.btnArm.setOnClickListener {
            if (OtpSession.isArmed()) {
                OtpSession.stopWatching()
            } else {
                OtpSession.manualArm()
            }
            render(OtpSession.snapshot())
        }

        unobserve = OtpSession.observe { snap ->
            if (!isDestroyed && !isFinishing) render(snap)
        }
    }

    override fun onResume() {
        super.onResume()
        render(OtpSession.snapshot())
    }

    override fun onDestroy() {
        unobserve?.invoke()
        unobserve = null
        super.onDestroy()
    }

    private fun render(snap: SessionSnapshot) {
        if (!::binding.isInitialized || isDestroyed) return
        val listenerOn = Permissions.isNotificationListenerEnabled(this)
        val a11yOn = Permissions.isAccessibilityEnabled(this)
        val ready = listenerOn && a11yOn

        binding.statusListener.text = getString(
            when {
                listenerOn && snap.listenerConnected -> R.string.status_listener_on
                listenerOn -> R.string.status_listener_enabled_disconnected
                else -> R.string.status_listener_off
            }
        )
        binding.statusAccessibility.text = getString(
            when {
                a11yOn && snap.accessibilityConnected -> R.string.status_accessibility_on
                a11yOn -> R.string.status_accessibility_enabled_disconnected
                else -> R.string.status_accessibility_off
            }
        )
        binding.statusWatch.text = when {
            !snap.masterEnabled -> getString(R.string.status_disabled)
            !ready -> getString(R.string.status_need_permissions)
            snap.armed -> getString(R.string.status_armed)
            else -> getString(R.string.status_standby)
        }

        updatingSwitch = true
        binding.switchMaster.isChecked = snap.masterEnabled
        updatingSwitch = false

        binding.btnListener.isEnabled = !listenerOn
        binding.btnListener.text = getString(
            if (listenerOn) R.string.btn_listener_granted else R.string.btn_grant_listener
        )
        binding.btnAccessibility.isEnabled = !a11yOn
        binding.btnAccessibility.text = getString(
            if (a11yOn) R.string.btn_accessibility_granted else R.string.btn_grant_accessibility
        )

        val canWatch = snap.masterEnabled && ready
        binding.btnArm.isEnabled = canWatch
        binding.btnArm.text = getString(
            if (snap.armed) R.string.btn_disarm else R.string.btn_arm_now
        )
    }
}
