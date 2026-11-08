package com.aura.launcher.gestures

import android.content.Context
import android.util.Log
import com.aura.launcher.control.GlobalAction
import com.aura.launcher.data.writeTextAtomically
import com.aura.launcher.data.readTextAtomically
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

/**
 * One step of what a gesture does.
 *
 * A gesture holds a list of these rather than one, because the useful ones are
 * compound: the point of drawing a shape instead of tapping three times is that
 * three things happen. They run in the order they are listed and each waits for
 * the last — "take what is copied, then send it to him on WhatsApp" is two
 * steps that only make sense that way round.
 *
 * Almost nothing here is new capability. Every step is something the launcher
 * already does from a screen somewhere, and the one genuinely new idea is that
 * they can be strung together with a value passed between them — see [CARRIED].
 *
 * The interesting one is [AppShortcut]. Android lets a launcher read the
 * shortcuts every installed app publishes for itself — the list under a long
 * press on its icon — so the palette of steps is not a list somebody wrote here
 * but whatever is on the user's phone: "New chat" in WhatsApp, a pinned
 * conversation, "Scan" in the bank's app. Nobody has to have thought of them.
 */
@Serializable
sealed interface GestureAction {

    /** A short line describing this step, for the list in Settings. */
    fun summary(): String

    /**
     * One of an app's own shortcuts, as the app publishes it.
     *
     * The label is stored alongside the id because the id is opaque and the
     * app can withdraw it — a row reading "Shortcut 7f3a is gone" helps nobody.
     */
    @Serializable
    data class AppShortcut(
        val packageName: String,
        val shortcutId: String,
        val label: String,
        val appLabel: String,
    ) : GestureAction {
        override fun summary() = "$appLabel: $label"
    }

    /**
     * Hand text to another app, the way the share sheet does.
     *
     * The one step that turns "send this to him on WhatsApp" into something
     * that does not need the screen driven. It opens the app's own composer
     * with the text already in it; the send itself stays with the user, which
     * is the right side of that line for something a shape can trigger by
     * accident.
     */
    @Serializable
    data class SendText(val packageName: String, val appLabel: String, val text: String) :
        GestureAction {
        override fun summary() = "Send to $appLabel: ${text.take(30)}"
    }

    @Serializable
    data class OpenLink(val url: String) : GestureAction {
        override fun summary() = "Open ${url.take(40)}"
    }

    /** Put something on the clipboard, or take what is on it. */
    @Serializable
    data class Clipboard(val read: Boolean, val text: String = "") : GestureAction {
        override fun summary() = if (read) "Take what is copied" else "Copy: ${text.take(30)}"
    }

    /**
     * Do nothing, for a moment.
     *
     * Not padding. A step that opens an app hands control to Android and comes
     * back immediately — the app is not on screen yet, and whatever the next
     * step does to it happens to the screen that is still leaving.
     */
    @Serializable
    data class Wait(val millis: Long) : GestureAction {
        override fun summary() = "Wait ${millis / 1000f}s"
    }

    @Serializable
    data class OpenApp(val packageName: String, val label: String) : GestureAction {
        override fun summary() = "Open $label"
    }

    /**
     * Show every gesture that has been taught, and let one be tapped.
     *
     * The one gesture whose job is the others. A shape is remembered by the
     * hand, not by the eye, and a hand forgets — which leaves a feature that
     * works perfectly and is used for the two gestures somebody happens to
     * recall. Being reachable *by* a gesture is the point: it costs nothing to
     * find out what you have, from wherever you are, without opening settings.
     */
    @Serializable
    data object ShowGestures : GestureAction {
        override fun summary() = "Show my gestures"
    }

    @Serializable
    data class Torch(val on: Boolean) : GestureAction {
        override fun summary() = if (on) "Flashlight on" else "Flashlight off"
    }

    @Serializable
    data class System(val action: GlobalAction) : GestureAction {
        override fun summary() = action.description
    }
}

