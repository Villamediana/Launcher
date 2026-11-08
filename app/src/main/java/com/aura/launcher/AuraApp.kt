package com.aura.launcher

import android.app.Application
import android.util.Log
import com.aura.launcher.apps.AppRepository
import com.aura.launcher.apps.SemanticIndex
import com.aura.launcher.data.CalendarRepository
import com.aura.launcher.data.CrashLog
import com.aura.launcher.data.Settings
import com.aura.launcher.data.SettingsRepository
import com.aura.launcher.data.WeatherRepository
import com.aura.launcher.updater.AppUpdater
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single owner of everything with a lifetime longer than a screen: the app
 * list, settings, the stores behind the home screen's cards. A launcher is
 * relaunched constantly as the user leaves and returns, so anything expensive
 * that lives in an Activity gets rebuilt over and over — this is where it does
 * not.
 */
class AuraApp : Application() {

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            // Most UI-triggered persistence work lands here. A full disk or a
            // transient DataStore failure must fail that operation, not kill
            // the launcher process and take the home screen down with it.
            CoroutineExceptionHandler { _, e ->
                Log.e(TAG, "Uncaught failure on the application scope", e)
            }
    )

    lateinit var settingsRepository: SettingsRepository private set
    lateinit var appRepository: AppRepository private set
    lateinit var calendar: CalendarRepository private set
    lateinit var weather: WeatherRepository private set
    lateinit var gestures: com.aura.launcher.gestures.GestureStore private set
    lateinit var todos: com.aura.launcher.todo.TodoStore private set
    lateinit var timers: com.aura.launcher.timer.TimerStore private set
    lateinit var updater: AppUpdater private set
    lateinit var geo: com.aura.launcher.web.Geo private set
    /** What a gesture feels like under the finger. */
    lateinit var haptics: com.aura.launcher.ui.Haptics private set

    /** The drawing boards, and the model that watches them. Only touched once a board opens. */
    val boards by lazy { com.aura.launcher.sketch.BoardStore(this) }
    val sketchModel by lazy { com.aura.launcher.sketch.SketchModel(this) }

    /** Latest settings, readable synchronously from non-suspending call sites. */
    val currentSettings = MutableStateFlow(Settings())

    /**
     * The favourite apps' keys, held here rather than collected per screen.
     *
     * Because *when* they arrive is visible. Read once here, before the first
     * frame is allowed out, and the row of favourites is simply there instead
     * of animating in under the user's thumb on every unlock.
     */
    val favoriteKeys = MutableStateFlow<List<String>>(emptyList())

    /**
     * False until settings have been read from disk once, so the first frame
     * is never the defaults' wrong onboarding step or wrong theme.
     */
    val settingsLoaded = MutableStateFlow(false)

    private val settingsSeen = MutableStateFlow(false)
    private val favoritesSeen = MutableStateFlow(false)

    /** Rebuilt whenever the app list or usage counters change. */
    val semanticIndex = MutableStateFlow(SemanticIndex(emptyList()))

    /**
     * Runtime permission bridge. MainActivity installs a real implementation
     * while it is in the foreground; without an Activity a request cannot be
     * shown, so the default simply reports "not granted".
     */
    @Volatile
    var permissionRequester: (suspend (String) -> Boolean) = { false }

    override fun onCreate() {
        super.onCreate()

        CrashLog.install(this)
        settingsRepository = SettingsRepository(this)
        // Aliases are read fresh on every repo reload; caching them here keeps
        // the read synchronous, and the flow below triggers a reload whenever
        // the user renames or clears an app.
        val currentAliases = MutableStateFlow<Map<String, String>>(emptyMap())
        appRepository = AppRepository(this, scope, aliasesProvider = { currentAliases.value })
        calendar = CalendarRepository(this)
        weather = WeatherRepository(this)
        gestures = com.aura.launcher.gestures.GestureStore(this)
        todos = com.aura.launcher.todo.TodoStore(this)
        timers = com.aura.launcher.timer.TimerStore(this)
        updater = AppUpdater(this)
        geo = com.aura.launcher.web.Geo()
        haptics = com.aura.launcher.ui.Haptics(this) { currentSettings.value.hapticsEnabled }

        scope.launch {
            settingsRepository.settings.collect {
                currentSettings.value = it
                settingsSeen.value = true
                settingsLoaded.value = favoritesSeen.value
            }
        }
        scope.launch {
            settingsRepository.favorites.collect {
                favoriteKeys.value = it
                favoritesSeen.value = true
                settingsLoaded.value = settingsSeen.value
            }
        }
        scope.launch { gestures.load() }
        // And the timer, which may have been running when the launcher was last killed.
        scope.launch {
            timers.load()
            com.aura.launcher.timer.TimerAlarm.sync(this@AuraApp, timers.state.value)
        }

        // To-dos, plus the reminders that go with them. Re-armed on every start:
        // a reboot or an update drops them.
        scope.launch {
            todos.load()
            // Keyed on the settings as well as the data: turning the widget off
            // is exactly the moment its alarms have to be taken out of the
            // system's alarm clock.
            combine(todos.data, currentSettings) { data, _ -> data }
                .collect { data ->
                    withContext(Dispatchers.Default) {
                        com.aura.launcher.todo.TodoAlarms.sync(this@AuraApp, data)
                    }
                }
        }

        scope.launch {
            combine(appRepository.apps, settingsRepository.usage) { apps, usage ->
                // Normalising names and building trigram sets is CPU work; off
                // the main dispatcher so it never competes with opening the drawer.
                withContext(Dispatchers.Default) { SemanticIndex(apps, usage) }
            }.collect { semanticIndex.value = it }
        }

        appRepository.start()

        // Alias changes drive an app-list reload — the sort key and the
        // display strings both depend on the alias map.
        scope.launch {
            settingsRepository.aliases.collect { map ->
                if (map != currentAliases.value) {
                    currentAliases.value = map
                    appRepository.reload()
                }
            }
        }
    }

    internal companion object {
        const val TAG = "AuraApp"
    }
}
