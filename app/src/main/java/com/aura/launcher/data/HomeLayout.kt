package com.aura.launcher.data

/**
 * The order the widgets sit in down the home screen.
 *
 * A list, and nothing more. Widgets could be stacked here for a version — drop
 * one on another and swipe between them — and it was the wrong feature: it
 * asked a home screen to hide things from the person looking at it, and every
 * question about it ("which card is this? where did the other one go? why did
 * it come apart?") was one the plain column never raises. A layout stored with
 * stacks in it reads back as the widgets in the order they appear, one per
 * position, so anybody who made one simply finds it undone.
 *
 * Stored as text, one id per line. Unreadable or unknown ids are dropped on
 * the way in rather than refused, because the file outlives the version that
 * wrote it — a widget removed in a later release must not take the rest of
 * somebody's order with it.
 */
object HomeLayout {

    /**
     * The widgets that have a place of their own.
     *
     * The date, the battery and the weather are absent on purpose: they are
     * the strip under the clock, not blocks. Moving the battery away from the
     * date would be rearranging three words in one sentence.
     */
    val MOVABLE: List<HomeWidget> = listOf(
        HomeWidget.CLOCK,
        HomeWidget.NOW,
        HomeWidget.TIMER,
        HomeWidget.TODO,
        HomeWidget.NOW_PLAYING,
        HomeWidget.CALENDAR,
        HomeWidget.NOTIFICATIONS,
        HomeWidget.MAP,
        HomeWidget.SKY,
    )

    /**
     * The stored order, resolved against what is actually on screen.
     *
     * Everything visible appears exactly once: ids that are hidden or unknown
     * are dropped, and a widget the stored order never heard of — one added by
     * an update — takes its natural position rather than being left out.
     *
     * Commas were the stack separator. They are read as plain separators now,
     * which is the whole of the migration: what was a stack of three becomes
     * three positions, in the order they were stacked.
     */
    fun resolve(stored: String, isVisible: (HomeWidget) -> Boolean): List<HomeWidget> {
        val placed = LinkedHashSet<HomeWidget>()
        val order = stored.split('\n', ',')
            .mapNotNull { HomeWidget.byId(it.trim()) }
            .filter { it in MOVABLE && isVisible(it) && placed.add(it) }
            .toMutableList()

        for (widget in MOVABLE) {
            if (!isVisible(widget) || widget in placed) continue
            order.add(MOVABLE.indexOf(widget).coerceIn(0, order.size), widget)
            placed.add(widget)
        }
        return order
    }

    fun encode(order: List<HomeWidget>): String = order.joinToString("\n") { it.id }

    /** [order] with [widget] moved to position [to]. */
    fun move(order: List<HomeWidget>, widget: HomeWidget, to: Int): List<HomeWidget> {
        val from = order.indexOf(widget)
        if (from < 0) return order
        val target = to.coerceIn(0, order.size - 1)
        if (from == target) return order
        val next = order.toMutableList()
        next.removeAt(from)
        next.add(target, widget)
        return next
    }
}
