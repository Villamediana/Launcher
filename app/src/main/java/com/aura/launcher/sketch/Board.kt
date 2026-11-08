package com.aura.launcher.sketch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min

/** A rectangle in board coordinates. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(other: Box) = Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
    fun inflate(by: Float) = Box(left - by, top - by, right + by, bottom + by)
    fun intersects(other: Box) = left <= other.right && other.left <= right && top <= other.bottom && other.top <= bottom
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    companion object {
        fun of(points: FloatArray): Box {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (i in points.indices step 2) {
                l = min(l, points[i]); r = max(r, points[i])
                t = min(t, points[i + 1]); b = max(b, points[i + 1])
            }
            return Box(l, t, r, b)
        }
    }
}

enum class ShapeKind { RECT, ELLIPSE, TRIANGLE, DIAMOND }

/**
 * One thing on the board, in board coordinates: an infinite plane, where the
 * camera decides what part is on screen and at what size.
 *
 * Whatever the hand drew is [Ink]. Everything else is what the model made of
 * it — and the model writes and reads these same kinds, through [ElementCodec],
 * so there is one vocabulary for the board, the file and the conversation.
 */
sealed interface Element {
    val id: String
    /** "#RRGGBB", or null for the board's own ink. */
    val color: String?
    val bounds: Box
    fun moved(dx: Float, dy: Float): Element

    /** Freehand: x, y pairs. */
    data class Ink(override val id: String, val points: FloatArray, val width: Float, override val color: String? = null) : Element {
        override val bounds: Box get() = Box.of(points).inflate(width / 2f)
        override fun moved(dx: Float, dy: Float) = copy(points = FloatArray(points.size) { points[it] + if (it % 2 == 0) dx else dy })
        override fun equals(other: Any?) = other is Ink && id == other.id && width == other.width && color == other.color && points.contentEquals(other.points)
        override fun hashCode() = 31 * id.hashCode() + points.contentHashCode()
    }

    data class Shape(
        override val id: String,
        val kind: ShapeKind,
        val box: Box,
        override val color: String? = null,
        /** "#RRGGBB" to fill it, null for an outline. */
        val fill: String? = null,
    ) : Element {
        override val bounds: Box get() = box
        override fun moved(dx: Float, dy: Float) = copy(box = Box(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy))
    }

    /** Straight segments through x, y pairs; [closed] makes it a polygon. */
    data class Line(
        override val id: String,
        val points: FloatArray,
        val arrow: Boolean = false,
        val closed: Boolean = false,
        override val color: String? = null,
        val fill: String? = null,
    ) : Element {
        override val bounds: Box get() = Box.of(points)
        override fun moved(dx: Float, dy: Float) = copy(points = FloatArray(points.size) { points[it] + if (it % 2 == 0) dx else dy })
        override fun equals(other: Any?) = other is Line && id == other.id && arrow == other.arrow && closed == other.closed &&
            color == other.color && fill == other.fill && points.contentEquals(other.points)
        override fun hashCode() = 31 * id.hashCode() + points.contentHashCode()
    }

    /** Typed text, its top-left corner at [x], [y]; [size] is the letter height. */
    data class Text(
        override val id: String,
        val text: String,
        val x: Float,
        val y: Float,
        val size: Float,
        override val color: String? = null,
    ) : Element {
        override val bounds: Box get() {
            val lines = text.lines()
            val widest = lines.maxOf { it.length }.coerceAtLeast(1)
            return Box(x, y, x + widest * size * 0.56f, y + lines.size * size * 1.25f)
        }
        override fun moved(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
    }

    /**
     * A picture from the web. [file] is empty until it has been found: the
     * model asks with [query], and the board brings it.
     */
    data class Picture(
        override val id: String,
        val box: Box,
        val file: String = "",
        val query: String = "",
    ) : Element {
        override val color: String? get() = null
        override val bounds: Box get() = box
        override fun moved(dx: Float, dy: Float) = copy(box = Box(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy))
    }
}

/** Where the board was being looked at: board point under the screen's top-left, and the zoom. */
data class Camera(val x: Float = 0f, val y: Float = 0f, val zoom: Float = 1f)

data class Board(
    val id: String,
    val created: Long,
    val updated: Long,
    val elements: List<Element> = emptyList(),
    val camera: Camera = Camera(),
) {
    val isEmpty: Boolean get() = elements.isEmpty()
}

/**
 * Elements to and from JSON, leniently: the same shapes are written by this
 * app and by a language model, and a model's number may arrive as a string or
 * its point list as pairs. Anything that cannot be read is dropped, not fatal.
 */
object ElementCodec {

