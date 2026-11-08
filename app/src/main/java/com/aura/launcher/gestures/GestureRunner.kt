package com.aura.launcher.gestures

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.util.Log
import com.aura.launcher.AuraApp
import com.aura.launcher.control.ScreenController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Running the steps behind a gesture, in order, with a value between them.
 *
 * Almost nothing here is new capability — every step is something the launcher
 * already does from a screen somewhere, called from a different place. What is
 * new is the thread between them: each step may produce a value, and the steps
 * ahead may use everything produced so far. That one implicit stream is what
 * turns a list of shortcuts into something worth calling a workflow without
 * asking somebody to name variables or draw wires on a phone.
 *
 * Steps run in order and each waits for the last. "Take what is copied, then
 * send it to him" only means anything that way round, and running them together
 * would send whatever happened to be in the variable first.
 */
object GestureRunner {

    suspend fun run(app: AuraApp, gesture: Gesture) {
        Log.i(TAG, "gesture ${gesture.name}: ${gesture.actions.size} step(s)")
        var carried = ""
        for (action in gesture.actions) {
            val produced = runCatching { perform(app, action, carried) }
                .onFailure { Log.w(TAG, "Step failed: ${action.summary()}", it) }
                .getOrNull()
            // Every useful result remains available to the steps ahead.
            carried = appendCarried(carried, produced)
        }
    }

    /** @return what this step produced, or null when it produces nothing. */
    private suspend fun perform(app: AuraApp, action: GestureAction, carried: String): String? =
        when (action) {
            is GestureAction.AppShortcut -> {
                // Straight to the app's own shortcut, exactly as a long press
                // on its icon would. No screen driving, nothing to go stale.
                val entry = app.appRepository.apps.value
                    .firstOrNull { it.packageName == action.packageName }
                val shortcut = entry?.let { e ->
                    app.appRepository.shortcutsFor(e).firstOrNull { it.id == action.shortcutId }
                }
                if (shortcut == null) {
                    Log.w(TAG, "Shortcut gone: ${action.appLabel} / ${action.label}")
                } else {
                    withContext(Dispatchers.Main) { app.appRepository.launchShortcut(shortcut) }
                }
                null
            }

            is GestureAction.SendText -> {
                val body = fillCarried(action.text, carried)
                // The app's own composer, with the text in it. Deliberately not
                // the send itself: a shape can be drawn by accident, and a
                // message that has left cannot be taken back.
                runCatching {
                    app.startActivity(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .setPackage(action.packageName)
                            .putExtra(Intent.EXTRA_TEXT, body)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { Log.w(TAG, "Could not hand text to ${action.appLabel}", it) }
                null
            }

            is GestureAction.OpenLink -> {
                val url = fillCarried(action.url, carried).trim().let {
                    if (it.startsWith("http", true) || it.contains("://")) it else "https://$it"
                }
                runCatching {
                    app.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { Log.w(TAG, "Could not open $url", it) }
                null
            }

            is GestureAction.Clipboard -> clipboard(app, action, carried)

            is GestureAction.Wait -> {
                delay(action.millis.coerceIn(0L, MAX_WAIT_MS))
                null
            }

            is GestureAction.OpenApp -> {
                withContext(Dispatchers.Main) {
                    val entry = app.appRepository.apps.value
                        .firstOrNull { it.packageName == action.packageName }
                    if (entry != null) app.appRepository.launch(entry)
                    else launchByPackage(app, action.packageName, action.label)
                }
                null
            }

            GestureAction.ShowGestures -> {
                // Only a flag. The list is drawn by the launcher's own
                // composition, which is the only thing that knows where the
                // screen is and what it looks like.
                GestureHelp.show()
                null
            }

            is GestureAction.Torch -> {
                torch(app, action.on)
                null
            }

            is GestureAction.System -> {
                ScreenController.globalAction(action.action)
                null
            }
        }

    /** The flashlight, from a shape. */
    private fun torch(app: AuraApp, on: Boolean) {
        val manager = app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        runCatching {
            val id = manager.cameraIdList.firstOrNull { camera ->
                manager.getCameraCharacteristics(camera)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            manager.setTorchMode(id, on)
        }.onFailure { Log.w(TAG, "Could not control the flashlight", it) }
    }

    private suspend fun clipboard(
        app: AuraApp,
        action: GestureAction.Clipboard,
        carried: String,
    ): String? = withContext(Dispatchers.Main) {
        val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@withContext null
        if (action.read) {
            runCatching {
                manager.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
            }.getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            runCatching {
                manager.setPrimaryClip(
                    ClipData.newPlainText("Launcher", fillCarried(action.text, carried))
                )
            }
            null
        }
    }

    private fun launchByPackage(app: AuraApp, packageName: String, label: String) {
        runCatching {
            app.packageManager.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { app.startActivity(it) }
        }.onFailure { Log.w(TAG, "Could not open $label", it) }
    }



    private const val TAG = "GestureRunner"

    /** Long enough for an app to arrive; short enough not to look like a hang. */
    private const val MAX_WAIT_MS = 10_000L
}
