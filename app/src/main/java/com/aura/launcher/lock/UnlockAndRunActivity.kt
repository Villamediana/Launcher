package com.aura.launcher.lock

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.aura.launcher.AuraApp
import com.aura.launcher.gestures.GestureRunner
import kotlinx.coroutines.launch

/**
 * The only thing standing between a shape drawn on the lock screen and the
 * action it means.
 *
 * It has no interface of its own. It exists because asking the system to
 * unlock requires an Activity — `requestDismissKeyguard` takes one — and an
 * accessibility service is not one. So the strip recognises the shape, this
 * opens for as long as the unlock takes, and the gesture runs or does not.
 *
 * Nothing here ever sees a credential. The face, the fingerprint and the PIN
 * are the system's own screens; this learns one bit — whether they were
 * satisfied — and finishes either way.
 */
class UnlockAndRunActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        val app = application as? AuraApp
        val id = intent?.getStringExtra(EXTRA_GESTURE)
        val gesture = id?.let { key -> app?.gestures?.gestures?.value?.firstOrNull { it.id == key } }
        if (app == null || gesture == null) {
            finish()
            return
        }

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            app.scope.launch { GestureRunner.run(app, gesture) }
            finish()
            return
        }

        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    app.scope.launch { GestureRunner.run(app, gesture) }
                    finish()
                }

                // Refused, or the user changed their mind. The phone stays
                // locked and the shape is simply forgotten.
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            },
        )
    }

    companion object {
        const val EXTRA_GESTURE = "gesture_id"
    }
}
