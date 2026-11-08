package com.aura.launcher.ui.sketch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aura.launcher.R
import com.aura.launcher.sketch.Assist
import com.aura.launcher.sketch.Board
import com.aura.launcher.sketch.BoardStore
import com.aura.launcher.sketch.Box
import com.aura.launcher.sketch.Element
import com.aura.launcher.sketch.Lasso
import com.aura.launcher.sketch.PictureFinder
import com.aura.launcher.sketch.SketchModel
import com.aura.launcher.sketch.SketchPainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * One open board: what is on it, where it is being looked at, and the model
 * that helps when asked.
 *
 * Drawing is only drawing. The model is asked when the hand circles something
 * ([Lasso]): it gets the circled things, does its work on them, and the circle
 * goes. Its answer is dropped if what it was about has been moved or erased
 * in the meantime.
 */
class SketchSession(
    private val context: android.content.Context,
    private val scope: CoroutineScope,
    private val store: BoardStore,
    private val model: SketchModel,
    private var board: Board,
    /** The gesture stroke's colour, which the hand's ink is drawn in. */
    ink: Int,
) {
    var elements by mutableStateOf(board.elements)
        private set
    var camera by mutableStateOf(board.camera)
    var selected by mutableStateOf<String?>(null)
    /** Circles the model is working on, and what each went around. */
    val working = mutableStateMapOf<String, List<String>>()
    /** A line at the top, for a moment: why the model said nothing. */
    var note by mutableStateOf<String?>(null)
        private set

    /** When each element arrived from the model, for its entrance. */
    val arrivals = mutableStateMapOf<String, Long>()
    /** What the model took away, still fading out, and since when. */
    val departures = mutableStateMapOf<Element, Long>()

    private val finder = PictureFinder(store.pictures)
    private val bitmaps = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    val painter = SketchPainter(ink) { name ->
        bitmaps.get(name) ?: BitmapFactory.decodeFile(File(store.pictures, name).path)?.also { bitmaps.put(name, it) }
    }

    private val looks = mutableListOf<Job>()
    private var saving: Job? = null

    fun newId(): String = UUID.randomUUID().toString().take(8)

    // ------------------------------------------------------------ the hand --

    fun addStroke(ink: Element.Ink) {
        val circled = Lasso.enclosed(ink.points, elements.filter { it.id !in working.keys })
        elements = elements + ink
        changed()
        if (circled.isEmpty()) return
        if (!model.config.value.ready) {
            say(context.getString(R.string.sketch_no_model))
            return
        }
        working[ink.id] = circled
        looks += scope.launch { look(ink, circled) }
    }

    fun move(id: String, dx: Float, dy: Float) {
        elements = elements.map { if (it.id == id) it.moved(dx, dy) else it }
    }

    fun moved() = changed()

    fun delete(id: String) {
        val gone = elements.firstOrNull { it.id == id } ?: return
        elements = elements - gone
        departures[gone] = now()
        if (selected == id) selected = null
        changed()
    }

    /** The topmost element under a board point, with some slack for a finger. */
    fun hit(x: Float, y: Float, slack: Float): Element? =
        elements.lastOrNull { it.bounds.inflate(slack).contains(x, y) }

    // ----------------------------------------------------------- the model --

    private suspend fun look(circle: Element.Ink, circled: List<String>) {
        val before = elements
        // The circle and what is around it, so the model sees the context.
        val b = circle.bounds
        val view = b.inflate(maxOf(b.width, b.height) * 0.35f)
        try {
            val jpeg = withContext(Dispatchers.Default) {
                val bmp = painter.render(before.filter { it.id != circle.id }, view, LOOK_SIDE, plain = true)
                ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it); bmp.recycle() }.toByteArray()
            }
            val reply = model.ask(Assist.SYSTEM, jpeg, Assist.describe(before.filter { it.id != circle.id }, circled.toSet(), view, b))
            val changes = Assist.parse(reply, circled) { newId() }
            val after = Assist.apply(elements, changes)
            if (after != null && changes.isNotEmpty()) {
                val t = now()
                changes.flatMap { it.remove }.forEach { id -> elements.firstOrNull { it.id == id }?.let { departures[it] = t } }
                changes.flatMap { it.add }.forEach { arrivals[it.id] = t }
                elements = after
                changes.flatMap { it.add }.filterIsInstance<Element.Picture>().filter { it.file.isEmpty() }.forEach { bring(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say(context.getString(R.string.sketch_model_failed, (e.message ?: e.javaClass.simpleName).take(90)))
        } finally {
            working.remove(circle.id)
            // The question has been answered, or could not be: the circle goes.
            delete(circle.id)
        }
    }

    /** Finds the picture the model asked for and puts it in its place, at its own proportions. */
    private fun bring(p: Element.Picture) = scope.launch {
        val found = finder.find(p.query)
        val current = elements.firstOrNull { it.id == p.id } as? Element.Picture ?: return@launch
        if (found == null) {
            delete(p.id)
            say(context.getString(R.string.sketch_no_picture, p.query))
            return@launch
        }
        val (file, ratio) = found
        val b = current.box
        val h = b.width / ratio
        val placed = current.copy(file = file, box = Box(b.left, b.centerY - h / 2, b.right, b.centerY + h / 2))
        elements = elements.map { if (it.id == p.id) placed else it }
        arrivals[p.id] = now()
        changed()
    }

    fun say(text: String) {
        note = text
        scope.launch {
            delay(3_500)
            if (note == text) note = null
        }
    }

    // ------------------------------------------------------------- keeping --

    private fun changed() {
        saving?.cancel()
        saving = scope.launch {
            delay(1_500)
            save(thumbnail = false)
        }
    }

    suspend fun save(thumbnail: Boolean) {
        // A circle still waiting on the model is a question, not part of the drawing.
        val kept = elements.filter { it.id !in working.keys }
        board = board.copy(elements = kept, camera = camera, updated = System.currentTimeMillis())
        val thumb = if (thumbnail && kept.isNotEmpty()) withContext(Dispatchers.Default) {
            val all = kept.map { it.bounds }.reduce(Box::union).inflate(48f)
            // Square, so the grid in settings lines up.
            val side = maxOf(all.width, all.height)
            val square = Box(all.centerX - side / 2, all.centerY - side / 2, all.centerX + side / 2, all.centerY + side / 2)
            painter.render(kept, square, 360)
        } else null
        store.save(board, thumb)
    }

    /** Leaving: stop asking, keep everything. */
    fun close() {
        looks.forEach { it.cancel() }
        saving?.cancel()
    }

    fun now(): Long = System.nanoTime() / 1_000_000

    companion object {
        /** Long side of the picture the model sees. */
        const val LOOK_SIDE = 1024
    }
}
