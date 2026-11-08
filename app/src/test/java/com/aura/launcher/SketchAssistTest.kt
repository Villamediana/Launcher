package com.aura.launcher

import com.aura.launcher.sketch.Assist
import com.aura.launcher.sketch.Board
import com.aura.launcher.sketch.Box
import com.aura.launcher.sketch.Camera
import com.aura.launcher.sketch.Element
import com.aura.launcher.sketch.ElementCodec
import com.aura.launcher.sketch.ShapeKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawing board's side of the conversation with the model: what it sends,
 * how it reads what comes back, and when it refuses to apply it.
 */
class SketchAssistTest {
    private var n = 0
    private fun id() = "n${n++}"

    private val square = Element.Ink("a", floatArrayOf(0f, 0f, 100f, 2f, 98f, 100f, 1f, 99f, 0f, 0f), 4f)
    private val word = Element.Ink("b", floatArrayOf(200f, 0f, 260f, 20f), 4f)

    @Test fun `a reply replaces the strokes it names with what it adds`() {
        val reply = """Here you go: {"changes":[{"remove":["a"],"add":[{"type":"rect","x":0,"y":0,"w":100,"h":100}]}]}"""
        val changes = Assist.parse(reply, emptyList(), ::id)
        val after = Assist.apply(listOf(square, word), changes)!!
        assertEquals(listOf("b", "n0"), after.map { it.id })
        val rect = after[1] as Element.Shape
        assertEquals(ShapeKind.RECT, rect.kind)
        assertEquals(Box(0f, 0f, 100f, 100f), rect.box)
    }

    @Test fun `remove circled takes away everything circled`() {
        val changes = Assist.parse("""{"changes":[{"remove":"circled","add":[{"type":"text","text":"Luna","x":0,"y":0,"size":30}]}]}""", listOf("a", "b"), ::id)
        val after = Assist.apply(listOf(square, word), changes)!!
        assertEquals(listOf("n0"), after.map { it.id })
    }

    @Test fun `an answer about strokes that are gone is dropped`() {
        val changes = Assist.parse("""{"changes":[{"remove":["a"],"add":[]}]}""", emptyList(), ::id)
        assertNull(Assist.apply(listOf(word), changes))
    }

    @Test fun `leaving the board alone is a valid answer`() {
        assertTrue(Assist.parse("""{"changes":[]}""", emptyList(), ::id).isEmpty())
        assertTrue(Assist.parse("nothing to do", emptyList(), ::id).isEmpty())
    }

    @Test fun `ids the model invents never reach the board`() {
        val changes = Assist.parse("""{"changes":[{"add":[{"id":"b","type":"text","text":"5","x":"10","y":20,"size":30}]}]}""", emptyList(), ::id)
        val text = changes.single().add.single() as Element.Text
        assertEquals("n0", text.id)
        assertEquals(10f, text.x)
    }

    @Test fun `points may arrive as pairs and pictures as a query`() {
        val changes = Assist.parse(
            """{"changes":[{"add":[
                {"type":"arrow","points":[[0,0],[50,50]]},
                {"type":"picture","query":"a red house","x":0,"y":0,"w":200,"h":150},
                {"type":"spaceship","x":0,"y":0}
            ]}]}""",
            emptyList(),
            ::id,
        )
        val (arrow, picture) = changes.single().add
        assertTrue((arrow as Element.Line).arrow)
        assertEquals(4, arrow.points.size)
        assertEquals("a red house", (picture as Element.Picture).query)
        assertEquals(2, changes.single().add.size)
    }

    @Test fun `the model is told what was circled, with few points each`() {
        val long = Element.Ink("c", FloatArray(400) { if (it % 2 == 0) it.toFloat() else (it % 7).toFloat() }, 4f)
        val json = Json.parseToJsonElement(Assist.describe(listOf(square, long), setOf("c"), Box(0f, 0f, 1000f, 1000f), Box(0f, 0f, 500f, 500f))).jsonObject
        val elements = json["elements"]!!.jsonArray.map { it.jsonObject }
        assertEquals(null, elements[0]["circled"])
        assertEquals("true", elements[1]["circled"]!!.jsonPrimitive.content)
        assertTrue(elements[1]["points"]!!.jsonArray.size <= 64)
    }

    @Test fun `a board survives the file`() {
        val board = Board(
            "x", 1L, 2L,
            listOf(
                square,
                Element.Text("t", "2+3 = 5", 10f, 20f, 28f, "#FFCC00"),
                Element.Line("l", floatArrayOf(0f, 0f, 10f, 10f, 20f, 0f), closed = true, fill = "#334455"),
                Element.Picture("p", Box(0f, 0f, 30f, 20f), file = "f.jpg"),
            ),
            Camera(5f, 6f, 1.5f),
        )
        val back = ElementCodec.readBoard(Json.parseToJsonElement(ElementCodec.writeBoard(board).toString()).jsonObject)
        assertEquals(board, back)
    }

    @Test fun `simplifying keeps the ends and the corners`() {
        val p = FloatArray(202) { i -> if (i % 2 == 0) (i / 2).toFloat() else if (i / 2 <= 50) (i / 2).toFloat() else (100 - i / 2).toFloat() }
        val s = Assist.simplify(p, 8)
        assertTrue(s.size / 2 <= 8)
        assertEquals(0f, s[0]); assertEquals(100f, s[s.size - 2])
        assertTrue((0 until s.size / 2).any { s[it * 2] == 50f && s[it * 2 + 1] == 50f })
    }
}
