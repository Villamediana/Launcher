package com.aura.launcher.data

/**
 * The pieces the home screen is made of, one entry per thing the user can see
 * or stop seeing.
 *
 * Everything here used to be a fixed property of [Settings] — the launcher drew
 * its clock, its strip and its notification list, and that was that. The reason
 * to make them addressable again is not that the defaults were wrong: it is
 * that the home screen now carries cards that are personal rather than
 * universal (a timer, a medicine round), and a personal card only makes
 * sense on a screen where the person decides what is on it.
 *
 * The id is the stored identity and never changes; the enum name and the order
 * here are free to. Hiding is stored as the exception — a set of hidden ids —
 * so a widget added in a later version appears for everyone who already has
 * Aura installed instead of staying invisible until they go and find it.
 */
enum class HomeWidget(
    val id: String,
) {
    CLOCK("clock"),
    DATE("date"),
    BATTERY("battery"),
    WEATHER("weather"),
    TIMER("timer"),
    TODO("todo"),
    NOW_PLAYING("now_playing"),
    CALENDAR("calendar"),
    NOTIFICATIONS("notifications"),
    MAP("map"),
    NOW("now"),
    SKY("sky"),
    ;

    companion object {
        fun byId(id: String): HomeWidget? = entries.firstOrNull { it.id == id }
    }
}