    fun write(e: Element, round: Boolean = false): JsonObject = buildJsonObject {
        put("id", e.id)
        fun num(v: Float) = if (round) JsonPrimitive(Math.round(v)) else JsonPrimitive(v)
        fun pts(p: FloatArray) = JsonArray(p.map { num(it) })
        fun box(b: Box) { put("x", num(b.left)); put("y", num(b.top)); put("w", num(b.width)); put("h", num(b.height)) }
        when (e) {
            is Element.Ink -> { put("type", "ink"); put("points", pts(e.points)); put("width", num(e.width)) }
            is Element.Shape -> { put("type", e.kind.name.lowercase()); box(e.box); e.fill?.let { put("fill", it) } }
            is Element.Line -> {
                put("type", "line"); put("points", pts(e.points))
                if (e.arrow) put("arrow", true)
                if (e.closed) put("closed", true)
                e.fill?.let { put("fill", it) }
            }
            is Element.Text -> { put("type", "text"); put("text", e.text); put("x", num(e.x)); put("y", num(e.y)); put("size", num(e.size)) }
            is Element.Picture -> { put("type", "picture"); box(e.box); if (e.file.isNotEmpty()) put("file", e.file); if (e.query.isNotEmpty()) put("query", e.query) }
        }
        e.color?.let { put("color", it) }
    }

    fun read(o: JsonObject, fallbackId: () -> String): Element? = runCatching {
        val id = o.str("id").ifBlank { fallbackId() }
        val color = o.color("color")
        fun box() = Box(o.num("x"), o.num("y"), o.num("x") + o.num("w"), o.num("y") + o.num("h"))
        when (val type = o.str("type").lowercase()) {
            "ink" -> Element.Ink(id, o.points(), o.numOr("width", 4f), color)
            "line", "arrow", "polygon" -> Element.Line(
                id, o.points(),
                arrow = type == "arrow" || o.bool("arrow"),
                closed = type == "polygon" || o.bool("closed"),
                color = color, fill = o.color("fill"),
            )
            "text" -> Element.Text(id, o.str("text"), o.num("x"), o.num("y"), o.numOr("size", 28f), color)
            "picture", "image" -> Element.Picture(id, box(), o.str("file"), o.str("query"))
            else -> {
                val kind = when (type) {
                    "rect", "rectangle", "square" -> ShapeKind.RECT
                    "ellipse", "circle", "oval" -> ShapeKind.ELLIPSE
                    "triangle" -> ShapeKind.TRIANGLE
                    "diamond", "rhombus" -> ShapeKind.DIAMOND
                    else -> return@runCatching null
                }
                Element.Shape(id, kind, box(), color, o.color("fill"))
            }
        }?.takeIf { valid(it) }
    }.getOrNull()

    private fun valid(e: Element): Boolean = when (e) {
        is Element.Ink -> e.points.size >= 2
        is Element.Line -> e.points.size >= 4
        is Element.Text -> e.text.isNotBlank() && e.size > 0f
        is Element.Shape -> e.box.width > 0f && e.box.height > 0f
        is Element.Picture -> e.box.width > 0f && e.box.height > 0f && (e.file.isNotEmpty() || e.query.isNotEmpty())
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.bool(k: String) = str(k).equals("true", ignoreCase = true)
    private fun JsonObject.num(k: String): Float = (this[k] as? JsonPrimitive)?.let { it.floatOrNull ?: it.content.toFloatOrNull() }
        ?: error("no $k")
    private fun JsonObject.numOr(k: String, d: Float) = runCatching { num(k) }.getOrDefault(d)
    private fun JsonObject.color(k: String) = str(k).takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }

    /** [x, y, x, y…] or [[x, y], [x, y]…]. */
    private fun JsonObject.points(): FloatArray {
        val arr = this["points"]!!.jsonArray
        val flat = if (arr.firstOrNull() is JsonArray) arr.flatMap { it.jsonArray } else arr
        val values = flat.map { it.jsonPrimitive.let { p -> p.floatOrNull ?: p.content.toFloat() } }
        return values.take(values.size / 2 * 2).toFloatArray()
    }

    fun writeBoard(b: Board): JsonObject = buildJsonObject {
        put("id", b.id); put("created", b.created); put("updated", b.updated)
        put("camera", buildJsonObject { put("x", b.camera.x); put("y", b.camera.y); put("zoom", b.camera.zoom) })
        put("elements", JsonArray(b.elements.map { write(it) }))
    }

    fun readBoard(o: JsonObject): Board {
        val cam = o["camera"]?.jsonObject
        var n = 0
        return Board(
            id = o.str("id"),
            created = o.str("created").toLongOrNull() ?: 0L,
            updated = o.str("updated").toLongOrNull() ?: 0L,
            elements = o["elements"]?.jsonArray.orEmpty().mapNotNull { read(it.jsonObject) { "e${n++}" } },
            camera = cam?.let { Camera(it.numOr("x", 0f), it.numOr("y", 0f), it.numOr("zoom", 1f)) } ?: Camera(),
        )
    }
}
