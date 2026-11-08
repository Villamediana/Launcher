package com.aura.launcher.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

private const val TAG = "Wallpaper"

/**
 * Where the edited wallpaper is stored.
 *
 * The gallery picker gives the editor a content URI whose validity we do not
 * control. Persisting the editor's final bitmap inside filesDir makes the
 * result ours and keeps it after the source grant disappears.
 */
object WallpaperStore {

    /**
     * Writes an already-composed bitmap (the output of the wallpaper
     * editor's crop/pan/rotate pass) as the wallpaper. Same folder, same
     * sweep, JPEG quality 92 — high enough that a photograph does not
     * band, low enough that we do not carry a 20MB file per switch.
     */
    suspend fun saveBitmap(context: Context, bitmap: android.graphics.Bitmap): String? =
        withContext(Dispatchers.IO) {
        val outDir = File(context.filesDir, "wallpaper").apply { mkdirs() }
        val target = File(outDir, "home-${UUID.randomUUID().toString().take(8)}.jpg")
        runCatching {
            val written = target.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
            }
            check(written && target.length() > 0L) { "Could not encode the wallpaper" }
            outDir.listFiles()?.forEach { f -> if (f != target) f.delete() }
            target.absolutePath
        }.onFailure {
            target.delete()
            Log.w(TAG, "Save bitmap failed", it)
        }.getOrNull()
    }

    /**
     * Sets the same bitmap as the system lock-screen wallpaper.
     *
     * Only the lock screen: the home surface is Aura's own, drawn by Compose,
     * and writing the system home wallpaper as well would put a second copy of
     * the photograph behind a launcher that is already painting it. Failures
     * are swallowed — a device whose OEM refuses FLAG_LOCK still has to end up
     * with the wallpaper it was asked for on the screen Aura controls.
     */
    suspend fun applyToLockScreen(context: Context, bitmap: android.graphics.Bitmap) {
        withContext(Dispatchers.IO) {
            runCatching {
                android.app.WallpaperManager.getInstance(context)
                    .setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_LOCK)
            }.onFailure { Log.w(TAG, "Lock screen wallpaper refused", it) }
        }
    }

    fun clear(context: Context) {
        runCatching {
            File(context.filesDir, "wallpaper").listFiles()?.forEach { it.delete() }
        }
        WallpaperCache.forget()
    }

    /**
     * The wallpaper file, without going through settings.
     *
     * Needed before the settings have been read: the window background is set
     * in `onCreate`, and DataStore answers a few frames later. The folder holds
     * at most one file — every save sweeps the others — so its presence is the
     * whole of the question.
     */
    fun currentFile(context: Context): File? = runCatching {
        File(context.filesDir, "wallpaper").listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.maxByOrNull { it.lastModified() }
    }.getOrNull()

    /**
     * The dim, mirrored where it can be read without waiting for DataStore.
     *
     * Settings remain the one place the value is decided; this is a cache for
     * the single frame that happens before they are readable. The window
     * background is painted in `onCreate`, and a wallpaper that arrives
     * undimmed and darkens two frames later is the same flash the cropped
     * window background was introduced to remove.
     */
    fun dim(context: Context): Float = runCatching {
        context.getSharedPreferences(MIRROR, Context.MODE_PRIVATE)
            .getFloat(MIRROR_DIM, 0f)
            .coerceIn(0f, 1f)
    }.getOrDefault(0f)

    fun rememberDim(context: Context, value: Float) {
        runCatching {
            context.getSharedPreferences(MIRROR, Context.MODE_PRIVATE)
                .edit()
                .putFloat(MIRROR_DIM, value.coerceIn(0f, 1f))
                .apply()
        }
    }

    private const val MIRROR = "wallpaper_mirror"
    private const val MIRROR_DIM = "dim"
}

/**
 * The decoded wallpaper, kept for as long as the process lives.
 *
 * Two callers want the same picture at two different moments: the window
 * background, painted before a single composable has run, and the Compose layer
 * a few frames later. Decoding twice would be waste; decoding late is what the
 * user actually saw — a flash of the phone's own wallpaper, uncropped and
 * zoomed, before Aura's version replaced it.
 */
object WallpaperCache {

    @Volatile private var cachedPath: String? = null
    @Volatile private var cached: android.graphics.Bitmap? = null

