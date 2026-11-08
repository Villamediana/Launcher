package com.aura.launcher

import kotlinx.coroutines.launch
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aura.launcher.ui.AuraRoot
import com.aura.launcher.ui.theme.WallpaperCache
import com.aura.launcher.ui.theme.WallpaperStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val app: AuraApp get() = application as AuraApp

    /** Serialises permission prompts; two overlapping dialogs cancel each other. */
    private val permissionLock = Mutex()
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingPermission?.complete(granted)
            pendingPermission = null
        }

    private val requestPermission: suspend (String) -> Boolean = { permission ->
        // ActivityResult launchers are main-thread-only. Crossing here keeps
        // every caller safe, wherever it runs.
        withContext(Dispatchers.Main.immediate) {
            permissionLock.withLock {
                val deferred = CompletableDeferred<Boolean>()
                pendingPermission = deferred
                runCatching { permissionLauncher.launch(permission) }
                    .onFailure {
                        pendingPermission = null
                        deferred.complete(false)
                    }
                deferred.await()
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Every launch from the launcher opens out of the point touched.
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            com.aura.launcher.ui.LaunchReveal.recordDown(window.decorView, ev.rawX, ev.rawY)
            com.aura.launcher.ui.theme.Ambient.touched()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        paintWallpaperBehindEverything()

        if (intent?.action == com.aura.launcher.updater.UpdateInstallReceiver.ACTION_INSTALL) {
            com.aura.launcher.updater.UpdateInstallReceiver.installFromNotification(app)
        }

        app.permissionRequester = requestPermission

        setContent {
            val settings by app.currentSettings.collectAsState()
            val loaded by app.settingsLoaded.collectAsState()

            // Nothing is drawn until settings are read. The window is
            // transparent over the wallpaper, so this reads as the launcher
            // appearing a beat later — not as a blank screen, and never as the
            // wrong onboarding step correcting itself under the user.
            if (!loaded) return@setContent

            LaunchedEffect(settings.hideStatusBar) {
                hideStatusBar = settings.hideStatusBar
                applySystemBars()
            }

            AuraRoot(app = app, settings = settings)
        }
    }

    /**
     * Puts the user's wallpaper behind the window before anything is composed.
     *
     * This is the fix for the blink on unlock, and the cause is worth writing
     * down. Aura's window is transparent with `windowShowWallpaper`, which is
     * right when no wallpaper of our own is set — the phone's own picture shows
     * through and the launcher floats on it. But nothing is drawn until the
     * settings have been read and the picture decoded, and during those frames
     * the transparent window shows *Android's* wallpaper: the same photograph,
     * uncropped, at whatever zoom the system chose. Then Aura's cropped version
     * arrived and replaced it. Two images of the same photo, a frame apart —
     * which reads exactly as the flash the user described.
     *
     * So the same picture goes in as the window background, cropped the way the
     * home screen crops it, and the wallpaper behind is switched off. The first
     * frame the system paints is then already the right one, and the Compose
     * layer that takes over draws the identical thing.
     *
     * Read from disk rather than from settings on purpose: settings are not
     * loaded yet at this point, and the folder holds the wallpaper or it does
     * not.
     */
    private fun paintWallpaperBehindEverything() {
        val file = WallpaperStore.currentFile(this) ?: return
        // A damaged or unexpectedly large old wallpaper must not prevent the
        // launcher itself from starting. Compose can fall back to paper while
        // the user chooses a replacement.
        val source = runCatching { WallpaperCache.bitmap(file.absolutePath) }.getOrNull() ?: return

        val metrics = resources.displayMetrics
        val cropped = runCatching {
            coverCrop(source, metrics.widthPixels, metrics.heightPixels)
        }.getOrNull() ?: return

        runCatching {
            window.setBackgroundDrawable(BitmapDrawable(resources, cropped))
            // Ours is opaque and covers everything, so the system's copy behind
            // it is now only a second surface for the compositor to blend.
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        }
    }

    /**
     * The picture scaled to fill [width] x [height] and cropped to the middle —
     * the same cover fit `wallpaperBackground` draws with, so the handover from
     * window background to Compose is invisible.
     */
    private fun coverCrop(source: android.graphics.Bitmap, width: Int, height: Int): android.graphics.Bitmap? {
        if (width <= 0 || height <= 0) return null
        val out = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawnWidth = source.width * scale
        val drawnHeight = source.height * scale
        val left = (width - drawnWidth) / 2f
        val top = (height - drawnHeight) / 2f
        canvas.drawBitmap(
            source,
            null,
            android.graphics.RectF(left, top, left + drawnWidth, top + drawnHeight),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        // The same veil Compose paints over the wallpaper, baked in here so the
        // very first frame is already at the brightness the user chose.
        val veil = WallpaperStore.dim(this).coerceIn(0f, 1f)
        if (veil > 0f) {
            canvas.drawColor(
                android.graphics.Color.argb((veil * 255f).toInt(), 0, 0, 0),
            )
        }
        return out
    }

    /**
     * Whether Android's own status strip should be hidden.
     *
     * Held here rather than read from settings at the point of use, because it
     * has to be re-applied at moments that have nothing to do with settings
     * changing — see [applySystemBars].
     */
    private var hideStatusBar = false

    /**
     * Puts the system bars into the requested state.
     *
     * Called from more than one place on purpose. Hiding the bars is not a
     * setting the window remembers: a transient bar shown by a swipe, another
     * app taking focus, or the screen turning off all leave them visible again,
     * and applying this once at startup means it silently stops working after
     * the first time anything else touches the screen.
     */
    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // Set before hiding: it is what makes a swipe from the top bring them
        // back temporarily rather than for good.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hideStatusBar) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onResume() {
        super.onResume()
        com.aura.launcher.ui.theme.Ambient.touched()
        applySystemBars()
        // The once-a-day update look. Quiet unless there is something new, and
        // then it is one notification with an install button.
        app.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { app.updater.checkInBackground(BuildConfig.VERSION_CODE) }
        }
        // Back on the home screen after a bank: if the launcher was allowed to
        // switch its own service on (granted once over adb), it comes back by
        // itself and the "paused" notification goes away.
        if (com.aura.launcher.control.SensitiveApps.wasAutoPaused(this) &&
            com.aura.launcher.control.ScreenController.selfEnable(this)
        ) {
            com.aura.launcher.control.SensitiveApps.clearAutoPause(this)
            com.aura.launcher.control.ScreenControlPausedNotification.dismiss(this)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Coming back from another app, or from the shade, is exactly when the
        // bars reappear on their own.
        if (hasFocus) applySystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == com.aura.launcher.updater.UpdateInstallReceiver.ACTION_INSTALL) {
            com.aura.launcher.updater.UpdateInstallReceiver.installFromNotification(app)
        }
    }

    override fun onDestroy() {
        // A replacement Activity may already have installed its own bridge.
        // Never let the old instance disable the new one during recreation.
        if (app.permissionRequester === requestPermission) {
            app.permissionRequester = { false }
        }
        // A configuration change can destroy this Activity while Android's
        // permission sheet is open. Do not leave the caller waiting forever on
        // the launcher instance that can no longer receive it.
        pendingPermission?.complete(false)
        pendingPermission = null
        super.onDestroy()
    }
}
