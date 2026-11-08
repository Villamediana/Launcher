package com.aura.launcher.updater

import android.util.Log

/**
 * Self-updater events, forwarded to logcat.
 *
 * See [com.aura.launcher.notifications.NotificationDiagnostics] and
 * [com.aura.launcher.ai.CanvasDiagnostics] for the same story: an in-memory
 * ring buffer became a Settings row that showed it, the row got removed with
 * the rest of the diagnostics page, and what is left is a shim over [Log.d]
 * that spares every updater call site from having to change.
 */
object UpdaterDiagnostics {
    fun log(tag: String, message: String) {
        Log.d("updater/$tag", message)
    }
}
