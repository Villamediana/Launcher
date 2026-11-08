package com.aura.launcher.data

import com.aura.launcher.ui.theme.ThemeMode

/**
 * How the home screen is laid out. DAY (Home 3, "Dia em linha"): a compact
 * clock over the day as a line — now, what is coming, reminders, tomorrow —
 * with the apps a swipe away. CLASSIC: the clock and the favourites.
 */
enum class HomeStyle { DAY, CLASSIC }

/**
 * The languages the interface is translated into.
 *
 * A closed list rather than "whatever the system is set to", because a launcher
 * that follows the system into a language it has no strings for shows half an
 * interface in English and calls it localisation. These three are the ones that
 * are actually written.
 */
enum class AppLanguage { ENGLISH, PORTUGUESE, SPANISH }

/**
 * What a new install does *not* show: everything but the basics.
 *
 * A first home screen is the clock, the date, the agenda, the weather and the
 * battery — plus the to-do card, which draws nothing until there is something
 * on it. The rest (timer, music, notifications, map, "now", sky) is one switch
 * away in Settings › Widgets. A phone that already stored its own choice keeps
 * it: this is only the default for a key that was never written.
 */
val DEFAULT_HIDDEN_WIDGETS: Set<String> = HomeWidget.entries
    .filterNot {
        it in setOf(
            HomeWidget.CLOCK, HomeWidget.DATE, HomeWidget.CALENDAR,
            HomeWidget.WEATHER, HomeWidget.BATTERY, HomeWidget.TODO,
        )
    }
    .map { it.id }
    .toSet()

/**
 * The look of the clock on the home screen.
 *
 * Each one is meant to be a different object, not the same digits at a
 * different weight — that was the first cut of this list and every face on it
 * looked like the one above. PAPER is the historical default, a big light HH:mm
 * typograph. BOLD stacks the hour over the minute against a rule, like a
 * poster. RING drops the digits almost entirely and fills two arcs. MATRIX
 * spells the time on a dot grid. TYPE rules a monospace line above and below,
 * typewriter fashion. ANALOG draws a face and hands. MINIMAL is the quiet one:
 * a small time for a wallpaper you want to see behind it.
 *
 * Persisted by name, so the order here is only the order of the picker.
 */
enum class ClockStyle { PAPER, BOLD, RING, MATRIX, TYPE, ANALOG, MINIMAL, EDITORIAL }

/**
 * What colour the ink runs in when a wallpaper is set.
 *
 * FOLLOW obeys the current theme — same as before wallpapers existed.
 * LIGHT and DARK force the readable variant against whichever photograph
 * is behind, without changing the theme the user picked for panels and
 * cards: a light theme with a dark wallpaper still keeps the light
 * drawer, it just prints the clock in white. Reverts to FOLLOW the
 * moment the wallpaper is cleared, so an old choice does not leak into
 * the pure paper look.
 */
enum class WallpaperInk { FOLLOW, LIGHT, DARK }

/**
 * The animated backdrops the launcher can draw when no photograph is set.
 *
 * These are not videos and not GIFs. Each one is a small Compose scene painted
 * on the home surface — cheap enough to leave running all day, deliberate
 * enough to feel like something rather than a screensaver from someone else's
 * app. NONE means "paper", the historical background.
 *
 * A user-picked photograph in `wallpaperPath` still wins over any choice here:
 * this is what fills the space when no picture is set, so the phone never has
 * to sit on a flat swatch just because the person did not bring their own art.
 */
enum class AnimatedWallpaper {
    /** Flat paper — the launcher's historical background. */
    NONE,

    /**
     * A lake under a crescent moon: the moon crosses on a slow arc, thin cloud
     * drifts in front of it and dims it, and the light it lays on the water
     * dims with it.
     */
    NIGHT_SKY,

    /**
     * A star chart drawing itself: Cassiopeia, Orion and the Plough take turns
     * tracing star to star, hold, and let go.
     */
    CONSTELLATION,
}

/**
 * Everything the launcher lets the person choose, read from one DataStore file.
 */
