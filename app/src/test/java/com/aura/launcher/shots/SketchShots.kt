package com.aura.launcher.shots

import android.graphics.Bitmap
import com.aura.launcher.sketch.Box
import com.aura.launcher.sketch.Element
import com.aura.launcher.sketch.ShapeKind
import com.aura.launcher.sketch.SketchPainter
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** A board before and after the model tidied it, for looking at from here (AURA_SHOTS=dir). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SketchShots {
    private val dir = System.getenv("AURA_SHOTS")?.let { File(it) }

    private fun wobble(vararg corners: Float): FloatArray {
        val out = ArrayList<Float>()
        for (i in 0 until corners.size / 2 - 1) {
            val ax = corners[i * 2]; val ay = corners[i * 2 + 1]
            val bx = corners[i * 2 + 2]; val by = corners[i * 2 + 3]
            for (s in 0..12) {
                val t = s / 12f
                out += ax + (bx - ax) * t + sin(t * 9f + i) * 2.5f
                out += ay + (by - ay) * t + cos(t * 7f + i) * 2.5f
            }
        }
        return out.toFloatArray()
    }

    @Test fun board() {
        assumeTrue(dir != null)
        val painter = SketchPainter(SketchPainter.ACCENT) { null }
        val rough = listOf(
            Element.Ink("1", wobble(60f, 200f, 260f, 200f, 260f, 360f, 60f, 360f, 60f, 200f), 4.5f),
            Element.Ink("2", wobble(50f, 205f, 160f, 110f, 270f, 205f), 4.5f),
            Element.Ink("3", wobble(330f, 260f, 360f, 230f, 390f, 260f), 4.5f),
        )
        val tidy = listOf(
            Element.Shape("4", ShapeKind.RECT, Box(60f, 200f, 260f, 360f)),
            Element.Line("5", floatArrayOf(50f, 205f, 160f, 110f, 270f, 205f), closed = true, fill = "#7A3E2A", color = "#DE8B5C"),
            Element.Shape("6", ShapeKind.RECT, Box(140f, 290f, 180f, 360f), fill = "#3A2E26"),
            Element.Text("7", "2 + 3 = 5", 320f, 230f, 34f),
            Element.Line("8", floatArrayOf(300f, 420f, 420f, 330f), arrow = true),
            Element.Shape("9", ShapeKind.ELLIPSE, Box(330f, 400f, 470f, 470f), color = "#97A585"),
            Element.Text("10", "jardim", 360f, 422f, 22f, "#97A585"),
        )
        dir!!.mkdirs()
        val view = Box(0f, 60f, 540f, 520f)
        listOf("before" to rough, "after" to tidy).forEach { (name, elements) ->
            val bmp = painter.render(elements, view, 1080)
            if (name == "after") {
                val canvas = android.graphics.Canvas(bmp)
                val scale = 1080 / view.width
                canvas.scale(scale, scale); canvas.translate(-view.left, -view.top)
                painter.glow(canvas, tidy[3].bounds, 0.8f, 1f / scale)
            }
            File(dir, "sketch_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
