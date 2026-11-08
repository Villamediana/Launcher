package com.aura.launcher.sketch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.hypot

/** What the model wants done: these go, those arrive in their place. */
data class Change(val remove: List<String>, val add: List<Element>)

/**
 * The conversation between the board and the model.
 *
 * The model is only asked when the hand circles something: it sees a picture
 * of the circled area and the elements in it, the circled ones marked, and
 * answers with changes in the board's own vocabulary ([ElementCodec]). What it
 * does with them is its call — there is no list of shapes to look for or
 * words to react to here.
 */
object Assist {

    val SYSTEM = """
Someone is drawing by hand on an infinite whiteboard on their phone. When they want your help, they circle something — a circle is a request: work your magic on what they circled. They never talk to you directly; what they drew and wrote is the request.

You get a picture of the circled area (it covers the "view" box) and the elements there as JSON, in board coordinates: "circled_box" is the area inside the circle, and elements with "circled": true are inside it. The rest is context.

Read the picture first: it is what they see. Handwriting inside the circle (or right next to it) may be an instruction to you — "make this a table", "translate", "draw a cat", "colour it in" — then do what it says to what was circled, and the instruction itself goes away. With no instruction, make what was circled into what they meant, clean and real: whatever would delight them. Handwriting becomes typed text; a rough shape the shape they were drawing; a sketch a neat drawing made of shapes and lines with the same meaning, place and size; something that asks for an answer, a result or a picture gets it, placed next to it. Use your judgement — nothing here is a fixed rule — and always do something with a circle: they asked.

Answer with JSON only, no prose:
{"changes":[{"remove":"circled","add":[<elements>]}]}
"remove" is "circled" to take away everything they circled (the usual case when you replace it), or a list of ids, or [] to keep it all and only add. Only remove what was circled, or an instruction written to you next to the circle.

The board is dark. Their hand-drawn ink is amber; what you add is drawn in a warm off-white unless you give it a color, and any color you pick must read on dark.

Elements (board coordinates — place what you make where the circle was, growing out from it if it needs room; colors "#RRGGBB", optional):
{"type":"text","text":"…","x":left,"y":top,"size":letter height}  (width ≈ 0.56 × size per character, height ≈ 1.25 × size per line)
{"type":"rect"|"ellipse"|"triangle"|"diamond","x":left,"y":top,"w":…,"h":…,"color":…,"fill":…}
{"type":"line","points":[x1,y1,x2,y2,…],"arrow":true|false,"closed":true|false,"fill":…}  (closed = polygon)
{"type":"ink","points":[x1,y1,…],"width":…}  (freehand, for curves nothing else can draw)
{"type":"picture","query":"what to search the web for","x":…,"y":…,"w":…,"h":…}  (the board finds and places it)

Write text in the language they write in, at about the size of their handwriting — never much smaller. When what you make is bigger than what they circled (a mind map, a diagram, a table, a plan), give it room: the board has no edges, so let it grow out from where the circle was, without covering anything that was outside it.
""".trim()

    /** What the model reads besides the picture. */
    fun describe(elements: List<Element>, circled: Set<String>, view: Box, inside: Box): String {
        val visible = elements.filter { it.bounds.intersects(view) || it.id in circled }
        fun box(b: Box) = buildJsonObject {
            put("x", Math.round(b.left)); put("y", Math.round(b.top))
            put("w", Math.round(b.width)); put("h", Math.round(b.height))
        }
        val json = buildJsonObject {
            put("view", box(view))
            put("circled_box", box(inside))
            put("elements", JsonArray(visible.map { e ->
                val shown = if (e is Element.Ink) e.copy(points = simplify(e.points, MAX_POINTS)) else e
                val o = ElementCodec.write(shown, round = true)
                if (e.id in circled) JsonObject(o + ("circled" to JsonPrimitive(true))) else o
            }))
        }
        return json.toString()
    }

    /**
     * The changes in a reply. Ids the model gives new elements are not used —
     * every arrival gets a fresh one — so nothing it writes can collide with
     * what is on the board. "circled", in place of a list or in it, is
     * everything in [circled].
     */
    fun parse(reply: String, circled: Collection<String>, newId: () -> String): List<Change> {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val root = runCatching { Json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull() ?: return emptyList()
        return root["changes"]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty().mapNotNull { c ->
            val o = runCatching { c.jsonObject }.getOrNull() ?: return@mapNotNull null
            val remove = when (val r = o["remove"]) {
                is JsonPrimitive -> listOf(r.content)
                is JsonArray -> r.mapNotNull { (it as? JsonPrimitive)?.content }
                else -> emptyList()
            }.flatMap { if (it == "circled") circled else listOf(it) }.distinct()
            val add = o["add"]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty()
                .mapNotNull { a -> runCatching { a.jsonObject }.getOrNull()?.let { ElementCodec.read(it) { "" } } }
                .map { reid(it, newId()) }
            Change(remove, add).takeIf { remove.isNotEmpty() || add.isNotEmpty() }
        }
    }

    /**
     * The board after [changes], or null when one of them is about something
     * no longer there — the hand moved on while the model was thinking, and
     * an answer to a board that has changed is an answer to a different
     * question.
     */
    fun apply(elements: List<Element>, changes: List<Change>): List<Element>? {
        val ids = elements.mapTo(HashSet()) { it.id }
        if (changes.any { c -> c.remove.any { it !in ids } }) return null
        val gone = changes.flatMapTo(HashSet()) { it.remove }
        return elements.filter { it.id !in gone } + changes.flatMap { it.add }
    }

    private fun reid(e: Element, id: String): Element = when (e) {
        is Element.Ink -> e.copy(id = id)
        is Element.Shape -> e.copy(id = id)
        is Element.Line -> e.copy(id = id)
        is Element.Text -> e.copy(id = id)
        is Element.Picture -> e.copy(id = id)
    }

    /**
     * A stroke cut down to at most [max] points (Ramer–Douglas–Peucker, with
     * the tolerance raised until it fits). The picture carries the detail;
     * this only has to say where things are.
     */
    fun simplify(points: FloatArray, max: Int): FloatArray {
        val n = points.size / 2
        if (n <= max) return points
        var tolerance = 0.5f
        while (true) {
            val keep = BooleanArray(n)
            keep[0] = true; keep[n - 1] = true
            rdp(points, 0, n - 1, tolerance, keep)
            if (keep.count { it } <= max) {
                return keep.indices.filter { keep[it] }.flatMap { listOf(points[it * 2], points[it * 2 + 1]) }.toFloatArray()
            }
            tolerance *= 1.6f
        }
    }

    private fun rdp(p: FloatArray, first: Int, last: Int, tolerance: Float, keep: BooleanArray) {
        if (last <= first + 1) return
        val ax = p[first * 2]; val ay = p[first * 2 + 1]
        val bx = p[last * 2]; val by = p[last * 2 + 1]
        val len = hypot(bx - ax, by - ay)
        var worst = -1f
        var at = -1
        for (i in first + 1 until last) {
            val x = p[i * 2]; val y = p[i * 2 + 1]
            val d = if (len == 0f) hypot(x - ax, y - ay) else abs((by - ay) * x - (bx - ax) * y + bx * ay - by * ax) / len
            if (d > worst) { worst = d; at = i }
        }
        if (worst > tolerance) {
            keep[at] = true
            rdp(p, first, at, tolerance, keep)
            rdp(p, at, last, tolerance, keep)
        }
    }

    private const val MAX_POINTS = 32
}