/**
 * The token a step uses to pick up what the preceding steps produced.
 *
 * One value, not a graph of them. On a phone, a workflow builder with named
 * variables and wires between them is a desktop tool that has been shrunk until
 * it no longer works; one implicit stream of results covers the things anybody
 * actually strings together — look up a date, run a shortcut, then reason over
 * both — and it needs no wiring at all.
 *
 * Never typed by hand. The builder offers it as a chip and inserts it, which is
 * also why it can be an awkward literal: nobody has to remember the spelling.
 */
const val CARRIED = "{it}"

/** The step's text with [CARRIED] replaced by everything produced so far. */
fun fillCarried(text: String, carried: String): String =
    if (!text.contains(CARRIED)) text else text.replace(CARRIED, carried)

/** Adds one step's non-empty result to the stream passed through the workflow. */
fun appendCarried(carried: String, produced: String?): String {
    val next = produced?.trim().orEmpty()
    if (next.isEmpty()) return carried
    if (carried.isBlank()) return next
    return "$carried\n\n---\n\n$next"
}

/**
 * A shape the user drew, and what it is for.
 *
 * [templates] are the drawings themselves, already put in the standard form by
 * [GestureShape.normalise] — several of them, because a shape drawn once is a
 * shape that only matches when it is drawn the same way twice, and nobody draws
 * anything the same way twice.
 */
@Serializable
data class Gesture(
    val id: String,
    val name: String,
    val templates: List<List<SavedDot>>,
    val actions: List<GestureAction>,
    val enabled: Boolean = true,
    /**
     * How many of [templates] the user drew in the teaching flow.
     *
     * The rest were learned from strokes that matched — see [GestureLearning].
     * The split matters because only the learned ones may be replaced, so the
     * shape somebody sat down and taught survives however far their hand
     * drifts.
     *
     * Zero on anything saved before gestures could learn, and read as "all of
     * them" by [GestureLearning.anchorCount]: those drawings were all taught by
     * hand, because at the time there was no other way in.
     */
    val taught: Int = 0,
) {
    /** The templates in the form the recogniser compares against. */
    fun shapes(): List<List<Dot>> =
        templates.map { stroke -> stroke.map { Dot(it.x, it.y, it.s) } }

    fun describe(): String = actions.joinToString(", ") { it.summary() }
}

/**
 * A point, in the form that goes on disk.
 *
 * [s] is where a stroke starts. Defaulted, so a template written before shapes
 * could have more than one stroke still reads back — it simply has no
 * boundaries in it, which is exactly true of a shape drawn in one go.
 */
@Serializable
data class SavedDot(val x: Float, val y: Float, val s: Boolean = false)

fun List<Dot>.saved(): List<SavedDot> = map { SavedDot(it.x, it.y, it.start) }

/**
 * The gestures the user has taught, on disk and in memory.
 *
 * Same shape as [com.aura.launcher.macros.MacroStore] and for the same reasons:
 * a small JSON file in the app's own directory, read once at startup, written
 * whenever it changes, and served to everything else as a flow.
 *
 * Normalised on the way in rather than on the way out. Recognition happens on a
 * finger coming up, with a window on screen waiting to be told what it caught,
 * and resampling every template at that moment would put the work in the one
 * place there is none to spare.
 */
class GestureStore(private val context: Context) {

