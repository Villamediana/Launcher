package com.aura.launcher.shots

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.aura.launcher.ui.home.Sky
import com.aura.launcher.ui.home.SkyKind
import com.aura.launcher.ui.home.drawSky
import com.aura.launcher.ui.home.hash
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Each sky drawn over a photograph, for looking at from here (-Dshots.dir). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkyShots {
    private val dir = System.getProperty("shots.dir")?.let { File(it) }

    private fun shot(name: String, sky: Sky, t: Float) {
        assumeTrue(dir != null)
        val w = 540
        val h = 1100
        val bmp = ImageBitmap(w, h)
        val canvas = Canvas(bmp)
        // A stand-in wallpaper: a warm photo-like gradient.
        val bg = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF6B5A4C.toInt(), 0xFF2E2A26.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.nativeCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        CanvasDrawScope().draw(Density(1.5f), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) { drawSky(sky, t) }
        dir!!.mkdirs()
        File(dir, "sky_$name.png").outputStream().use { bmp.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun all() {
        val flash = 6f + hash(0, 51) * 8f + 0.04f
        shot("storm", Sky(SkyKind.STORM, false, 21, 30f), flash)
        shot("heavy_rain", Sky(SkyKind.HEAVY_RAIN, true, 17, 25f), 3.3f)
        shot("drizzle", Sky(SkyKind.DRIZZLE, true, 19), 3.3f)
        shot("snow", Sky(SkyKind.SNOW, true, -2), 5f)
        shot("night", Sky(SkyKind.CLEAR, false, 18), 5f)
        shot("hot_day", Sky(SkyKind.CLEAR, true, 34), 5f)
        shot("fog", Sky(SkyKind.FOG, true, 12), 5f)
        shot("cloudy", Sky(SkyKind.CLOUDY, true, 20), 5f)
        shot("dusk", Sky(SkyKind.PARTLY, true, 22, hour = 18), 5f)
        shot("cold", Sky(SkyKind.PARTLY, true, 1), 5f)
        shot("rain", Sky(SkyKind.RAIN, true, 18, 10f), 20f)
    }
}
