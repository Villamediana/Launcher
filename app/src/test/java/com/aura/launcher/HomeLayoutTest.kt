package com.aura.launcher

import com.aura.launcher.data.HomeLayout
import com.aura.launcher.data.HomeWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home screen's order, which the user owns and the file outlives.
 *
 * Three things have to hold across a version. A widget that did not exist when
 * the order was written must appear anyway — otherwise shipping one means
 * shipping it invisible to everybody who has ever dragged something. A widget
 * that no longer exists, or is switched off, must drop out without taking the
 * rest of the arrangement with it. And a layout written by the version that
 * had stacks must read back as a plain column, which is what the commas in it
 * now mean.
 */
class HomeLayoutTest {

    private val all: (HomeWidget) -> Boolean = { true }

    @Test
    fun `an empty layout is the order the launcher ships with`() {
        assertEquals(HomeLayout.MOVABLE, HomeLayout.resolve("", all))
    }

    @Test
    fun `a stored order is honoured`() {
        val order = HomeLayout.resolve("timer\nclock\nnotifications", all)

        // What the file said keeps its relative order; what it did not
        // mention is slotted in at its natural position, which is how a widget
        // added by an update arrives somewhere sensible instead of at the end.
        assertTrue(order.indexOf(HomeWidget.TIMER) < order.indexOf(HomeWidget.CLOCK))
        assertTrue(order.indexOf(HomeWidget.CLOCK) < order.indexOf(HomeWidget.NOTIFICATIONS))
        assertEquals(order.distinct(), order)
        assertTrue(order.containsAll(HomeLayout.MOVABLE))
    }

    @Test
    fun `a stack written by the old version comes apart in place`() {
        val order = HomeLayout.resolve("timer\nclock,meds\nnotifications", all)

        // The two that shared a slot become two positions, in the order they
        // were stacked — nothing is lost and nothing is hidden.
        assertEquals(HomeWidget.TIMER, order[0])
        assertTrue(order.indexOf(HomeWidget.CLOCK) < order.indexOf(HomeWidget.TODO))
        assertEquals(order.distinct(), order)
    }

    @Test
    fun `an id from another version is dropped, not fatal`() {
        val order = HomeLayout.resolve("clock\nfridge\nnonsense\nmeds", all)

        assertEquals(HomeWidget.CLOCK, order[0])
        assertTrue(order.indexOf(HomeWidget.CLOCK) < order.indexOf(HomeWidget.TODO))
        assertEquals(order.distinct(), order)
        assertTrue(order.containsAll(HomeLayout.MOVABLE))
    }

    @Test
    fun `a hidden widget is left out without disturbing the rest`() {
        val order = HomeLayout.resolve("clock\ntimer\nmeds") { it != HomeWidget.TIMER }

        assertTrue(order.none { it == HomeWidget.TIMER })
        assertEquals(HomeWidget.CLOCK, order[0])
        assertTrue(order.indexOf(HomeWidget.CLOCK) < order.indexOf(HomeWidget.TODO))
    }

    @Test
    fun `moving a widget puts it where it was dropped`() {
        val order = listOf(HomeWidget.CLOCK, HomeWidget.TIMER, HomeWidget.TODO)

        assertEquals(
            listOf(HomeWidget.TIMER, HomeWidget.CLOCK, HomeWidget.TODO),
            HomeLayout.move(order, HomeWidget.TIMER, to = 0),
        )
        assertEquals(
            listOf(HomeWidget.TIMER, HomeWidget.TODO, HomeWidget.CLOCK),
            HomeLayout.move(order, HomeWidget.CLOCK, to = 2),
        )
    }

    @Test
    fun `dropping a widget where it already was changes nothing`() {
        val order = listOf(HomeWidget.CLOCK, HomeWidget.TIMER)
        assertEquals(order, HomeLayout.move(order, HomeWidget.TIMER, to = 1))
        assertEquals(order, HomeLayout.move(order, HomeWidget.NOW_PLAYING, to = 0))
    }

    @Test
    fun `what is encoded reads back as what was arranged`() {
        // Every movable widget, in an order nothing would have chosen: what
        // comes back must be exactly this, or the file is not the layout.
        val order = listOf(
            HomeWidget.TIMER,
            HomeWidget.CLOCK,
            HomeWidget.NOTIFICATIONS,
            HomeWidget.TODO,
            HomeWidget.NOW_PLAYING,
            HomeWidget.MAP,
            HomeWidget.SKY,
            HomeWidget.NOW,
            HomeWidget.CALENDAR,
        )
        assertEquals(HomeLayout.MOVABLE.size, order.size)
        assertEquals(order, HomeLayout.resolve(HomeLayout.encode(order), all))
    }
}
