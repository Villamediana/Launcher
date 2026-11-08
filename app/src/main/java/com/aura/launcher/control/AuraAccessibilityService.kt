package com.aura.launcher.control

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * The launcher's accessibility service.
 *
 * Android gives no other way to draw a gesture strip over the lock screen and
 * other apps, or to press back, recents or the shade on the user's behalf. The
 * grant in system settings is the whole security model, which is why nothing
 * here tries to be clever.
 *
 * Only window changes are processed, and only for stepping aside for banking
 * apps, which refuse to run while any accessibility service is enabled.
 */
class AuraAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Back on, however it happened — the notice offering the way back has
        // nothing left to offer.
        SensitiveApps.clearAutoPause(this)
        ScreenControlPausedNotification.dismiss(this)
        // The lock-screen strip. Started here because this is the moment the
        // service has a window to hang one on.
        com.aura.launcher.lock.LockCanvas.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        SensitiveApps.pauseIfSensitive(this, pkg)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        com.aura.launcher.lock.LockCanvas.stop(this)
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        com.aura.launcher.lock.LockCanvas.stop(this)
        instance = null
        super.onDestroy()
    }

    companion object {
        /** Set while the service is bound. Written from the binder thread, hence @Volatile. */
        @Volatile
        var instance: AuraAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}
