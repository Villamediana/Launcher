package com.aura.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aura.launcher.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aura_settings")

/**
 * The single place preferences become [Settings].
 *
 * There used to be two of these — one for the flow the UI reads, one inside
 * `update` — and adding a field to only the second meant every unrelated toggle
 * quietly wrote that field back to its default. Turning on the weather really
 * did turn off the notifications. One function, so a new field cannot be half
 * added.
 */
private fun Preferences.toSettings(): Settings {
    val d = Settings()
    return Settings(
        onboarded = this[SettingsRepository.Keys.onboarded] ?: d.onboarded,
        onboardingStep = this[SettingsRepository.Keys.onboardingStep] ?: d.onboardingStep,
        language = this[SettingsRepository.Keys.language]
            ?.let { name -> AppLanguage.entries.firstOrNull { it.name == name } },
        themeMode = this[SettingsRepository.Keys.themeMode].toEnum(d.themeMode),
        homeStyle = this[SettingsRepository.Keys.homeStyle].toEnum(d.homeStyle),
        dayFavorites = this[SettingsRepository.Keys.dayFavorites] ?: d.dayFavorites,
        liveWeather = this[SettingsRepository.Keys.liveWeather] ?: d.liveWeather,
        hapticsEnabled = this[SettingsRepository.Keys.haptics] ?: d.hapticsEnabled,
        clockStyle = this[SettingsRepository.Keys.clockStyle]
            ?.let { name -> ClockStyle.entries.firstOrNull { it.name == name } }
            ?: d.clockStyle,
        wallpaperPath = this[SettingsRepository.Keys.wallpaperPath] ?: d.wallpaperPath,
        wallpaperInk = this[SettingsRepository.Keys.wallpaperInk]
            ?.let { name -> WallpaperInk.entries.firstOrNull { it.name == name } }
            ?: d.wallpaperInk,
        wallpaperDim = (this[SettingsRepository.Keys.wallpaperDim] ?: d.wallpaperDim)
            .coerceIn(0f, 1f),
        animatedWallpaper = this[SettingsRepository.Keys.animatedWallpaper].toEnum(d.animatedWallpaper),
        hiddenWidgets = this[SettingsRepository.Keys.hiddenWidgets]
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: d.hiddenWidgets,
        homeLayout = this[SettingsRepository.Keys.homeLayout] ?: d.homeLayout,
        lockGestures = this[SettingsRepository.Keys.lockGestures] ?: d.lockGestures,
    )
}

/**
 * Reads and writes the launcher's preferences.
 *
 * The store is injectable so a test can hand over a temporary one. That is not
 * decoration: the one bug this file has actually had — an unrelated toggle
 * writing another field back to its default — is only catchable by writing,
 * toggling something else, and reading back.
 */
