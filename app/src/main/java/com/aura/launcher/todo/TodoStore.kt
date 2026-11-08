package com.aura.launcher.todo

import android.content.Context
import com.aura.launcher.data.readTextAtomically
import com.aura.launcher.data.writeTextAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

class TodoStore(context: Context) {

    private val dir = context.filesDir
    private val file = File(dir, "todo.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }
    private val lock = Mutex()

    private val _data = MutableStateFlow(TodoData())
    val data: StateFlow<TodoData> = _data.asStateFlow()

    suspend fun load() = lock.withLock {
        _data.value = withContext(Dispatchers.IO) {
            if (file.isFile) {
                runCatching { json.decodeFromString<TodoData>(file.readTextAtomically()) }
                    .getOrDefault(TodoData())
            } else {
                // First run on a phone that had the medicine card: its
                // medicines become recurring to-dos, once, and the old file is
                // left where it was in case anything has to be rescued.
                val migrated = migrateCare(File(dir, "care.json"))
                if (migrated.todos.isNotEmpty()) {
                    runCatching { file.writeTextAtomically(json.encodeToString(migrated)) }
                }
                migrated
            }
        }
    }

    private suspend fun edit(block: (TodoData) -> TodoData) = lock.withLock {
        val next = block(_data.value)
        if (next == _data.value) return@withLock
        _data.value = next
        withContext(Dispatchers.IO) {
            runCatching { file.writeTextAtomically(json.encodeToString(next)) }
        }
        Unit
    }

    suspend fun upsert(todo: Todo) = edit { data ->
        val exists = data.todos.any { it.id == todo.id }
        data.copy(todos = if (exists) data.todos.map { if (it.id == todo.id) todo else it } else data.todos + todo)
    }

    suspend fun remove(id: String) = edit { data ->
        data.copy(todos = data.todos.filterNot { it.id == id }, done = data.done.filterNot { it.todoId == id })
    }

    suspend fun setArchived(id: String, archived: Boolean) = edit { data ->
        data.copy(todos = data.todos.map { if (it.id == id) it.copy(archived = archived) else it })
    }

    suspend fun setDone(todoId: String, slotAt: Long, done: Boolean, now: Long = System.currentTimeMillis()) =
        edit { data ->
            val without = data.done.filterNot { it.todoId == todoId && it.slotAt == slotAt }
            // History is kept, but not forever: a daily to-do adds 365 rows a year.
            val trimmed = if (done) (without + TodoDone(todoId, slotAt, now)).takeLast(MAX_DONE) else without
            data.copy(done = trimmed)
        }

    // ------------------------------------------------------------ migration --

    @Serializable
    private data class OldCare(val meds: List<OldMed> = emptyList(), val doses: List<OldDose> = emptyList())

    @Serializable
    private data class OldMed(
        val id: String,
        val name: String,
        val everyHours: Double = 8.0,
        val firstMinuteOfDay: Int = 8 * 60,
        val startDay: String,
        val endDay: String? = null,
        val doseAmount: Double = 0.0,
        val doseUnit: String = "",
        val archived: Boolean = false,
        val remind: Boolean = true,
    )

    @Serializable
    private data class OldDose(val medId: String, val slotAt: Long, val takenAt: Long)

    internal fun migrateCare(old: File): TodoData {
        if (!old.isFile) return TodoData()
        val care = runCatching { json.decodeFromString<OldCare>(old.readTextAtomically()) }.getOrNull()
            ?: return TodoData()
        return fromCare(care.meds.map { it.toTodo() }, care.doses.map { TodoDone(it.medId, it.slotAt, it.takenAt) })
    }

    private fun OldMed.toTodo(): Todo {
        val hours = everyHours.roundToInt().coerceAtLeast(1)
        val daily = hours % 24 == 0
        val dose = if (doseAmount > 0.0) {
            val amount = if (doseAmount % 1.0 == 0.0) doseAmount.toLong().toString() else doseAmount.toString()
            " · ${listOf(amount, doseUnit.trim()).filter { it.isNotEmpty() }.joinToString(" ")}"
        } else ""
        return Todo(
            id = id,
            title = name + dose,
            startDay = startDay,
            minuteOfDay = firstMinuteOfDay,
            repeat = if (daily) TodoRepeat.DAILY else TodoRepeat.HOURS,
            every = if (daily) hours / 24 else hours,
            endDay = endDay,
            remind = remind,
            archived = archived,
        )
    }

    private fun fromCare(todos: List<Todo>, done: List<TodoDone>) = TodoData(todos, done.takeLast(MAX_DONE))

    companion object {
        fun newId(): String = UUID.randomUUID().toString().take(8)

        private const val MAX_DONE = 2000
    }
}