    private val file: File by lazy { File(context.filesDir, FILE_NAME) }
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
    }
    private val lock = Mutex()

    private val _gestures = MutableStateFlow<List<Gesture>>(emptyList())
    val gestures: StateFlow<List<Gesture>> = _gestures.asStateFlow()

    suspend fun load() {
        val read = withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) emptyList() else json.decodeFromString<List<Gesture>>(file.readTextAtomically())
            }.getOrElse {
                Log.w(TAG, "Could not read gestures; starting empty", it)
                emptyList()
            }
        }
        lock.withLock { _gestures.value = read }
    }

    /**
     * Saves a gesture, replacing one of the same name.
     *
     * Returns null when there is nothing usable to save. Both refusals are
     * about the same thing: a gesture that cannot be recognised or cannot do
     * anything is a row in a list that quietly never fires.
     */
    suspend fun save(
        name: String,
        drawings: List<List<Dot>>,
        actions: List<GestureAction>,
        replacing: String? = null,
    ): Gesture? {
        val cleanName = name.trim().take(MAX_NAME)
        val shapes = drawings.filter { it.isNotEmpty() }.take(GestureLearning.MAX_TEMPLATES)
        if (cleanName.isEmpty() || shapes.isEmpty() || actions.isEmpty()) return null

        var stored: Gesture? = null
        mutate { list ->
            val existing = replacing?.let { id -> list.firstOrNull { it.id == id } }
                ?: list.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
            stored = Gesture(
                id = existing?.id ?: UUID.randomUUID().toString().take(10),
                name = cleanName,
                templates = shapes.map { it.saved() },
                actions = actions,
                enabled = existing?.enabled ?: true,
                // Editing a gesture without touching its shape hands the whole
                // set back — learned drawings included — so counting them as
                // taught here would freeze the gesture the first time its
                // steps were changed. Unchanged shapes keep the old split;
                // anything redrawn is taught by definition.
                taught = if (existing != null && existing.shapes() == shapes) {
                    existing.taught
                } else {
                    shapes.size
                },
            )
            if (existing == null) list + stored!! else list.map { if (it.id == existing.id) stored!! else it }
        }
        return stored
    }

    /**
     * Keeps the stroke that just matched [id] as another example of it.
     *
     * Called on the finger coming up, from whichever surface caught the shape.
     * Silent about doing nothing: [GestureLearning] refuses far more strokes
     * than it accepts, and every refusal is an ordinary outcome rather than a
     * failure — the gesture ran either way, which is all the user asked for.
     *
     * @return true when the gesture actually gained an example.
     */
    suspend fun reinforce(id: String, candidate: List<Dot>, score: Float): Boolean {
        // Worked out under the lock, against the list as it is at that moment.
        //
        // Reading it first and writing afterwards left a window: a gesture
        // recognised while the builder was open would be rebuilt from the copy
        // held before the user's edit, and writing it back would quietly undo
        // that edit. The window is small and the loss is the user's own work.
        var grown: Gesture? = null
        mutateIfChanged { list ->
            val gesture = list.firstOrNull { it.id == id } ?: return@mutateIfChanged null
            val next = GestureLearning.reinforced(gesture, candidate, score, list)
                ?: return@mutateIfChanged null
            grown = next
            list.map { if (it.id == next.id) next else it }
        }
        val kept = grown ?: return false
        Log.i(TAG, "${kept.name}: kept a stroke, ${kept.templates.size} example(s) on file")
        return true
    }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    private suspend fun mutate(block: (List<Gesture>) -> List<Gesture>) {
        mutateIfChanged(block)
    }

    /**
     * The same, for a change that may turn out not to be one.
     *
     * A block returning null means "nothing to do", and nothing is published
     * or written. Learning a stroke refuses far more often than it accepts,
     * and without this every rejected stroke would still rewrite the file.
     */
    private suspend fun mutateIfChanged(block: (List<Gesture>) -> List<Gesture>?) {
        val next = lock.withLock {
            val updated = block(_gestures.value)?.take(MAX_GESTURES) ?: return@withLock null
            _gestures.value = updated
            updated
        } ?: return
        withContext(Dispatchers.IO) {
            runCatching { file.writeTextAtomically(json.encodeToString(next)) }
                .onFailure { Log.w(TAG, "Could not save gestures", it) }
        }
    }

    private companion object {
        const val FILE_NAME = "gestures.json"
        const val TAG = "GestureStore"
        const val MAX_NAME = 40

        /**
         * More than this and the user is drawing shapes the recogniser cannot
         * keep apart, whatever the list says.
         */
        const val MAX_GESTURES = 24
    }
}
