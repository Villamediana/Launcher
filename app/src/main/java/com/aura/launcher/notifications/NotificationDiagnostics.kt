package com.aura.launcher.notifications

import android.util.Log

/**
 * Notification pipeline events, forwarded to logcat.
 *
 * There used to be an in-memory ring buffer here with a Settings row that
 * displayed it, so a user without USB debugging could still read what the
 * pipeline did. The Settings row is gone — the last big cleanup dropped it —
 * so what is left is a thin adapter over [Log.d] that keeps the call sites
 * unchanged. Deleting the object would touch every notification handler; a
 * one-line shim keeps them all where they are.
 */
object NotificationDiagnostics {
    fun log(tag: String, message: String) {
        Log.d("notif/$tag", message)
    }
}
