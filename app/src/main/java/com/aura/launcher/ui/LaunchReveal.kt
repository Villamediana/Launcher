package com.aura.launcher.ui

import android.app.ActivityOptions
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import java.lang.ref.WeakReference

/**
 * Where the finger last came down, so an app can open *from* it.
 *
 * The system's own clip-reveal: the app's window grows out of the point that
 * was touched, a circle of it widening until it fills the screen, with the
 * launcher still standing behind it. Without options Android plays its
 * generic transition, which from a panel looked like the drawer collapsing
 * back to the home screen and the app appearing afterwards.
 *
 * Recorded in [com.aura.launcher.MainActivity.dispatchTouchEvent], which sees
 * every touch before Compose does, so no row, list or button has to know
 * about it.
 */
object LaunchReveal {
    private var x = 0
    private var y = 0
    private var at = 0L
    private var root: WeakReference<View>? = null

    fun recordDown(view: View, rawX: Float, rawY: Float) {
        root = WeakReference(view)
        x = rawX.toInt()
        y = rawY.toInt()
        at = SystemClock.uptimeMillis()
    }

    /** A touch recent enough to be the one that asked for this launch. */
    private fun fresh(): Boolean = at != 0L && SystemClock.uptimeMillis() - at < FRESH_MS

    /** A small box round the touch — what the app is told it was opened from. */
    fun bounds(): Rect? = if (fresh()) Rect(x - SOURCE_PX, y - SOURCE_PX, x + SOURCE_PX, y + SOURCE_PX) else null

    fun options(): Bundle? {
        if (!fresh()) return null
        val view = root?.get() ?: return null
        val origin = IntArray(2).also { view.getLocationOnScreen(it) }
        return runCatching {
            ActivityOptions.makeClipRevealAnimation(view, x - origin[0], y - origin[1], 0, 0).toBundle()
        }.getOrNull()
    }

    private const val FRESH_MS = 1500L
    private const val SOURCE_PX = 24
}
