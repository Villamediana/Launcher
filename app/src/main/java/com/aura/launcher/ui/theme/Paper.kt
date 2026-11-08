package com.aura.launcher.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.min
import kotlin.random.Random

private const val GRAIN_TILE = 192

/**
 * A single deterministic noise tile shared by every paper surface. Generating it
 * once and repeating it via a shader keeps the texture free at draw time — the
 * alternative (per-frame noise) would burn CPU on the home screen forever.
 */
private val grainTile: ImageBitmap by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val rnd = Random(0x4155_5241) // "AURA"
    val pixels = IntArray(GRAIN_TILE * GRAIN_TILE)
    for (i in pixels.indices) {
        // Two stacked samples give a softer, more fibrous grain than flat white
        // noise, which otherwise reads as digital static rather than paper.
        val a = rnd.nextInt(256)
        val b = rnd.nextInt(256)
        val v = (a + b) / 2
        pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    Bitmap.createBitmap(pixels, GRAIN_TILE, GRAIN_TILE, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Warm paper background: a base tint, a very soft off-centre glow so large
 * surfaces are not perfectly flat, and a repeating grain on top.
 */
fun Modifier.paperBackground(
    colors: AuraColors,
    grainAlpha: Float = if (colors.isDark) 0.030f else 0.045f,
    glow: Boolean = true,
): Modifier = this
    .background(colors.paper)
    .drawWithCache {
        val grain = ShaderBrush(
            ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated)
        )
        val radius = min(size.width, size.height) * 1.15f
        val glowBrush = Brush.radialGradient(
            colors = listOf(
                if (colors.isDark) Color(0x14E0A472) else Color(0x1AFFFFFF),
                Color.Transparent,
            ),
            center = Offset(size.width * 0.22f, size.height * 0.14f),
            radius = radius,
        )
        val vignette = Brush.radialGradient(
            colors = listOf(Color.Transparent, if (colors.isDark) Color(0x2E000000) else Color(0x0F3A2E1E)),
            center = Offset(size.width * 0.5f, size.height * 0.45f),
            radius = radius,
        )
        onDrawBehind {
            if (glow) {
                drawRect(glowBrush)
                drawRect(vignette)
            }
            drawRect(grain, alpha = grainAlpha)
        }
    }

/** Grain only — for cards sitting on top of an already-textured background. */
fun Modifier.paperGrain(alpha: Float = 0.035f): Modifier = this.drawWithCache {
    val grain = ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated))
    onDrawWithContent {
        drawContent()
        drawRect(grain, alpha = alpha)
    }
}