@kotlinx.serialization.Serializable
data class Settings(
    val onboarded: Boolean = false,
    /**
     * How far through setup the user got.
     *
     * Persisted rather than held in memory because the home-role step ends by
     * Android relaunching Aura *as the home app* — a fresh task, with
     * `stateNotNeeded` and `clearTaskOnLaunch` on the Activity, so nothing in
     * memory or in saved instance state survives. Without this the user is
     * dropped back at step one the moment the step succeeds.
     */
    val onboardingStep: Int = 0,
    /**
     * The language the whole interface is written in.
     *
     * Chosen on the first onboarding step and changeable in Settings. Null
     * until then, which is what lets the onboarding step open on the phone's
     * own language as a suggestion rather than committing to it — a stored
     * value means the user has actually answered.
     */
    val language: AppLanguage? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,

    /** The home's layout. See [HomeStyle]. */
    val homeStyle: HomeStyle = HomeStyle.DAY,

    /** The home's sky follows the weather: rain, storms, snow, fog, sun, night. */
    val liveWeather: Boolean = false,

    /** The favourites under the day's line, as on the classic home. */
    val dayFavorites: Boolean = true,

    val hapticsEnabled: Boolean = true,
    /** Which of the clock designs the home screen renders. */
    val clockStyle: ClockStyle = ClockStyle.PAPER,
    /**
     * A wallpaper the user picked from their gallery.
     *
     * The image is copied into the app's private files/ on selection, so a
     * later revoke of media permission does not blank the home screen.
     * Empty means "use the paper texture" — the historical default.
     */
    val wallpaperPath: String = "",
    /**
     * How to colour the ink drawn on top of the wallpaper. Only used when
     * [wallpaperPath] is set; the field survives clearing the wallpaper
     * but its effect stops.
     */
    val wallpaperInk: WallpaperInk = WallpaperInk.FOLLOW,
    /**
     * How much black is laid over the wallpaper, 0..1.
     *
     * A photograph is chosen for being a photograph, not for being a backdrop,
     * and a bright one swallows the clock. Rather than asking the user to edit
     * the picture until the text reads, the launcher dims it: the same photo,
     * behind a veil they set with one finger. Zero by default, so a wallpaper
     * chosen before this existed keeps looking exactly as it did.
     */
    val wallpaperDim: Float = 0f,
    /**
     * A drawn, breathing backdrop for the home screen.
     *
     * Only used when [wallpaperPath] is empty — a photograph the user picked
     * still wins, always. The default is [AnimatedWallpaper.NONE], which is
     * exactly the paper look Aura has always had, so an install that never
     * opens the setting looks the same as before.
     */
    val animatedWallpaper: AnimatedWallpaper = AnimatedWallpaper.NONE,
    /**
     * Widgets the user has taken off the home screen, by [HomeWidget.id].
     *
     * Stored as the exception rather than the selection so that a widget which
     * did not exist when the set was written is shown, not hidden: a phone that
     * updates gets the new card, and the only ids in here are ones somebody
     * actually turned off.
     */
    val hiddenWidgets: Set<String> = DEFAULT_HIDDEN_WIDGETS,
    /**
     * The order of the home screen, and what shares a place.
     *
     * One slot per line, widget ids separated by commas — see [HomeLayout].
     * Empty means the order the launcher ships with, which is what every
     * install starts from and what stays until somebody drags something.
     */
    val homeLayout: String = "",
    /**
     * Whether the lock screen carries a strip to draw gestures from.
     *
     * A switch rather than a silent capability: what it allows is a layer in
     * front of the keyguard, and somebody who would rather not have one should
     * be able to say so in one place. Nothing runs from it without the
     * system's own unlock either way.
     */
    val lockGestures: Boolean = false,
) {
    // ------------------------------------------------------ home composition --
    //
    // One question, asked once: is this widget on the home screen? Each reader
    // below is the same lookup against [hiddenWidgets], which keeps the call
    // sites reading the way they always have — `settings.showClock` — while the
    // answer now comes from the Widgets page instead of from this file.
    //
    // Every one of them still needs its permission, and permissions are asked
    // for where permissions belong — the Permissions section under System. A
    // card whose grant is missing simply does not draw; it never lies about
    // being on.

    /** Whether [widget] is currently on the home screen. */
    fun shows(widget: HomeWidget): Boolean = widget.id !in hiddenWidgets

    /** The big clock at the top of the home screen. */
    val showClock: Boolean get() = shows(HomeWidget.CLOCK)

    /** The date under it. */
    val showDate: Boolean get() = shows(HomeWidget.DATE)

    /**
     * Hide Android's own status bar on the home screen.
     *
     * The clock, the battery and the weather are already here, drawn the way
     * the rest of the launcher is drawn — the system strip on top of that is
     * the same information twice in two different type styles. Swiping from the
     * top still brings it back, so nothing is actually lost.
     */
    val hideStatusBar: Boolean = true

    /** Temperature and conditions under the clock. Needs coarse location. */
    val showWeather: Boolean get() = shows(HomeWidget.WEATHER)

    /** Battery level beside the date. */
    val showBattery: Boolean get() = shows(HomeWidget.BATTERY)

    /** The notification list on the home screen. Needs notification access. */
    val showNotifications: Boolean get() = shows(HomeWidget.NOTIFICATIONS)

    /** The stopwatch and countdown card. */
    val showTimer: Boolean get() = shows(HomeWidget.TIMER)

    /** The to-do card: what is owed today, one tap to tick it off. */
    val showTodo: Boolean get() = shows(HomeWidget.TODO)

    /**
     * A card for whatever is playing — music or video — under the clock.
     *
     * Costs no permission of its own: reading Android's media sessions is gated
     * on the same notification-listener grant the notification list already
     * needs, so this appears for anyone who has given that and stays quietly
     * absent for everyone else.
     */
    val showNowPlaying: Boolean get() = shows(HomeWidget.NOW_PLAYING)

    /** The next calendar events under the clock. Needs READ_CALENDAR. */
    val showCalendar: Boolean get() = shows(HomeWidget.CALENDAR)

    /** How many upcoming events the home screen lists. */
    val calendarEventCount: Int = 2

    /** Whether long-pressing an app offers the app's own shortcuts. */
    val appShortcutsEnabled: Boolean = true

    /** How many of them are shown. */
    val appShortcutCount: Int = 2

}
