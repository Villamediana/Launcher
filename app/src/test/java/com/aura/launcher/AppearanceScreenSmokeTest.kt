package com.aura.launcher

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.aura.launcher.data.AnimatedWallpaper
import com.aura.launcher.data.Settings
import com.aura.launcher.ui.settings.AppearanceSettings
import com.aura.launcher.ui.theme.AnimatedWallpaperBox
import com.aura.launcher.ui.theme.AuraTheme
import com.aura.launcher.ui.theme.ThemeMode
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppearanceScreenSmokeTest {

    @Test
    fun `appearance route composes without terminating its activity`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val app = ApplicationProvider.getApplicationContext<AuraApp>()

        activity.setContent {
            AuraTheme(mode = ThemeMode.LIGHT) {
                AppearanceSettings(app = app, settings = Settings(onboarded = true), update = {})
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(activity.isFinishing)
        controller.close()
    }

    @Test
    fun `animated wallpaper owns a compose frame clock`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()

        activity.setContent {
            AuraTheme(mode = ThemeMode.DARK) {
                AnimatedWallpaperBox(
                    kind = AnimatedWallpaper.NIGHT_SKY,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

        assertFalse(activity.isFinishing)
        controller.close()
    }
}
