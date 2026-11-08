package com.aura.launcher.lock

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.aura.launcher.AuraApp
import com.aura.launcher.control.AuraAccessibilityService
import com.aura.launcher.gestures.Dot
import com.aura.launcher.gestures.GestureMatch
import com.aura.launcher.gestures.GestureShape
import com.aura.launcher.gestures.matchStrokes
import kotlinx.coroutines.launch

/**
 * Gestures on the lock screen, without asking anyone for anything first.
 *
 * Android gives an app no way onto the keyguard: no widget, no button, no
 * gesture handler. The one exception is the window type an accessibility
 * service may use, which the launcher already uses for the action pill — it is
 * allowed over other apps and, on most builds, over the keyguard too. Whether
 * "most" includes any particular phone is not documented anywhere, which is
 * why the pad is drawn rather than invisible: if it is on the lock screen, the
 * rest works.
 *
 * What it takes from the keyguard is deliberately almost nothing — a disc in
 * the bottom-right corner, lifted clear of the shortcuts that live down there.
 * The swipe to unlock, the notifications, the camera and the torch all stay
 * exactly where they were. Touch the pad — a tap, or a drag off it — and the
 * window grows to the whole screen, with the dark arriving out of the corner
 * it was pulled from.
 *
 * Nothing runs from here. A recognised shape opens [UnlockAndRunActivity],
 * which asks the system to unlock and does the work only if it says yes.
 */
object LockCanvas {

    private var windowManager: WindowManager? = null
    private var view: LockCanvasView? = null
    private var receiver: BroadcastReceiver? = null

    /**
     * Starts watching the lock screen, if the setting allows it.
     *
     * Called when the service connects and whenever the switch moves. Safe to
     * call twice: the second one is a no-op.
     */
    fun start(service: AuraAccessibilityService) {
        if (receiver != null) return
        val watcher = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> sync(service)
                    Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT -> detach()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            // The keyguard is gone the moment this arrives, and so is the strip.
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { service.registerReceiver(watcher, filter) }
            .onSuccess { receiver = watcher }
            .onFailure { Log.w(TAG, "Could not watch the screen", it) }
        sync(service)
    }

    fun stop(service: AuraAccessibilityService) {
        receiver?.let { runCatching { service.unregisterReceiver(it) } }
        receiver = null
        detach()
    }

    /** Puts the strip up or takes it down, according to the state right now. */
    fun sync(service: AuraAccessibilityService) {
        val app = service.applicationContext as? AuraApp ?: return
        val wanted = app.currentSettings.value.lockGestures &&
            service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (wanted) attach(service) else detach()
    }

    private fun attach(service: AuraAccessibilityService) {
        if (view != null) return
        val manager = service.getSystemService(WindowManager::class.java) ?: return

        val canvas = LockCanvasView(
            context = service,
            onExpand = { expand() },
            onCollapse = { collapse() },
            onShape = { strokes -> resolve(service, strokes) },
        )

        return runCatching {
            manager.addView(canvas, handleParams(service))
            windowManager = manager
            view = canvas
        }.getOrElse { Log.w(TAG, "Could not attach the lock strip", it) }
    }

    fun detach() {
        val manager = windowManager
        val canvas = view
        view = null
        windowManager = null
        if (manager != null && canvas != null) {
            runCatching { manager.removeView(canvas) }
        }
    }

    private fun expand() {
        val manager = windowManager ?: return
        val canvas = view ?: return
        runCatching { manager.updateViewLayout(canvas, fullParams(canvas.context)) }
    }

    private fun collapse() {
        val manager = windowManager ?: return
        val canvas = view ?: return
        runCatching { manager.updateViewLayout(canvas, handleParams(canvas.context)) }
    }

    /**
     * What a finished shape means, and what to do about it.
     *
     * The recogniser is the launcher's own — the same templates, the same
     * scoring, the same gestures taught on the home screen. A shape drawn here
     * is the shape drawn there.
     */
    private fun resolve(
        service: AuraAccessibilityService,
        strokes: List<List<LockCanvasView.PointF>>,
    ): LockCanvasView.Verdict {
        val app = service.applicationContext as? AuraApp ?: return LockCanvasView.Verdict.MISS
        val drawn = strokes.map { stroke -> stroke.map { Dot(it.x, it.y) } }
        val match = matchStrokes(drawn, app.gestures.gestures.value)
        if (match !is GestureMatch.Hit) return LockCanvasView.Verdict.MISS

        // The same stroke, kept as an example. A shape drawn on the keyguard is
        // the same shape drawn on the home screen — one recogniser, one set of
        // templates — and this is where a good half of them are actually drawn,
        // one-handed, without looking.
        app.scope.launch {
            app.gestures.reinforce(
                match.gesture.id,
                GestureShape.normaliseStrokes(drawn),
                match.score,
            )
        }

        // Without "draw over other apps" the launch below is dropped by the
        // system with no error of any kind, which is how a recognised gesture
        // ended up doing nothing at all. Better to say so on the glass.
        if (!android.provider.Settings.canDrawOverlays(service)) {
            Log.w(TAG, "Recognised ${match.gesture.id}, but cannot start an activity")
            return LockCanvasView.Verdict.BLOCKED
        }

        val intent = Intent(service, UnlockAndRunActivity::class.java)
            .putExtra(UnlockAndRunActivity.EXTRA_GESTURE, match.gesture.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching {
            service.startActivity(intent)
            LockCanvasView.Verdict.HIT
        }.getOrElse {
            Log.w(TAG, "Could not ask for the unlock", it)
            LockCanvasView.Verdict.BLOCKED
        }
    }

    /**
     * The pad: a small square in the bottom-right corner.
     *
     * Lifted well clear of the bottom edge, which on a keyguard is somebody
     * else's: the camera and torch shortcuts sit in those corners on most
     * builds, and the gesture-navigation strip runs across the whole of it. A
     * button placed there would be a button that either does nothing or takes
     * the camera away.
     *
     * The window is larger than the disc drawn inside it. A corner is reached
     * for without looking, so the quiet ring of empty space around the mark is
     * deliberate — it is all target.
     */
    private fun handleParams(context: Context): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        return WindowManager.LayoutParams(
            (PAD_DP * density).toInt(),
            (PAD_DP * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            x = (PAD_MARGIN_DP * density).toInt()
            y = (PAD_LIFT_DP * density).toInt()
            windowAnimations = 0
        }
    }

    /**
     * The page: everything, edge to edge.
     *
     * Sized from the display's own bounds rather than left to MATCH_PARENT,
     * which stops at the status bar — the dark reached the clock and left the
     * carrier, the battery and the signal sitting on top of it in the light.
     * The cutout mode is the other half of that: without it the window stops
     * short of the notch as well.
     */
    private fun fullParams(context: Context): WindowManager.LayoutParams {
        val manager = context.getSystemService(WindowManager::class.java)
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager?.currentWindowMetrics?.bounds
        } else {
            null
        }
        val metrics = context.resources.displayMetrics

        return WindowManager.LayoutParams(
            bounds?.width() ?: metrics.widthPixels,
            bounds?.height() ?: metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            windowAnimations = 0
            // ALWAYS only exists from Android 11; on 9 and 10 the closest is
            // SHORT_EDGES, and an unknown mode there is not one to rely on.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private const val TAG = "LockCanvas"

    /** The whole target, disc plus the quiet ring around it. */
    private const val PAD_DP = 76f
    private const val PAD_MARGIN_DP = 8f

    /** Above the keyguard's own shortcut row and the navigation strip. */
    private const val PAD_LIFT_DP = 132f
}
