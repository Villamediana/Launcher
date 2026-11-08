package com.aura.launcher.shots

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import com.aura.launcher.sketch.Assist
import com.aura.launcher.sketch.Box
import com.aura.launcher.sketch.Element
import com.aura.launcher.sketch.ElementCodec
import com.aura.launcher.sketch.Lasso
import com.aura.launcher.sketch.SketchPainter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * What the model is sent for a circle, on boards written "by hand", and what
 * its reply makes of them (AURA_PROBE=dir). Phase one writes each request;
 * a script asks the model and saves reply.txt; phase two applies it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SketchProbe {
    private val dir = System.getenv("AURA_PROBE")?.let { File(it) }
    private val painter = SketchPainter(SketchPainter.ACCENT) { null }
    private val rnd = Random(3)
    private var n = 0

    /** Text as strokes: each glyph contour walked and wobbled a little, as a finger would. */
    private fun hand(text: String, x: Float, y: Float, size: Float): List<Element.Ink> {
        val paint = Paint().apply { textSize = size; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL) }
        val out = ArrayList<Element.Ink>()
        text.lines().forEachIndexed { line, s ->
            val path = Path()
            paint.getTextPath(s, 0, s.length, x, y + size + line * size * 1.4f, path)
            val measure = PathMeasure(path, false)
            do {
                val len = measure.length
                if (len < 4f) continue
                val pts = ArrayList<Float>()
                val pos = FloatArray(2)
                var d = 0f
                while (d <= len) {
                    measure.getPosTan(d, pos, null)
                    pts += pos[0] + rnd.nextFloat() * 1.6f - 0.8f
                    pts += pos[1] + rnd.nextFloat() * 1.6f - 0.8f
                    d += 3f
                }
                out += Element.Ink("h${n++}", pts.toFloatArray(), 3.5f)
            } while (measure.nextContour())
        }
        return out
    }

    private fun wobbly(vararg corners: Float): Element.Ink {
        val pts = ArrayList<Float>()
        for (i in 0 until corners.size / 2 - 1) {
            for (s in 0..14) {
                val t = s / 14f
                pts += corners[i * 2] + (corners[i * 2 + 2] - corners[i * 2]) * t + rnd.nextFloat() * 5f - 2.5f
                pts += corners[i * 2 + 1] + (corners[i * 2 + 3] - corners[i * 2 + 1]) * t + rnd.nextFloat() * 5f - 2.5f
            }
        }
        return Element.Ink("w${n++}", pts.toFloatArray(), 3.5f)
    }

    private fun circle(around: Box): Element.Ink {
        // Loose, as a finger draws it — but an ellipse, so it still cuts corners.
        val rx = around.width / 2 * 1.2f + 24f
        val ry = around.height / 2 * 1.2f + 24f
        val pts = FloatArray(120) { i ->
            val a = (i / 2) / 59f * 2.05f * Math.PI.toFloat()
            if (i % 2 == 0) around.centerX + rx * cos(a) else around.centerY + ry * sin(a)
        }
        return Element.Ink("circle", pts, 3.5f)
    }

    private val scenes: Map<String, List<Element>> by lazy {
        mapOf(
            "sum" to hand("2 + 3 =", 60f, 80f, 64f),
            "house_instruction" to listOf(
                wobbly(60f, 200f, 260f, 200f, 260f, 360f, 60f, 360f, 60f, 200f),
                wobbly(50f, 205f, 160f, 110f, 270f, 205f),
                wobbly(140f, 360f, 140f, 290f, 180f, 290f, 180f, 360f),
            ) + hand("deixa bonita", 300f, 230f, 40f),
            "picture" to hand("foto de um gato", 60f, 80f, 48f),
            "shapes" to listOf(
                wobbly(80f, 100f, 200f, 100f, 200f, 200f, 80f, 200f, 80f, 100f),
                wobbly(300f, 210f, 360f, 100f, 420f, 210f, 300f, 210f),
            ),
            "mindmap" to hand("viagem pro Chile", 60f, 60f, 44f) + hand("mapa mental", 120f, 140f, 34f),
            "flow" to hand("acordar > café > trabalho", 40f, 60f, 36f) + hand("fluxograma", 140f, 130f, 32f),
            "list_table" to hand("pão 5\nleite 7\novos 12", 60f, 60f, 40f) + hand("tabela", 300f, 120f, 40f),
        )
    }

    @Test fun ask() {
        assumeTrue(dir != null && System.getenv("AURA_PROBE_PHASE") == "ask")
        scenes.forEach { (name, elements) ->
            val out = File(dir, name).apply { mkdirs() }
            val all = elements.map { it.bounds }.reduce(Box::union)
            val ring = circle(all)
            val circled = Lasso.enclosed(ring.points, elements)
            val b = ring.bounds
            val view = b.inflate(maxOf(b.width, b.height) * 0.35f)
            val pic = painter.render(elements, view, 1024, plain = true)
            File(out, "image.jpg").outputStream().use { pic.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            painter.render(elements + ring, view, 720).let { bmp -> File(out, "before.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            File(out, "system.txt").writeText(Assist.SYSTEM)
            File(out, "describe.json").writeText(Assist.describe(elements, circled.toSet(), view, b))
            File(out, "board.json").writeText(JsonArray(elements.map { ElementCodec.write(it) }).toString())
            File(out, "circled.txt").writeText(circled.joinToString("\n"))
            File(out, "view.txt").writeText("${view.left} ${view.top} ${view.right} ${view.bottom}")
        }
    }

    @Test fun apply() {
        assumeTrue(dir != null && System.getenv("AURA_PROBE_PHASE") == "apply")
        dir!!.listFiles { f -> File(f, "reply.txt").exists() }!!.forEach { out ->
            var k = 0
            val board = Json.parseToJsonElement(File(out, "board.json").readText()).jsonArray
                .mapNotNull { ElementCodec.read(it.jsonObject) { "x${k++}" } }
            val circled = File(out, "circled.txt").readText().lines().filter { it.isNotBlank() }
            val changes = Assist.parse(File(out, "reply.txt").readText(), circled) { "m${k++}" }
            val after = Assist.apply(board, changes)
            val (l, t, r, bt) = File(out, "view.txt").readText().split(" ").map { it.toFloat() }
            val bmp = painter.render(after ?: board, Box(l, t, r, bt), 720)
            File(out, if (after == null) "after_REJECTED.png" else "after.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
