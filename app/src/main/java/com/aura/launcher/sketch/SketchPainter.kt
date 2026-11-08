package com.aura.launcher.sketch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws elements onto an Android canvas already set to board coordinates.
 *
 * One painter for the screen, the picture the model looks at and the
 * thumbnail in settings: what the model sees is exactly what the hand sees.
 */
class SketchPainter(
    /** The hand's ink: the gesture stroke's colour. */
    private val hand: Int = ACCENT,
    private val picture: (String) -> Bitmap?,
) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val bitmap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()

    /**
     * [plain] draws the hand's ink as its bright core alone, a little bolder:
     * the picture the model reads, where the haze only blurs the letters.
     */
    fun draw(canvas: Canvas, e: Element, alpha: Float = 1f, plain: Boolean = false) {
        val colour = e.color?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: INK
        stroke.color = colour
        stroke.alpha = (255 * alpha).toInt()
        when (e) {
            is Element.Ink -> {
                // The gesture stroke, exactly: a wide haze, then a bright
                // core, in the gesture's own colour unless it has one.
                smooth(e.points)
                stroke.color = e.color?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: hand
                (if (plain) listOf(1.5f to 1f) else HAZE).forEach { (times, a) ->
                    stroke.strokeWidth = e.width * times
                    stroke.alpha = (255 * a * alpha).toInt().coerceIn(0, 255)
                    canvas.drawPath(path, stroke)
                }
            }
            is Element.Shape -> {
                stroke.strokeWidth = SHAPE_WIDTH
                val b = e.box
                path.reset()
                when (e.kind) {
                    ShapeKind.RECT -> {
                        val r = min(10f, min(b.width, b.height) * 0.08f)
                        path.addRoundRect(b.left, b.top, b.right, b.bottom, r, r, Path.Direction.CW)
                    }
                    ShapeKind.ELLIPSE -> path.addOval(b.left, b.top, b.right, b.bottom, Path.Direction.CW)
                    ShapeKind.TRIANGLE -> {
                        path.moveTo(b.centerX, b.top); path.lineTo(b.right, b.bottom); path.lineTo(b.left, b.bottom); path.close()
                    }
                    ShapeKind.DIAMOND -> {
                        path.moveTo(b.centerX, b.top); path.lineTo(b.right, b.centerY)
                        path.lineTo(b.centerX, b.bottom); path.lineTo(b.left, b.centerY); path.close()
                    }
                }
                fillPath(canvas, e.fill, alpha)
                canvas.drawPath(path, stroke)
            }
            is Element.Line -> {
                stroke.strokeWidth = SHAPE_WIDTH
                val p = e.points
                path.reset()
                path.moveTo(p[0], p[1])
                for (i in 2 until p.size step 2) path.lineTo(p[i], p[i + 1])
                if (e.closed) {
                    path.close()
                    fillPath(canvas, e.fill, alpha)
                }
                canvas.drawPath(path, stroke)
                if (e.arrow && p.size >= 4) arrowHead(canvas, p[p.size - 4], p[p.size - 3], p[p.size - 2], p[p.size - 1])
            }
            is Element.Text -> {
                text.color = colour
                text.alpha = (255 * alpha).toInt()
                text.textSize = e.size
                e.text.lines().forEachIndexed { i, line ->
                    canvas.drawText(line, e.x, e.y + e.size * 0.95f + i * e.size * 1.25f, text)
                }
            }
            is Element.Picture -> {
                val b = e.box
                rect.set(b.left, b.top, b.right, b.bottom)
                val image = e.file.takeIf { it.isNotEmpty() }?.let(picture)
                if (image == null) {
                    // Still being looked for: the place it will go, breathing.
                    fill.color = INK
                    fill.alpha = (20 * alpha).toInt()
                    canvas.drawRoundRect(rect, CORNER, CORNER, fill)
                    return
                }
                val save = canvas.save()
                path.reset()
                path.addRoundRect(rect, CORNER, CORNER, Path.Direction.CW)
                canvas.clipPath(path)
                bitmap.alpha = (255 * alpha).toInt()
                // Cover the box, as a photo frame does.
                val scale = maxOf(b.width / image.width, b.height / image.height)
                val w = image.width * scale
                val h = image.height * scale
                rect.set(b.centerX - w / 2, b.centerY - h / 2, b.centerX + w / 2, b.centerY + h / 2)
                canvas.drawBitmap(image, null, rect, bitmap)
                canvas.restoreToCount(save)
            }
        }
    }

    /** The spark at the head of a stroke being drawn, as on the gesture surface. */
    fun spark(canvas: Canvas, x: Float, y: Float, unit: Float) {
        fill.color = hand
        fill.alpha = (255 * 0.55f).toInt()
        canvas.drawCircle(x, y, 22f * unit, fill)
        fill.color = SPARK
        fill.alpha = (255 * 0.95f).toInt()
        canvas.drawCircle(x, y, 7f * unit, fill)
    }

    /**
     * A soft light around [box], for something arriving, held, or being
     * thought about. [unit] is one screen pixel in board units, so the light
     * is the same on screen at any zoom.
     */
    fun glow(canvas: Canvas, box: Box, strength: Float, unit: Float, colour: Int = hand) {
        if (strength <= 0f) return
        val b = box.inflate(12f * unit)
        rect.set(b.left, b.top, b.right, b.bottom)
        listOf(26f to 0.06f, 14f to 0.12f, 4f to 0.30f).forEach { (width, a) ->
            stroke.color = colour
            stroke.alpha = (255 * a * strength).toInt().coerceIn(0, 255)
            stroke.strokeWidth = width * unit
            canvas.drawRoundRect(rect, 18f * unit, 18f * unit, stroke)
        }
    }

    private fun fillPath(canvas: Canvas, colour: String?, alpha: Float) {
        val c = colour?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: return
        fill.color = c
        fill.alpha = (255 * alpha).toInt()
        canvas.drawPath(path, fill)
    }

    private fun arrowHead(canvas: Canvas, fx: Float, fy: Float, tx: Float, ty: Float) {
        val angle = atan2(ty - fy, tx - fx)
        val spread = 0.5f
        val len = 18f
        path.reset()
        path.moveTo(tx - len * cos(angle - spread), ty - len * sin(angle - spread))
        path.lineTo(tx, ty)
        path.lineTo(tx - len * cos(angle + spread), ty - len * sin(angle + spread))
        canvas.drawPath(path, stroke)
    }

    /** Through the midpoints, so a stroke sampled at the finger's pace still reads as one curve. */
    private fun smooth(p: FloatArray) {
        path.reset()
        path.moveTo(p[0], p[1])
        if (p.size == 2) {
            path.lineTo(p[0] + 0.1f, p[1])
            return
        }
        var i = 2
        while (i < p.size - 2) {
            val mx = (p[i] + p[i + 2]) / 2f
            val my = (p[i + 1] + p[i + 3]) / 2f
            path.quadTo(p[i], p[i + 1], mx, my)
            i += 2
        }
        path.lineTo(p[p.size - 2], p[p.size - 1])
    }

    /**
     * [elements] seen through [view] (board coordinates) as a bitmap at most
     * [maxSide] pixels across, on the board's own ground.
     */
    fun render(elements: List<Element>, view: Box, maxSide: Int, plain: Boolean = false): Bitmap {
        val scale = maxSide / maxOf(view.width, view.height)
        val out = Bitmap.createBitmap(
            (view.width * scale).toInt().coerceAtLeast(1),
            (view.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(out)
        canvas.drawColor(GROUND)
        canvas.scale(scale, scale)
        canvas.translate(-view.left, -view.top)
        elements.filter { it.bounds.intersects(view) }.forEach { draw(canvas, it, plain = plain) }
        return out
    }

    companion object {
        /** The room the gesture veil darkens, now solid. */
        val GROUND = Color.rgb(0x0F, 0x0D, 0x0B)
        val INK = Color.rgb(0xF0, 0xEA, 0xDF)
        /** The dark theme's accent, for when no theme is at hand. */
        val ACCENT = Color.rgb(0xDE, 0x8B, 0x5C)
        val SPARK = Color.rgb(0xFF, 0xF6, 0xEB)
        const val SHAPE_WIDTH = 3.5f
        /** The gesture stroke's core width, in screen pixels at zoom 1. */
        const val PEN_WIDTH = 3.5f
        /** The gesture stroke's four passes (GestureLayer): width over the core's, and strength. */
        private val HAZE = listOf(30f / 3.5f to 0.07f, 18f / 3.5f to 0.14f, 9f / 3.5f to 0.34f, 1f to 0.95f)
        const val CORNER = 14f
    }
}
