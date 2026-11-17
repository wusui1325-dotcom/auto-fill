package com.wusper.auto.fill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionUiTest {

    @Test
    fun buttonsStayClickableWhenEnabledButDisconnected() {
        val kind = ConnectionUi.permissionButton(settingsEnabled = true, connected = false)
        assertEquals(PermissionButtonKind.RECONNECT, kind)
        assertTrue(ConnectionUi.buttonEnabled(kind))
    }

    @Test
    fun buttonsDisableOnlyWhenReallyConnected() {
        val kind = ConnectionUi.permissionButton(settingsEnabled = true, connected = true)
        assertEquals(PermissionButtonKind.CONNECTED, kind)
        assertFalse(ConnectionUi.buttonEnabled(kind))
    }

    @Test
    fun buttonsAskToGrantWhenSettingsOff() {
        val kind = ConnectionUi.permissionButton(settingsEnabled = false, connected = false)
        assertEquals(PermissionButtonKind.GRANT, kind)
        assertTrue(ConnectionUi.buttonEnabled(kind))
    }

    @Test
    fun watchStatusDoesNotClaimArmedWhenServicesAreDown() {
        val status = ConnectionUi.watchStatus(
            masterEnabled = true,
            armed = true,
            listenerSettingsOn = true,
            listenerConnected = false,
            accessibilitySettingsOn = true,
            accessibilityConnected = false
        )
        assertEquals(WatchStatusKind.SERVICES_DISCONNECTED, status)
    }

    @Test
    fun watchStatusArmedOnlyWhenBothServicesConnected() {
        val missingListener = ConnectionUi.watchStatus(
            masterEnabled = true,
            armed = true,
            listenerSettingsOn = true,
            listenerConnected = false,
            accessibilitySettingsOn = true,
            accessibilityConnected = true
        )
        assertEquals(WatchStatusKind.SERVICES_DISCONNECTED, missingListener)

        val ready = ConnectionUi.watchStatus(
            masterEnabled = true,
            armed = true,
            listenerSettingsOn = true,
            listenerConnected = true,
            accessibilitySettingsOn = true,
            accessibilityConnected = true
        )
        assertEquals(WatchStatusKind.ARMED, ready)
    }

    @Test
    fun watchStatusFallsBackToPermissionsOrStandby() {
        assertEquals(
            WatchStatusKind.DISABLED,
            ConnectionUi.watchStatus(false, true, true, true, true, true)
        )
        assertEquals(
            WatchStatusKind.NEED_PERMISSIONS,
            ConnectionUi.watchStatus(true, false, false, false, true, true)
        )
        assertEquals(
            WatchStatusKind.STANDBY,
            ConnectionUi.watchStatus(true, false, true, true, true, true)
        )
    }

    @Test
    fun userCanStopWatchingEvenIfServicesAreDisconnected() {
        assertTrue(
            ConnectionUi.canToggleWatch(
                masterEnabled = true,
                armed = true,
                listenerSettingsOn = true,
                accessibilitySettingsOn = true
            )
        )
        assertFalse(
            ConnectionUi.canToggleWatch(
                masterEnabled = false,
                armed = true,
                listenerSettingsOn = true,
                accessibilitySettingsOn = true
            )
        )
        assertFalse(
            ConnectionUi.canToggleWatch(
                masterEnabled = true,
                armed = false,
                listenerSettingsOn = true,
                accessibilitySettingsOn = false
            )
        )
    }
}
