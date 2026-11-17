package com.wusper.auto.fill

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.wusper.auto.fill.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var unobserve: (() -> Unit)? = null
    private var unobservePrompt: (() -> Unit)? = null
    private var updatingSwitch = false

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Fail-open: overlay still works without this permission. */ }

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
        binding.btnTotp.setOnClickListener {
            startActivity(Intent(this, TotpActivity::class.java))
        }
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
        unobservePrompt = ConfirmPromptController.observeUi {
            if (!isDestroyed && !isFinishing) render(OtpSession.snapshot())
        }
    }

    override fun onResume() {
        super.onResume()
        ListenerRebind.scheduleIfNeeded(this)
        maybeAskNotificationPermission()
        render(OtpSession.snapshot())
    }

    override fun onDestroy() {
        unobserve?.invoke()
        unobserve = null
        unobservePrompt?.invoke()
        unobservePrompt = null
        super.onDestroy()
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!ConfirmPromptController.fallbackAttempted && !ConfirmPromptController.lastDeliveryFailed) {
            return
        }
        if (prefs.notificationPermissionAsked) return
        prefs.notificationPermissionAsked = true
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun render(snap: SessionSnapshot) {
        if (!::binding.isInitialized || isDestroyed) return
        val listenerOn = Permissions.isNotificationListenerEnabled(this)
        val a11yOn = Permissions.isAccessibilityEnabled(this)
        val listenerButton = ConnectionUi.permissionButton(listenerOn, snap.listenerConnected)
        val a11yButton = ConnectionUi.permissionButton(a11yOn, snap.accessibilityConnected)
        val watch = ConnectionUi.watchStatus(
            masterEnabled = snap.masterEnabled,
            armed = snap.armed,
            listenerSettingsOn = listenerOn,
            listenerConnected = snap.listenerConnected,
            accessibilitySettingsOn = a11yOn,
            accessibilityConnected = snap.accessibilityConnected
        )

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
        binding.statusWatch.text = getString(
            when (watch) {
                WatchStatusKind.DISABLED -> R.string.status_disabled
                WatchStatusKind.NEED_PERMISSIONS -> R.string.status_need_permissions
                WatchStatusKind.SERVICES_DISCONNECTED -> R.string.status_services_disconnected
                WatchStatusKind.ARMED -> R.string.status_armed
                WatchStatusKind.STANDBY -> R.string.status_standby
            }
        )

        updatingSwitch = true
        binding.switchMaster.isChecked = snap.masterEnabled
        updatingSwitch = false

        binding.btnListener.isEnabled = ConnectionUi.buttonEnabled(listenerButton)
        binding.btnListener.text = getString(
            when (listenerButton) {
                PermissionButtonKind.CONNECTED -> R.string.btn_listener_granted
                PermissionButtonKind.RECONNECT -> R.string.btn_reconnect_listener
                PermissionButtonKind.GRANT -> R.string.btn_grant_listener
            }
        )
        binding.btnAccessibility.isEnabled = ConnectionUi.buttonEnabled(a11yButton)
        binding.btnAccessibility.text = getString(
            when (a11yButton) {
                PermissionButtonKind.CONNECTED -> R.string.btn_accessibility_granted
                PermissionButtonKind.RECONNECT -> R.string.btn_reconnect_accessibility
                PermissionButtonKind.GRANT -> R.string.btn_grant_accessibility
            }
        )

        binding.btnArm.isEnabled = ConnectionUi.canToggleWatch(
            masterEnabled = snap.masterEnabled,
            armed = snap.armed,
            listenerSettingsOn = listenerOn,
            accessibilitySettingsOn = a11yOn
        )
        binding.btnArm.text = getString(
            if (snap.armed) R.string.btn_disarm else R.string.btn_arm_now
        )

        binding.statusPromptHint.visibility =
            if (ConfirmPromptController.lastDeliveryFailed) View.VISIBLE else View.GONE
    }
}
