package com.aura.launcher.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.launcher.ui.theme.Aura
import kotlin.math.cos
import kotlin.math.sin

/**
 * The sky, drawn rather than typed.
 *
 * These were emoji, which is the fastest way to get a weather glyph on screen
 * and the worst-looking: an emoji is rendered by the system font, so it arrives
 * in whatever house style the vendor picked, at whatever weight, in colours
 * chosen by somebody drawing for a keyboard. Three of them in a row on a quiet
 * home screen read as stickers.
 *
 * Drawn flat, in two tones — a warm one for anything the sun is doing and a
 * grey for anything water is doing — so a row of them reads as one set. Sized
 * by the caller, since the same shapes serve a 20dp strip and a 15dp line.
 */
@Composable
fun SkyIcon(code: Int, size: Dp, modifier: Modifier = Modifier) {
    val colors = Aura.colors
    // Warm, but not the accent: the accent means "you can touch this" all over
    // the rest of the launcher, and a sun is not a control.
    val sun = Color(0xFFE0A33C)
    // Pitched off the ink rather than fixed mid-greys. These glyphs mostly sit
    // on the wallpaper, next to a temperature printed in whatever ink the user
    // chose, and a mid-grey cloud stayed mid-grey whichever way that choice
    // went — the one part of the line that visibly ignored the setting. Two
    // tones still, and still not the accent; just carried far enough in each
    // direction to be the same weight as the text beside them.
    val cloud = if (colors.isDark) Color(0xFFDBD5C9) else Color(0xFF5F594E)
    val rain = if (colors.isDark) Color(0xFFA8C2DC) else Color(0xFF4D6883)

    Canvas(modifier.size(size)) {
        when (code) {
            0 -> sun(sun)
            1, 2 -> partly(sun, cloud)
            3 -> cloudOnly(cloud)
            in 45..48 -> fog(cloud)
            in 51..57, in 61..65, in 80..82 -> rain(cloud, rain)
            in 66..67, in 71..77, in 85..86 -> snow(cloud, rain)
            in 95..99 -> storm(cloud, sun)
            else -> cloudOnly(cloud)
        }
    }
}

// ------------------------------------------------------------------ shapes --

/** A disc with eight rays. The rays are what stop it reading as a full moon. */
private fun DrawScope.sun(tint: Color, centre: Offset = center, scale: Float = 1f) {
    val r = size.minDimension * 0.22f * scale
    drawCircle(tint, radius = r, center = centre)
    val inner = r * 1.55f
    val outer = r * 2.05f
    repeat(8) { i ->
        val a = (Math.PI / 4.0 * i).toFloat()
        drawLine(
            color = tint,
            start = Offset(centre.x + cos(a) * inner, centre.y + sin(a) * inner),
            end = Offset(centre.x + cos(a) * outer, centre.y + sin(a) * outer),
            strokeWidth = size.minDimension * 0.075f * scale,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * A cloud as three overlapping discs on a bar.
 *
 * Simpler than a path and it scales without hinting — at 15dp a drawn outline
 * turns to mud, where three filled circles stay legible.
 */
private fun DrawScope.cloud(tint: Color, cx: Float, cy: Float, scale: Float = 1f) {
    val unit = size.minDimension * scale
    drawCircle(tint, radius = unit * 0.155f, center = Offset(cx - unit * 0.14f, cy))
    drawCircle(tint, radius = unit * 0.20f, center = Offset(cx + unit * 0.02f, cy - unit * 0.055f))
    drawCircle(tint, radius = unit * 0.145f, center = Offset(cx + unit * 0.19f, cy))
    drawRoundRect(
        color = tint,
        topLeft = Offset(cx - unit * 0.30f, cy - unit * 0.03f),
        size = Size(unit * 0.62f, unit * 0.19f),
        cornerRadius = CornerRadius(unit * 0.095f),
    )
}

private fun DrawScope.cloudOnly(tint: Color) = cloud(tint, center.x, center.y + size.height * 0.03f)

private fun DrawScope.partly(sunTint: Color, cloudTint: Color) {
    sun(sunTint, centre = Offset(size.width * 0.63f, size.height * 0.34f), scale = 0.72f)
    cloud(cloudTint, center.x - size.width * 0.06f, center.y + size.height * 0.14f, scale = 0.9f)
}

private fun DrawScope.fog(tint: Color) {
    val w = size.width
    val h = size.height
    repeat(3) { i ->
        val y = h * (0.36f + i * 0.16f)
        val inset = if (i == 1) w * 0.12f else w * 0.2f
        drawLine(
            color = tint.copy(alpha = if (i == 1) 0.9f else 0.6f),
            start = Offset(inset, y),
            end = Offset(w - inset, y),
            strokeWidth = h * 0.075f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.rain(cloudTint: Color, dropTint: Color) {
    cloud(cloudTint, center.x, size.height * 0.4f, scale = 0.88f)
    repeat(3) { i ->
        val x = size.width * (0.32f + i * 0.18f)
        drawLine(
            color = dropTint,
            start = Offset(x, size.height * 0.68f),
            end = Offset(x - size.width * 0.05f, size.height * 0.86f),
            strokeWidth = size.minDimension * 0.075f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.snow(cloudTint: Color, flakeTint: Color) {
    cloud(cloudTint, center.x, size.height * 0.4f, scale = 0.88f)
    repeat(3) { i ->
        drawCircle(
            color = flakeTint,
            radius = size.minDimension * 0.045f,
            center = Offset(size.width * (0.32f + i * 0.18f), size.height * 0.78f),
        )
    }
}

private fun DrawScope.storm(cloudTint: Color, boltTint: Color) {
    cloud(cloudTint, center.x, size.height * 0.38f, scale = 0.88f)
    val p = Path().apply {
        moveTo(size.width * 0.54f, size.height * 0.60f)
        lineTo(size.width * 0.40f, size.height * 0.84f)
        lineTo(size.width * 0.50f, size.height * 0.84f)
        lineTo(size.width * 0.44f, size.height * 1.00f)
        lineTo(size.width * 0.64f, size.height * 0.76f)
        lineTo(size.width * 0.53f, size.height * 0.76f)
        close()
    }
    drawPath(p, boltTint)
}

/**
 * The charging mark on the status line.
 *
 * Same reasoning as the sky: this was the ⚡ emoji, which on most phones is a
 * flat yellow lozenge with a black outline and nothing like the rest of the
 * line. Drawn, it is one shape in one colour that matches the percentage it
 * sits beside.
 */
@Composable
fun ChargingBolt(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.60f, 0f)
            lineTo(w * 0.20f, h * 0.55f)
            lineTo(w * 0.46f, h * 0.55f)
            lineTo(w * 0.38f, h)
            lineTo(w * 0.80f, h * 0.42f)
            lineTo(w * 0.53f, h * 0.42f)
            close()
        }
        drawPath(p, tint)
    }
}
