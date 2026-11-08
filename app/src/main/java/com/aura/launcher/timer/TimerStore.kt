package com.aura.launcher.timer

import android.content.Context
import android.util.Log
import com.aura.launcher.data.readTextAtomically
import com.aura.launcher.data.writeTextAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The timer, on disk.
 *
 * One small file, the same shape every other store in this app has. What makes
 * it worth persisting at all is that the thing it holds is a clock somebody
 * started: a launcher is killed and rebuilt constantly, and a timer that lost
 * its run every time the home screen went away would be a stopwatch that only
 * works while you watch it.
 *
 * Written on every change, which is a handful of writes per session — a start,
 * a pause, a reset. Not on every tick: the face is worked out from [TimerState]
 * and the wall clock, so there is nothing to save between one second and the
 * next.
 */
class TimerStore(private val context: Context) {

    private val file: File by lazy { File(context.filesDir, FILE_NAME) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()

    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state.asStateFlow()

    suspend fun load() {
        val read = withContext(Dispatchers.IO) {
            runCatching {
                if (!file.isFile) TimerState()
                else json.decodeFromString<TimerState>(file.readTextAtomically())
            }.getOrElse {
                Log.w(TAG, "Could not read the timer; starting fresh", it)
                TimerState()
            }
        }
        lock.withLock { _state.value = read }
    }

    /**
     * Applies a change and writes it.
     *
     * The block is the pure part — one of [Timers] — so what is stored can
     * never be a state the rules would not have produced.
     */
    suspend fun edit(block: (TimerState) -> TimerState) {
        val next = lock.withLock {
            val updated = block(_state.value)
            _state.value = updated
            updated
        }
        withContext(Dispatchers.IO) {
            runCatching { file.writeTextAtomically(json.encodeToString(next)) }
                .onFailure { Log.w(TAG, "Could not save the timer", it) }
        }
    }

    private companion object {
        const val TAG = "TimerStore"
        const val FILE_NAME = "timer.json"
    }
}
