package com.wusper.auto.fill

enum class PermissionButtonKind {
    GRANT,
    RECONNECT,
    CONNECTED
}

enum class WatchStatusKind {
    DISABLED,
    NEED_PERMISSIONS,
    SERVICES_DISCONNECTED,
    ARMED,
    STANDBY
}

/**
 * Pure UI rules for permission buttons and the watch status line
 * after an overlay update can leave services enabled but unbound.
 */
object ConnectionUi {

    fun permissionButton(settingsEnabled: Boolean, connected: Boolean): PermissionButtonKind {
        return when {
            settingsEnabled && connected -> PermissionButtonKind.CONNECTED
            settingsEnabled -> PermissionButtonKind.RECONNECT
            else -> PermissionButtonKind.GRANT
        }
    }

    fun buttonEnabled(kind: PermissionButtonKind): Boolean = kind != PermissionButtonKind.CONNECTED

    fun watchStatus(
        masterEnabled: Boolean,
        armed: Boolean,
        listenerSettingsOn: Boolean,
        listenerConnected: Boolean,
        accessibilitySettingsOn: Boolean,
        accessibilityConnected: Boolean
    ): WatchStatusKind {
        if (!masterEnabled) return WatchStatusKind.DISABLED
        if (!listenerSettingsOn || !accessibilitySettingsOn) {
            return WatchStatusKind.NEED_PERMISSIONS
        }
        if (!listenerConnected || !accessibilityConnected) {
            return WatchStatusKind.SERVICES_DISCONNECTED
        }
        return if (armed) WatchStatusKind.ARMED else WatchStatusKind.STANDBY
    }

    /**
     * The armed flag is not cleared when services drop. The user can always
     * stop watching, and can start watching once both settings toggles are on.
     */
    fun canToggleWatch(
        masterEnabled: Boolean,
        armed: Boolean,
        listenerSettingsOn: Boolean,
        accessibilitySettingsOn: Boolean
    ): Boolean {
        if (!masterEnabled) return false
        if (armed) return true
        return listenerSettingsOn && accessibilitySettingsOn
    }
}
