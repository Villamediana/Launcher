package com.aura.launcher.lock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.ui.graphics.toArgb
import com.aura.launcher.ui.theme.DarkAura

/**
 * The pad in the corner of the lock screen, and the page it becomes.
 *
 * It starts as a mark: a small disc in the bottom-right, with a miniature of
 * the ink inside it, and the only part of the lock screen this takes away from
 * the system. Everything else — the swipe up, the notifications, the camera
 * shortcut — is untouched, because a layer covering the whole keyguard waiting
 * for a gesture would be a layer that had quietly replaced the keyguard.
 *
 * Two ways in, and they end in the same place. Tap it, or drag off it — either
 * opens the page, and the page is blank either way. The drag is a way of
 * asking, not the first stroke of a shape: drawing it in would put a tail on
 * every gesture, leading out of whichever corner the button happens to sit in.
 * Drawing starts with the next touch, wherever it lands.
 *
 * The ink is the launcher's own: four passes from a wide haze to a bright
 * core, and a spark at the head while a finger is down. That is what a shape
 * drawn on the home screen looks like, and a shape drawn here is the same
 * shape — it should not arrive in a different hand.
 *
 * A plain View rather than Compose on purpose: it lives in a window owned by
 * an accessibility service, created before there is any lifecycle to attach a
 * composition to, and it has to draw a line and read a finger.
 */