    /** The decoded picture for [path], decoding it if this is the first ask. */
    @Synchronized
    fun bitmap(path: String): android.graphics.Bitmap? {
        if (path.isBlank()) return null
        cached?.let { if (cachedPath == path && !it.isRecycled) return it }
        val decoded = loadDownsampled(path, targetPx = 2048) ?: return null
        cachedPath = path
        cached = decoded
        return decoded
    }

    /** Already in memory, or null. Never decodes — safe on the main thread. */
    fun peek(path: String): android.graphics.Bitmap? =
        cached?.takeIf { cachedPath == path && !it.isRecycled }

    @Synchronized
    fun forget() {
        cached = null
        cachedPath = null
    }
}

/**
 * A cheap subsampled decode of the path, downscaled to the on-screen size
 * so the launcher does not carry a 30-megapixel photo in memory forever.
 */
@Composable
fun rememberWallpaperBitmap(path: String): ImageBitmap? {
    // Seeded from the cache, so a wallpaper this process has already decoded —
    // for the window background, or before the Activity was last recreated — is
    // there on the first composition rather than one frame later. That frame is
    // what the flash on unlock was made of.
    var bitmap by remember(path) {
        mutableStateOf(WallpaperCache.peek(path)?.asImageBitmap())
    }

    LaunchedEffect(path) {
        if (bitmap != null) return@LaunchedEffect
        // BitmapFactory performs file I/O and allocates the pixel buffer. A
        // wallpaper first encountered on the Appearance page used to do both
        // on the main thread, making the tap look like a freeze or process
        // death on memory-constrained phones.
        val decoded = if (path.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching { WallpaperCache.bitmap(path) }
                .onFailure { Log.w(TAG, "Wallpaper decode failed", it) }
                .getOrNull()
        }
        bitmap = decoded?.asImageBitmap()
    }
    return bitmap
}

private fun loadDownsampled(path: String, targetPx: Int): android.graphics.Bitmap? {
    if (!File(path).exists()) return null

    // First pass reads dimensions only. Cheap.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val longEdge = max(bounds.outWidth, bounds.outHeight)
    val sample = wallpaperInSampleSize(bounds.outWidth, bounds.outHeight, targetPx)

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(path, opts)
}

/**
 * Power-of-two sample that never leaves the decoded long edge above [targetPx].
 * Flooring the ratio allowed an 8000px photograph through at 4000px, roughly
 * 48 MB for a 4:3 ARGB bitmap, despite the documented 2048px ceiling.
 */
internal fun wallpaperInSampleSize(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0 || targetPx <= 0) return 1
    val longEdge = max(width, height).toLong()
    var sample = 1
    while ((longEdge + sample - 1L) / sample > targetPx && sample <= Int.MAX_VALUE / 2) {
        sample *= 2
    }
    return sample
}

/**
 * Renders the wallpaper as the bottom layer of a home surface, sized to fill
 * and cropped to cover — the same behaviour Android's own wallpaper picker
 * produces. Falls back to nothing when no path is set; the paper background
 * downstream still shows.
 */
fun Modifier.wallpaperBackground(image: ImageBitmap?, dim: Float = 0f): Modifier =
    if (image == null) this else this.drawWithCache {
        val veil = dim.coerceIn(0f, 1f)
        onDrawBehind {
            val cw = size.width
            val ch = size.height
            val iw = image.width.toFloat()
            val ih = image.height.toFloat()
            val scale = max(cw / iw, ch / ih)
            // Source rect big enough to cover, cropped to the middle so tall
            // pictures on a wide screen (and vice versa) fill without stretch.
            val srcW = min(iw, cw / scale).coerceAtLeast(1f).toInt()
            val srcH = min(ih, ch / scale).coerceAtLeast(1f).toInt()
            val srcX = ((iw - srcW) / 2f).toInt().coerceAtLeast(0)
            val srcY = ((ih - srcH) / 2f).toInt().coerceAtLeast(0)
            drawImage(
                image = image,
                srcOffset = IntOffset(srcX, srcY),
                srcSize = IntSize(srcW, srcH),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(cw.toInt(), ch.toInt()),
                filterQuality = FilterQuality.High,
            )
            // The veil, on top of the picture and under everything else, so the
            // clock and the app names gain contrast without any of them being
            // recoloured. Black rather than the theme's ink: what is wanted
            // here is less light, not a tint.
            if (veil > 0f) drawRect(color = Color.Black, alpha = veil)
        }
    }
