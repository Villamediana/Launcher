package com.aura.launcher.sketch

import android.content.Context
import android.graphics.Bitmap
import com.aura.launcher.data.readTextAtomically
import com.aura.launcher.data.writeTextAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID

/** A board in the list: enough to show it and open it. */
data class BoardEntry(val id: String, val updated: Long, val thumbnail: File)

/**
 * The drawings, one JSON file each, with a picture of how each looked when it
 * was last closed. Pictures from the web live beside them and go with the
 * board that holds them.
 */
class BoardStore(context: Context) {
    private val root = File(context.filesDir, "sketches")
    private val boards = File(root, "boards")
    private val thumbs = File(root, "thumbs")
    val pictures = File(root, "pictures")

    private val _entries = MutableStateFlow<List<BoardEntry>>(emptyList())
    val entries: StateFlow<List<BoardEntry>> = _entries.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _entries.value = boards.listFiles { f -> f.extension == "json" }.orEmpty()
            .map { BoardEntry(it.nameWithoutExtension, it.lastModified(), File(thumbs, "${it.nameWithoutExtension}.png")) }
            .sortedByDescending { it.updated }
    }

    fun fresh(): Board {
        val now = System.currentTimeMillis()
        return Board(UUID.randomUUID().toString(), now, now)
    }

    suspend fun load(id: String): Board? = withContext(Dispatchers.IO) {
        val file = File(boards, "$id.json")
        if (!file.exists()) return@withContext null
        runCatching { ElementCodec.readBoard(Json.parseToJsonElement(file.readTextAtomically()).jsonObject) }.getOrNull()
    }

    /** Saves [board], or forgets it when it is empty: a blank page is not a drawing. */
    suspend fun save(board: Board, thumbnail: Bitmap?) = withContext(Dispatchers.IO) {
        if (board.isEmpty) {
            delete(board.id)
            return@withContext
        }
        boards.mkdirs(); thumbs.mkdirs()
        File(boards, "${board.id}.json").writeTextAtomically(ElementCodec.writeBoard(board).toString())
        thumbnail?.let { bmp -> File(thumbs, "${board.id}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        refresh()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        load(id)?.elements?.filterIsInstance<Element.Picture>()?.forEach { if (it.file.isNotEmpty()) File(pictures, it.file).delete() }
        File(boards, "$id.json").delete()
        File(thumbs, "$id.png").delete()
        refresh()
    }
}
