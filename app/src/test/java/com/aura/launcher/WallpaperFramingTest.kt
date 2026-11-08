package com.aura.launcher

import com.aura.launcher.ui.home.Framing
import com.aura.launcher.ui.home.MAX_WALLPAPER_ZOOM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * The wallpaper must cover the frame, always.
 *
 * The first version of the editor fitted the picture inside the framing area and
 * saved a bitmap the shape of that area rather than of the screen. Both halves
 * of that produced the same complaint from different directions: black bars
 * around the preview, and a wallpaper that arrived on the home screen visibly
 * more zoomed-in than what had been framed — the home surface cropping to cover
 * what the editor had left uncovered.
 *
 * These pin the two invariants that make that impossible: the drawn image is
 * never smaller than the frame, and the framing survives being rescaled to the
 * screen's real pixels.
 */
@RunWith(RobolectricTestRunner::class)
class WallpaperFramingTest {

    private fun framing(
        srcW: Int,
        srcH: Int,
        rotation: Int = 0,
        zoom: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) = Framing(
        sourceWidth = srcW,
        sourceHeight = srcH,
        frameWidth = FRAME_W,
        frameHeight = FRAME_H,
        rotationDegrees = rotation,
        zoom = zoom,
        offsetX = offsetX,
        offsetY = offsetY,
    ).clamped()

    /** Where the four corners of the picture land inside the frame. */
    private fun drawnBounds(f: Framing): FloatArray {
        val corners = floatArrayOf(
            0f, 0f,
            f.sourceWidth.toFloat(), 0f,
            f.sourceWidth.toFloat(), f.sourceHeight.toFloat(),
            0f, f.sourceHeight.toFloat(),
        )
        f.matrix().mapPoints(corners)
        val xs = listOf(corners[0], corners[2], corners[4], corners[6])
        val ys = listOf(corners[1], corners[3], corners[5], corners[7])
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun assertCovers(f: Framing) {
        val (left, top, right, bottom) = drawnBounds(f)
        assertTrue("left edge leaves a gap: $left", left <= TOLERANCE)
        assertTrue("top edge leaves a gap: $top", top <= TOLERANCE)
        assertTrue("right edge leaves a gap: $right", right >= f.frameWidth - TOLERANCE)
        assertTrue("bottom edge leaves a gap: $bottom", bottom >= f.frameHeight - TOLERANCE)
    }

    @Test
    fun `a wide picture still fills a tall frame`() {
        assertCovers(framing(4000, 1800))
    }

    @Test
    fun `a tall picture still fills a tall frame`() {
        assertCovers(framing(1200, 4000))
    }

    @Test
    fun `a square picture still fills a tall frame`() {
        assertCovers(framing(2000, 2000))
    }

    @Test
    fun `every quarter turn still fills the frame`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            assertCovers(framing(3000, 1500, rotation = rotation))
            assertCovers(framing(1500, 3000, rotation = rotation))
        }
    }

    @Test
    fun `panning to the end of its travel never opens a gap`() {
        // Deliberately shoved far past what the picture can afford.
        for (dx in listOf(-9000f, 0f, 9000f)) {
            for (dy in listOf(-9000f, 0f, 9000f)) {
                assertCovers(framing(3000, 2200, zoom = 1.6f, offsetX = dx, offsetY = dy))
            }
        }
    }

    @Test
    fun `zoom cannot go below the scale that covers the frame`() {
        val pinchedOut = framing(3000, 2000, zoom = 0.2f)
        assertEquals(1f, pinchedOut.zoom, 0.0001f)
        assertCovers(pinchedOut)
    }

    @Test
    fun `zoom stops at the ceiling`() {
        assertEquals(MAX_WALLPAPER_ZOOM, framing(3000, 2000, zoom = 99f).zoom, 0.0001f)
    }

    @Test
    fun `a pinch keeps the point under the fingers under the fingers`() {
        val start = framing(3000, 2200, zoom = 1.4f)
        val centroidX = FRAME_W * 0.3f
        val centroidY = FRAME_H * 0.7f

        // Where that screen point sits in the picture before the pinch.
        val before = floatArrayOf(centroidX, centroidY)
        val inverse = android.graphics.Matrix()
        assertTrue(start.matrix().invert(inverse))
        inverse.mapPoints(before)

        val zoomed = start.transformed(centroidX, centroidY, zoomBy = 1.5f, panX = 0f, panY = 0f)

        // And where it sits on screen after.
        val after = floatArrayOf(before[0], before[1])
        zoomed.matrix().mapPoints(after)

        assertTrue(
            "anchor drifted by ${abs(after[0] - centroidX)}, ${abs(after[1] - centroidY)}",
            abs(after[0] - centroidX) < 0.5f && abs(after[1] - centroidY) < 0.5f,
        )
    }

    @Test
    fun `scaling to the screen keeps the same crop`() {
        val onScreen = framing(4000, 2400, zoom = 2f, offsetX = 120f, offsetY = -80f)
        // The output is the same shape as the frame, four times the pixels.
        val rendered = onScreen.scaledTo((FRAME_W * 4).toInt(), (FRAME_H * 4).toInt())

        assertCovers(rendered)

        // The same fraction of the picture is visible in both: compare where the
        // frame's centre lands in source coordinates.
        fun sourcePointAtFrameCentre(f: Framing): FloatArray {
            val inverse = android.graphics.Matrix()
            f.matrix().invert(inverse)
            val point = floatArrayOf(f.frameWidth / 2f, f.frameHeight / 2f)
            inverse.mapPoints(point)
            return point
        }

        val small = sourcePointAtFrameCentre(onScreen)
        val large = sourcePointAtFrameCentre(rendered)
        assertEquals(small[0], large[0], 0.5f)
        assertEquals(small[1], large[1], 0.5f)
    }

    private companion object {
        /** A frame the shape of a phone. */
        const val FRAME_W = 360f
        const val FRAME_H = 780f

        /** Sub-pixel slack: the maths is in floats. */
        const val TOLERANCE = 0.01f
    }
}