@SuppressLint("ViewConstructor")
class LockCanvasView(
    context: Context,
    /** Asks for the window to be grown to full screen. */
    private val onExpand: () -> Unit,
    /** Asks for it to shrink back to the handle. */
    private val onCollapse: () -> Unit,
    /** A finished shape, in screen pixels. */
    private val onShape: (List<List<PointF>>) -> Verdict,
) : View(context) {

    data class PointF(val x: Float, val y: Float)

    private enum class Mode { HANDLE, PAPER }

    /** What the last shape turned out to be, for the moment it fades out. */
    enum class Verdict { NONE, HIT, MISS, BLOCKED }

    private var verdict = Verdict.NONE
    private var fading = 1f
    private val strokes = mutableListOf<List<PointF>>()
    private val current = mutableListOf<PointF>()
    private var mode = Mode.HANDLE
    private var drawing = false
    private var downAt = 0f to 0f

    /**
     * The rest of the touch that opened the page is not a drawing.
     *
     * Dragging off the pad is a way of asking for the page, not the first
     * stroke of a shape — so once it has opened, everything until the finger
     * comes up is discarded and the glass waits, blank, for a fresh touch.
     */
    private var opening = false

    // Where the page came from, and how far out it has got. The dark is
    // clipped to a circle growing out of the pad itself — from the pad's own
    // radius, at the pad's own centre — so what expands is the button, not a
    // light being switched off somewhere near it.
    private var revealFrom = 0f to 0f
    private var revealSeed = 0f
    private var reveal = 0f
    private var revealer: android.animation.ValueAnimator? = null

    /** True while the page is folding back into the pad rather than opening. */
    private var closing = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val accent = DarkAura.accent.toArgb()

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** The pad's own ground, and the hairline that separates it from the photo. */
    private val pad = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val padRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        alpha = 46
    }

    /** How far the pad is pressed in, 0..1 — the only thing it does before it opens. */
    private var press = 0f
    private var presser: android.animation.ValueAnimator? = null

    fun clear() {
        strokes.clear()
        current.clear()
        drawing = false
        opening = false
        verdict = Verdict.NONE
        fading = 1f
        removeCallbacks(fade)
        mode = Mode.HANDLE
        revealer?.cancel()
        revealer = null
        reveal = 0f
        presser?.cancel()
        presser = null
        press = 0f
        removeCallbacks(settle)
        removeCallbacks(idle)
        invalidate()
    }

    /**
     * Opens the page from [x], [y], growing to cover the screen.
     *
     * Starts at [seed] — the radius the pad was drawn at — rather than at
     * nothing, so the first frame of the dark is exactly the disc that was
     * pressed and every frame after it is that disc getting bigger. Growing
     * from zero looked like a hole opening *near* the button instead.
     *
     * Deliberately quick and deliberately eased: this is the moment somebody
     * finds out the tap worked, and an animation they have to wait through is
     * an animation they will learn to tap past.
     */
    private fun revealFrom(x: Float, y: Float, seed: Float) {
        revealFrom = x to y
        revealSeed = seed
        reveal = 0f
        closing = false
        revealer?.cancel()
        revealer = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = REVEAL_MS
            interpolator = android.view.animation.DecelerateInterpolator(1.7f)
            addUpdateListener {
                reveal = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Folds the page back into the pad, then gives the keyguard its screen.
     *
     * The same circle, run backwards. A page that simply vanished left nobody
     * any idea where it had gone or how to get it back; shrinking into the
     * corner says the button is still there and this is it.
     */
    private fun closeIntoPad() {
        if (closing) return
        closing = true
        revealer?.cancel()
        val from = reveal.coerceAtLeast(0.001f)
        revealer = android.animation.ValueAnimator.ofFloat(from, 0f).apply {
            duration = CLOSE_MS
            interpolator = android.view.animation.AccelerateInterpolator(1.4f)
            addUpdateListener {
                reveal = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!closing) return
                    // Dropped before clear(), which cancels whatever is in this
                    // field — and what is in this field is the animator whose
                    // end callback we are standing in.
                    revealer = null
                    clear()
                    onCollapse()
                }
            })
            start()
        }
    }

    /** The shape is over when the hand has been still long enough to mean it. */
    private val settle = Runnable {
        if (current.size > 1) strokes.add(current.toList())
        current.clear()
        val drawn = strokes.filter { it.size > 1 }
        if (drawn.isEmpty()) {
            closeIntoPad()
            return@Runnable
        }
        // The shape is answered before the page closes, so the ink can say
        // what happened to it: found, not found, or refused for want of a
        // permission. A drawing that simply disappears is indistinguishable
        // from a feature that is broken — which is exactly how this looked.
        verdict = onShape(drawn)
        invalidate()
        postDelayed(fade, VERDICT_MS)
    }

    /** The ink leaves, and the page with it. */
    private val fade = Runnable { closeIntoPad() }

    /** The page opened and nobody drew. Give the keyguard its screen back. */
    private val idle = Runnable {
        if (strokes.isEmpty() && current.isEmpty()) closeIntoPad()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (mode == Mode.HANDLE) {
            drawPad(canvas)
            return
        }

        // The room going quiet — the same dark the home screen draws under a
        // gesture, at the same strength — arriving out of the handle.
        val saved = canvas.save()
        val (fromX, fromY) = revealFrom
        val far = kotlin.math.hypot(
            maxOf(fromX, width - fromX),
            maxOf(fromY, height - fromY),
        )
        // From the disc's own radius out to the far corner, so the first frame
        // is the button and the last one is the screen.
        val radius = revealSeed + (far - revealSeed) * reveal
        if (reveal < 1f) {
            canvas.clipPath(Path().apply { addCircle(fromX, fromY, radius, Path.Direction.CW) })
        }
        canvas.drawColor(VEIL)

        // The pad's hairline, carried on the edge of the growing circle: the
        // ring is what makes it read as one object expanding rather than a
        // dark shape arriving where the button used to be. Gone by the time
        // the circle is big enough for an edge to be a distraction.
        if (reveal < 1f) {
            val edge = (1f - reveal * 2.4f).coerceIn(0f, 1f)
            if (edge > 0f) {
                padRing.alpha = (86 * edge).toInt()
                padRing.strokeWidth = revealSeed * 0.035f
                canvas.drawCircle(fromX, fromY, radius, padRing)
            }
        }

        val drawn = strokes.filter { it.size >= 2 } + listOf(current.toList()).filter { it.size >= 2 }
        if (drawn.isEmpty()) {
            canvas.restoreToCount(saved)
            return
        }

        val path = Path()
        drawn.forEach { stroke ->
            path.moveTo(stroke.first().x, stroke.first().y)
            stroke.drop(1).forEach { path.lineTo(it.x, it.y) }
        }
        val tint = when (verdict) {
            Verdict.MISS -> MISS
            Verdict.BLOCKED -> MISS
            else -> accent
        }
        // Four passes: a wide haze, then a bright core. Cheaper than a blur
        // and it survives hardware rendering.
        PASSES.forEach { (width, alpha) ->
            ink.color = tint
            ink.alpha = (alpha * fading * 255).toInt()
            ink.strokeWidth = width
            canvas.drawPath(path, ink)
        }

        canvas.restoreToCount(saved)

        // The spark at the head, which is what makes it read as being drawn
        // rather than having appeared.
        if (drawing) {
            val head = drawn.last().last()
            spark.color = accent
            spark.alpha = (0.55f * 255).toInt()
            canvas.drawCircle(head.x, head.y, 22f, spark)
            spark.color = Color.parseColor("#FFF6EB")
            spark.alpha = (0.95f * 255).toInt()
            canvas.drawCircle(head.x, head.y, 7f, spark)
        }
    }

    /**
     * The mark in the corner: a small dark disc with a stroke drawn inside it.
     *
     * It used to be a bar down the edge, which was easy to find and said
     * nothing — a hairline on a lock screen reads as a rendering artefact, not
     * as somewhere to press. What is inside the disc is a miniature of the ink
     * the page draws with: the same accent, the same four passes from haze to
     * core, the same spark at the head. So the button is a small picture of
     * what pressing it is for.
     *
     * The disc is drawn smaller than the window it sits in. The window is the
     * target — a corner is reached for without looking, and a 40dp circle is
     * not something a thumb finds in the dark; the ring around it is empty
     * space that still counts as a press.
     */
    private fun drawPad(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = padRadius()

        pad.color = Color.BLACK
        pad.alpha = (0.34f * 255).toInt()
        canvas.drawCircle(cx, cy, radius, pad)
        padRing.strokeWidth = radius * 0.035f
        padRing.alpha = (46 + 40 * press).toInt().coerceAtMost(255)
        canvas.drawCircle(cx, cy, radius, padRing)

        // A stroke somebody might actually draw, at a twelfth of the size.
        val r = radius * 0.52f
        val glyph = Path().apply {
            moveTo(cx - r, cy + r * 0.55f)
            cubicTo(
                cx - r * 0.35f, cy - r * 1.15f,
                cx + r * 0.35f, cy + r * 1.15f,
                cx + r, cy - r * 0.55f,
            )
        }
        PASSES.forEach { (strokeWidth, alpha) ->
            ink.color = accent
            ink.alpha = (alpha * (0.85f + 0.15f * press) * 255).toInt()
            ink.strokeWidth = strokeWidth * radius / 52f
            canvas.drawPath(glyph, ink)
        }
        spark.color = Color.parseColor("#FFF6EB")
        spark.alpha = (0.9f * 255).toInt()
        canvas.drawCircle(cx + r, cy - r * 0.55f, radius * 0.075f, spark)
    }

    /** Presses the pad in, or lets it back out. Nothing animates while it is idle. */
    private fun press(down: Boolean) {
        presser?.cancel()
        presser = android.animation.ValueAnimator.ofFloat(press, if (down) 1f else 0f).apply {
            duration = 130L
            addUpdateListener {
                press = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Opens the page, out of the pad.
     *
     * Both ways in end here: a tap on the pad, and a drag off it. Where the
     * finger landed does not come into it — what expands is the disc, so the
     * circle is measured from the disc's centre and starts at the disc's
     * radius, whichever corner of the button was actually touched.
     *
     * Both are read *before* the window grows, while the view is still the
     * small one, and are already in screen pixels; the full-screen window sits
     * at the origin, so they carry over unchanged.
     */
    private fun open() {
        val at = IntArray(2).also { getLocationOnScreen(it) }
        val cx = at[0] + width / 2f
        val cy = at[1] + height / 2f
        val seed = padRadius()

        mode = Mode.PAPER
        press = 0f
        presser?.cancel()
        onExpand()
        // After the resize, so the circle is drawn in the page's coordinates.
        post { revealFrom(cx, cy, seed) }
    }

    /** The disc's radius, which is both what is drawn and what the page grows from. */
    private fun padRadius(): Float = minOf(width, height) / 2f * (0.56f - 0.05f * press)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(settle)
                removeCallbacks(idle)
                // The page is already folding away. Drawing into it would put a
                // stroke on a surface that is about to stop existing.
                if (closing) return true
                downAt = event.rawX to event.rawY
                opening = false
                if (mode == Mode.PAPER) {
                    drawing = true
                    current.clear()
                    current.add(PointF(event.rawX, event.rawY))
                    invalidate()
                } else {
                    press(true)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode != Mode.PAPER) {
                    // Dragging off the pad is the other way of opening it, not
                    // the beginning of a shape. The page comes up under the
                    // hand and stays blank: the travel out of the corner would
                    // be a tail on every gesture, and the hand is going to lift
                    // and draw properly anyway.
                    val dx = event.rawX - downAt.first
                    val dy = event.rawY - downAt.second
                    if (kotlin.math.hypot(dx, dy) > slop * 1.6f) {
                        opening = true
                        open()
                        postDelayed(idle, IDLE_MS)
                    }
                    return true
                }
                if (opening) return true
                current.add(PointF(event.rawX, event.rawY))
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val travelled = kotlin.math.hypot(
                    event.rawX - downAt.first,
                    event.rawY - downAt.second,
                )
                if (mode == Mode.HANDLE) {
                    press(false)
                    // A tap on the pad opens the page too. Anything longer that
                    // never left the pad was a finger resting on the corner.
                    if (travelled < slop) {
                        open()
                        postDelayed(idle, IDLE_MS)
                    }
                    return true
                }

                // The hand that opened the page is coming up. It drew nothing,
                // so there is nothing to finish — only the wait for a real one.
                if (opening) {
                    opening = false
                    postDelayed(idle, IDLE_MS)
                    return true
                }

                drawing = false
                if (current.size > 1) strokes.add(current.toList())
                current.clear()
                invalidate()
                if (strokes.isEmpty()) {
                    // Opened, touched, nothing drawn: close rather than sit
                    // there dark over somebody's lock screen.
                    postDelayed(idle, IDLE_MS)
                } else {
                    postDelayed(settle, STROKE_GAP_MS)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(fade)
        revealer?.cancel()
        revealer = null
        presser?.cancel()
        presser = null
        removeCallbacks(settle)
        removeCallbacks(idle)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val STROKE_GAP_MS = 420L

        /** How long the open page waits for a hand before closing itself. */
        const val IDLE_MS = 4_000L

        /** Long enough to be seen coming out of the pad, short enough not to wait on. */
        const val REVEAL_MS = 300L

        /** Going back is quicker than coming out: it is over, not arriving. */
        const val CLOSE_MS = 220L

        /** How long the answer stays on the glass before the page closes. */
        const val VERDICT_MS = 420L

        /** GestureLayer's colour for a shape nothing recognised. */
        val MISS = Color.parseColor("#969288")

        /** GestureVeil's own colour and strength. */
        val VEIL = Color.argb((0.62f * 255).toInt(), 0x08, 0x07, 0x06)

        /** GestureLayer's four passes: width to alpha. */
        val PASSES = listOf(30f to 0.07f, 18f to 0.14f, 9f to 0.34f, 3.5f to 0.95f)
    }
}
