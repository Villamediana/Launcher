package com.aura.launcher.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import com.aura.launcher.R
import com.aura.launcher.ui.theme.Aura
import com.aura.launcher.ui.theme.wallpaperInSampleSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full-screen preview of a picked wallpaper before it lands on the home.
 *
 * Two rules make this what it is, and both were broken in the first cut:
 *
 * 1. **The frame is the phone.** Not "whatever space is left below the title" —
 *    the framed area carries the screen's own aspect ratio, so what is inside it
 *    is exactly what will be on the home screen. It used to be an arbitrary
 *    rectangle, and the picture inside it was fitted rather than filled, which
 *    is where the wide black borders came from.
 *
 * 2. **The picture always covers the frame.** The starting scale is the one that
 *    exactly fills it, zoom cannot go below that, and panning stops at the edge
 *    of the image. There is no state in which a gap can open, so no state in
 *    which the home screen has to zoom in afterwards to hide one — which is what
 *    made an applied wallpaper come out blown up compared to the preview.
 *
 * The transform lives in [Framing], which also builds the matrix used both for
 * drawing and for the final render. One description, two consumers: what the
 * user framed is what gets written to disk, at the screen's own resolution.
 */
@Composable
fun WallpaperEditor(
    source: Uri,
    onCancel: () -> Unit,
    onApply: (Bitmap) -> Unit,
) {
    val colors = Aura.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The size the wallpaper will actually be drawn at. Both the shape of the
    // framing area and the resolution of the saved file come from here.
    val metrics = context.resources.displayMetrics
    val screenW = metrics.widthPixels.coerceAtLeast(1)
    val screenH = metrics.heightPixels.coerceAtLeast(1)
    val screenAspect = screenW.toFloat() / screenH.toFloat()

    // Loaded once, downsampled, and turned upright: a photograph off a phone
    // camera carries its rotation in EXIF rather than in its pixels, and
    // BitmapFactory hands back the sideways version.
    var picture by remember(source) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source) {
        picture = withContext(Dispatchers.IO) {
            runCatching { decodeUpright(context, source, targetPx = 2048) }.getOrNull()
        }
    }

    var frameW by remember { mutableStateOf(0) }
    var frameH by remember { mutableStateOf(0) }
    var rotation by remember(source) { mutableStateOf(0) }
    var zoom by remember(source) { mutableStateOf(1f) }
    var offsetX by remember(source) { mutableStateOf(0f) }
    var offsetY by remember(source) { mutableStateOf(0f) }
    var applying by remember { mutableStateOf(false) }

    val bitmap = picture
    val framing = if (bitmap != null && frameW > 0 && frameH > 0) {
        Framing(
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
            frameWidth = frameW.toFloat(),
            frameHeight = frameH.toFloat(),
            rotationDegrees = rotation,
            zoom = zoom,
            offsetX = offsetX,
            offsetY = offsetY,
        ).clamped()
    } else null

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.paperDeep)
                .systemBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.wallpaper_editor_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            Text(
                text = stringResource(R.string.wallpaper_editor_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(16.dp))

            // The phone, at the size that fits. Nothing is drawn outside it and
            // nothing inside it is anything but the picture.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(screenAspect, matchHeightConstraintsFirst = true)
                        .clip(RoundedCornerShape(22.dp))
                        .background(colors.surface)
                        .onSizeChanged { frameW = it.width; frameH = it.height }
                        .pointerInput(source, bitmap) {
                            val picked = bitmap ?: return@pointerInput
                            detectTransformGestures(panZoomLock = true) { centroid, pan, gestureZoom, _ ->
                                val current = Framing(
                                    sourceWidth = picked.width,
                                    sourceHeight = picked.height,
                                    frameWidth = size.width.toFloat(),
                                    frameHeight = size.height.toFloat(),
                                    rotationDegrees = rotation,
                                    zoom = zoom,
                                    offsetX = offsetX,
                                    offsetY = offsetY,
                                )
                                val next = current.transformed(
                                    centroidX = centroid.x,
                                    centroidY = centroid.y,
                                    zoomBy = gestureZoom,
                                    panX = pan.x,
                                    panY = pan.y,
                                )
                                zoom = next.zoom
                                offsetX = next.offsetX
                                offsetY = next.offsetY
                            }
                        },
                ) {
                    if (bitmap != null && framing != null) {
                        val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
                        Canvas(Modifier.fillMaxSize()) {
                            drawIntoCanvas { canvas ->
                                canvas.nativeCanvas.drawBitmap(bitmap, framing.matrix(), paint)
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorIconButton(
                    icon = Icons.AutoMirrored.Rounded.RotateRight,
                    label = stringResource(R.string.wallpaper_editor_rotate),
                    onClick = {
                        // A quarter turn changes which way the picture fills the
                        // frame entirely, so the pan it had is meaningless.
                        rotation = (rotation + 90) % 360
                        offsetX = 0f
                        offsetY = 0f
                    },
                )
                Spacer(Modifier.width(10.dp))
                EditorIconButton(
                    icon = Icons.Rounded.Refresh,
                    label = stringResource(R.string.wallpaper_editor_reset),
                    onClick = { rotation = 0; zoom = 1f; offsetX = 0f; offsetY = 0f },
                )
                Spacer(Modifier.weight(1f))
                EditorIconButton(
                    icon = Icons.Rounded.Close,
                    label = stringResource(R.string.common_cancel),
                    onClick = onCancel,
                )
                Spacer(Modifier.width(8.dp))
                EditorIconButton(
                    icon = Icons.Rounded.Check,
                    label = stringResource(R.string.wallpaper_editor_apply),
                    filled = true,
                    enabled = framing != null && bitmap != null && !applying,
                    onClick = {
                        val ready = framing ?: return@EditorIconButton
                        val src = bitmap ?: return@EditorIconButton
                        applying = true
                        // A full-screen ARGB bitmap is a few tens of
                        // milliseconds of work; off the main thread it is
                        // nobody's dropped frame.
                        scope.launch {
                            val out = withContext(Dispatchers.Default) {
                                render(src, ready.scaledTo(screenW, screenH))
                            }
                            onApply(out)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun EditorIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Aura.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                when {
                    filled && enabled -> colors.accent
                    filled -> colors.inkFaint
                    else -> colors.surface
                }
            )
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (filled) colors.surfaceRaised else colors.ink,
            modifier = Modifier.size(20.dp),
        )
    }
}

// --------------------------------------------------------------- framing ----

/** Below this the picture would no longer cover the frame. */
internal const val MIN_WALLPAPER_ZOOM = 1f

/** Far enough in to crop a face out of a group photo, not so far it pixelates. */
internal const val MAX_WALLPAPER_ZOOM = 6f

/**
 * Where the picture sits inside the frame.
 *
 * [zoom] is a multiple of the scale at which the image exactly covers the frame,
 * so 1 is "filled, nothing cropped that does not have to be" and it can never go
 * lower. [offsetX] and [offsetY] are in frame pixels from the centre and are
 * bounded by how much picture there is to spare — which is what guarantees no
 * gap can ever open at an edge.
 *
 * Deliberately a plain value type with no Compose or Android UI in it: the same
 * description drives the on-screen draw and the final render, and it can be
 * checked in a unit test rather than by eye on a phone.
 */
internal data class Framing(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val frameWidth: Float,
    val frameHeight: Float,
    /** 0, 90, 180 or 270 — the quarter turns the user asked for. */
    val rotationDegrees: Int = 0,
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    private val quarterTurned: Boolean get() = rotationDegrees % 180 != 0

    /** The picture's width once its rotation is taken into account. */
    val uprightWidth: Float get() = (if (quarterTurned) sourceHeight else sourceWidth).toFloat()

    /** The picture's height once its rotation is taken into account. */
    val uprightHeight: Float get() = (if (quarterTurned) sourceWidth else sourceHeight).toFloat()

    /** The scale at which the picture exactly covers the frame. */
    val coverScale: Float
        get() = max(frameWidth / uprightWidth, frameHeight / uprightHeight)

    /** The scale actually drawn at. */
    val drawScale: Float get() = coverScale * zoom

    /** How far the picture may be pushed sideways before an edge would show. */
    val maxOffsetX: Float
        get() = ((uprightWidth * drawScale) - frameWidth).coerceAtLeast(0f) / 2f

    /** How far the picture may be pushed up or down before an edge would show. */
    val maxOffsetY: Float
        get() = ((uprightHeight * drawScale) - frameHeight).coerceAtLeast(0f) / 2f

    /** The same framing with the zoom and the pan pulled back into range. */
    fun clamped(): Framing {
        val bounded = copy(zoom = zoom.coerceIn(MIN_WALLPAPER_ZOOM, MAX_WALLPAPER_ZOOM))
        return bounded.copy(
            offsetX = bounded.offsetX.coerceIn(-bounded.maxOffsetX, bounded.maxOffsetX),
            offsetY = bounded.offsetY.coerceIn(-bounded.maxOffsetY, bounded.maxOffsetY),
        )
    }

    /**
     * Applies one gesture frame: a pinch of [zoomBy] centred on
     * ([centroidX], [centroidY]) plus a drag of ([panX], [panY]).
     *
     * The zoom is anchored at the centroid — the point of the picture under the
     * fingers stays under the fingers — which is the difference between a pinch
     * that feels attached to the image and one that appears to shove it towards
     * a corner.
     */
    fun transformed(
        centroidX: Float,
        centroidY: Float,
        zoomBy: Float,
        panX: Float,
        panY: Float,
    ): Framing {
        val nextZoom = (zoom * zoomBy).coerceIn(MIN_WALLPAPER_ZOOM, MAX_WALLPAPER_ZOOM)
        val growth = nextZoom / zoom
        val fromCentreX = centroidX - frameWidth / 2f
        val fromCentreY = centroidY - frameHeight / 2f
        return copy(
            zoom = nextZoom,
            offsetX = fromCentreX - growth * (fromCentreX - offsetX) + panX,
            offsetY = fromCentreY - growth * (fromCentreY - offsetY) + panY,
        ).clamped()
    }

    /**
     * The same framing expressed against an output of [outWidth] × [outHeight].
     *
     * Used to go from the on-screen frame to the real pixels of the phone. The
     * two share an aspect ratio, so everything scales by one factor and the
     * result is the picture the user framed, at full resolution.
     */
    fun scaledTo(outWidth: Int, outHeight: Int): Framing {
        val factor = outWidth / frameWidth
        return copy(
            frameWidth = outWidth.toFloat(),
            frameHeight = outHeight.toFloat(),
            offsetX = offsetX * factor,
            offsetY = offsetY * factor,
        ).clamped()
    }

    /**
     * Maps the source bitmap onto the frame: centre it, turn it, scale it to
     * cover, then move it where the user put it.
     */
    fun matrix(): Matrix = Matrix().apply {
        postTranslate(-sourceWidth / 2f, -sourceHeight / 2f)
        postRotate(rotationDegrees.toFloat())
        postScale(drawScale, drawScale)
        postTranslate(frameWidth / 2f + offsetX, frameHeight / 2f + offsetY)
    }
}

/** Draws [source] through [framing] onto a bitmap the size of the framing. */
private fun render(source: Bitmap, framing: Framing): Bitmap {
    val out = Bitmap.createBitmap(
        framing.frameWidth.roundToInt().coerceAtLeast(1),
        framing.frameHeight.roundToInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    android.graphics.Canvas(out).drawBitmap(
        source,
        framing.matrix(),
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
    )
    return out
}

/**
 * Decodes [uri] at roughly [targetPx] on its long edge and rotates it the way
 * its EXIF says it was taken. Without that step a portrait photo off the camera
 * arrives on its side and the user has to correct by hand what was never
 * crooked.
 */
private fun decodeUpright(context: Context, uri: Uri, targetPx: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val sample = wallpaperInSampleSize(bounds.outWidth, bounds.outHeight, targetPx)

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: return null

    val orientation = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    val fix = exifMatrix(orientation) ?: return decoded
    return runCatching {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, fix, true).also { upright ->
            if (upright !== decoded) decoded.recycle()
        }
    }.getOrDefault(decoded)
}

/** The transform that puts an EXIF-tagged photo the right way up, if any. */
private fun exifMatrix(orientation: Int): Matrix? = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
    ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
    ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }
    ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { postRotate(90f); postScale(-1f, 1f) }
    ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { postRotate(270f); postScale(-1f, 1f) }
    else -> null
}