class SettingsRepository(
    context: Context,
    private val store: DataStore<Preferences> = context.dataStore,
) {

    internal object Keys {
        val onboarded = booleanPreferencesKey("onboarded")
        val onboardingStep = intPreferencesKey("onboarding_step")
        val language = stringPreferencesKey("app_language")
        val themeMode = stringPreferencesKey("theme_mode")
        val homeStyle = stringPreferencesKey("home_style")
        val dayFavorites = booleanPreferencesKey("day_favorites")
        val liveWeather = booleanPreferencesKey("live_weather")
        val clockStyle = stringPreferencesKey("clock_style")
        val wallpaperPath = stringPreferencesKey("wallpaper_path")
        val wallpaperInk = stringPreferencesKey("wallpaper_ink")
        val wallpaperDim = floatPreferencesKey("wallpaper_dim")
        val animatedWallpaper = stringPreferencesKey("animated_wallpaper")
        /** Hidden home widgets, one [HomeWidget.id] per line. */
        val hiddenWidgets = stringPreferencesKey("hidden_widgets")
        /** The home screen's slots, one per line. See [HomeLayout]. */
        val homeLayout = stringPreferencesKey("home_layout")
        val lockGestures = booleanPreferencesKey("lock_gestures")
        val haptics = booleanPreferencesKey("haptics")
        val favorites = stringPreferencesKey("favorites")
        val hidden = stringPreferencesKey("hidden_apps")
        val usage = stringPreferencesKey("usage_counts")
        /**
         * User-picked display names, `key=alias` per line, one entry per app.
         *
         * Keyed by [com.aura.launcher.apps.AppEntry.key] — packageName/className —
         * because that survives an app being renamed by its own developer, and
         * matches the same identity favourites and hidden already use.
         */
        val aliases = stringPreferencesKey("app_aliases")
        /**
         * `key=weight:lastUsedAt` per line — how much each app is used, with
         * the age of the number recorded alongside it so it can be discounted.
         * See [SettingsRepository.Habit].
         */
        val habits = stringPreferencesKey("app_habits")
    }

    val settings: Flow<Settings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    /** Favourite launcher entries, stored as `package/class` keys in order. */
    val favorites: Flow<List<String>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.favorites].orEmpty().split('\n').filter { s -> s.isNotBlank() } }

    val hidden: Flow<Set<String>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.hidden].orEmpty().split('\n').filter { s -> s.isNotBlank() }.toSet() }

    /** `key=count` pairs used to bias search results toward what is actually used. */
    val usage: Flow<Map<String, Int>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            prefs[Keys.usage].orEmpty().split('\n').mapNotNull { line ->
                val i = line.lastIndexOf('=')
                if (i <= 0) return@mapNotNull null
                val n = line.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
                line.substring(0, i) to n
            }.toMap()
        }

    /**
     * User-picked display names by app key.
     *
     * Parses the same `key=alias` per-line format as [usage]. An alias with `=`
     * inside it is preserved by splitting on the *first* `=` — the key never
     * contains one, aliases can.
     */
    val aliases: Flow<Map<String, String>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            prefs[Keys.aliases].orEmpty().split('\n').mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) return@mapNotNull null
                val alias = line.substring(i + 1).trim()
                if (alias.isBlank()) return@mapNotNull null
                line.substring(0, i) to alias
            }.toMap()
        }


    suspend fun update(block: (Settings) -> Settings) {
        store.edit { p ->
            val next = block(p.toSettings())
            p[Keys.onboarded] = next.onboarded
            p[Keys.onboardingStep] = next.onboardingStep
            // Removed rather than skipped when it is null.
            //
            // Null means "never answered", and leaving the old key behind makes
            // this the one field `update` cannot actually set. Nothing noticed
            // while the only writer was a copy of the current settings — but a
            // restore hands over a whole Settings, and a backup taken before
            // the language was chosen would leave the old one in place.
            val language = next.language
            if (language != null) p[Keys.language] = language.name else p.remove(Keys.language)
            p[Keys.themeMode] = next.themeMode.name
            p[Keys.homeStyle] = next.homeStyle.name
            p[Keys.dayFavorites] = next.dayFavorites
            p[Keys.liveWeather] = next.liveWeather
            p[Keys.haptics] = next.hapticsEnabled
            p[Keys.clockStyle] = next.clockStyle.name
            p[Keys.wallpaperPath] = next.wallpaperPath
            p[Keys.wallpaperInk] = next.wallpaperInk.name
            p[Keys.wallpaperDim] = next.wallpaperDim.coerceIn(0f, 1f)
            p[Keys.animatedWallpaper] = next.animatedWallpaper.name
            p[Keys.hiddenWidgets] = next.hiddenWidgets.joinToString("\n")
            p[Keys.homeLayout] = next.homeLayout
            p[Keys.lockGestures] = next.lockGestures
        }
    }

    suspend fun setFavorites(keys: List<String>) {
        store.edit { it[Keys.favorites] = keys.joinToString("\n") }
    }

    suspend fun toggleHidden(key: String) {
        store.edit { p ->
            val set = p[Keys.hidden].orEmpty().split('\n').filter { it.isNotBlank() }.toMutableSet()
            if (!set.add(key)) set.remove(key)
            p[Keys.hidden] = set.joinToString("\n")
        }
    }

    /**
     * Sets or clears the alias for one app.
     *
     * A blank [alias] removes the entry outright — restoring the app's real
     * label is one visible undo, so it should not require a hidden button.
     */
    suspend fun setAlias(key: String, alias: String) {
        store.edit { p ->
            val map = p[Keys.aliases].orEmpty().split('\n').mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) return@mapNotNull null
                line.substring(0, i) to line.substring(i + 1)
            }.toMutableList()
            val idx = map.indexOfFirst { it.first == key }
            val cleanAlias = alias.trim().replace('\n', ' ')
            if (cleanAlias.isBlank()) {
                if (idx >= 0) map.removeAt(idx)
            } else {
                if (idx >= 0) map[idx] = key to cleanAlias else map.add(key to cleanAlias)
            }
            p[Keys.aliases] = map.joinToString("\n") { "${it.first}=${it.second}" }
        }
    }

    suspend fun recordLaunch(key: String) {
        store.edit { p ->
            val map = p[Keys.usage].orEmpty().split('\n').mapNotNull { line ->
                val i = line.lastIndexOf('=')
                if (i <= 0) return@mapNotNull null
                val n = line.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
                line.substring(0, i) to n
            }.toMutableList()
            val idx = map.indexOfFirst { it.first == key }
            if (idx >= 0) map[idx] = key to (map[idx].second + 1) else map.add(key to 1)
            // Keep the table bounded; the tail contributes nothing to ranking.
            val trimmed = map.sortedByDescending { it.second }.take(200)
            p[Keys.usage] = trimmed.joinToString("\n") { "${it.first}=${it.second}" }

            // And the same launch, weighted by when it happened. See [Habit].
            val now = System.currentTimeMillis()
            val habits = Habit.parse(p[Keys.habits]).toMutableList()
            val at = habits.indexOfFirst { it.key == key }
            val updated = if (at >= 0) habits[at].used(now) else Habit(key, 1.0, now)
            if (at >= 0) habits[at] = updated else habits.add(updated)
            p[Keys.habits] = Habit.write(
                habits.sortedByDescending { it.scoreAt(now) }.take(HABIT_TABLE_LIMIT)
            )
        }
    }

    /**
     * How much each app is used, as a number that fades.
     *
     * A plain count answers "what has this person opened most since they
     * installed the launcher", which is not the question a home screen is
     * asking. Someone who lived in one app for a month and has not touched it
     * since would keep it at the top forever, and the app they actually reach
     * for every morning would never catch up.
     *
     * So a launch is worth 1 at the moment it happens and half of that
     * [HABIT_HALF_LIFE_DAYS] days later, halving again every span after. The
     * score is not swept or recomputed on a timer — each entry carries the day
     * it was last touched, and the decay is applied when it is read, which is
     * both exact and free.
     *
     * The effect is a list that keeps up: three weeks of a new habit overtakes
     * a year of an old one, and dropping an app quietly retires it.
     */
    data class Habit(val key: String, val weight: Double, val lastUsedAt: Long) {

        /** What this is worth now, with the decay since [lastUsedAt] applied. */
        fun scoreAt(now: Long): Double {
            val days = (now - lastUsedAt).coerceAtLeast(0L).toDouble() / DAY_MS
            return weight * Math.pow(0.5, days / HABIT_HALF_LIFE_DAYS)
        }

        /** This entry after one more launch at [now]. */
        fun used(now: Long): Habit = Habit(key, scoreAt(now) + 1.0, now)

        companion object {
            fun parse(raw: String?): List<Habit> = raw.orEmpty().split('\n').mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                // `key=weight:lastUsedAt`; the key may itself contain '=', so
                // the split is from the right, same as the usage table.
                val eq = line.lastIndexOf('=')
                if (eq <= 0) return@mapNotNull null
                val colon = line.indexOf(':', eq)
                if (colon < 0) return@mapNotNull null
                val weight = line.substring(eq + 1, colon).toDoubleOrNull() ?: return@mapNotNull null
                val at = line.substring(colon + 1).toLongOrNull() ?: return@mapNotNull null
                Habit(line.substring(0, eq), weight, at)
            }

            fun write(habits: List<Habit>): String =
                habits.joinToString("\n") { "${it.key}=${it.weight}:${it.lastUsedAt}" }

            private const val DAY_MS = 24 * 60 * 60 * 1000.0
        }
    }

    /**
     * The apps this person actually uses, most first.
     *
     * Ranked at read time so the order is right the moment it is looked at,
     * without anything having to run in between.
     */
    val habits: Flow<List<String>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val now = System.currentTimeMillis()
            Habit.parse(prefs[Keys.habits])
                .sortedByDescending { it.scoreAt(now) }
                .map { it.key }
        }

    /**
     * The apps this person reached for most recently, newest first.
     *
     * Same table [habits] reads — every launch stamps [Habit.lastUsedAt] as a
     * side effect of the ranking — so this costs no extra writes on the launch
     * path and no extra storage. The strip that used to say "most used" reads
     * this instead, because "what did I just open" answers what a home-screen
     * strip is actually being asked (bring the last app back) more directly
     * than "what have I opened most this week" does.
     */
    val recentlyLaunched: Flow<List<String>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            Habit.parse(prefs[Keys.habits])
                .sortedByDescending { it.lastUsedAt }
                .map { it.key }
        }
}

/**
 * How long it takes for a launch to count for half of what it did.
 *
 * Two weeks. Short enough that a habit picked up this month is at the top of
 * the list by the end of it; long enough that a quiet week does not clear out
 * everything the person normally uses.
 */
private const val HABIT_HALF_LIFE_DAYS = 14.0

/** A ceiling on the table, same reasoning as the usage counts it sits beside. */
private const val HABIT_TABLE_LIMIT = 60

private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
    this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
