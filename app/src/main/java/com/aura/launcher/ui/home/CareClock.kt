package com.aura.launcher.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aura.launcher.todo.TodoMath
import kotlinx.coroutines.delay

/**
 * The clock the care widgets read, and the only one they are allowed to wind.
 *
 * Each of the three used to keep its own: a feed line ticking, a medicine card
 * ticking, a nappy tally ticking, all on their own coroutines and all waking
 * up within milliseconds of each other to ask the same question. One tick
 * serves them now, aligned to the minute boundary so the phone wakes once for
 * the whole home screen rather than three times for three parts of it.
 *
 * It only runs while the home screen is actually in front of somebody. A
 * launcher's composition is never torn down — the user opens another app and
 * this Activity is merely stopped — so an ungated loop here would go on ticking
 * all day behind whatever they are really using, which is the most expensive
 * mistake this file could make.
 *
 * [fast] buys a tick a second instead of a minute, and is meant for one thing:
 * a feed timer that is actually running. A paused one does not need it, and
 * nothing else ever does.
 */
@Composable
internal fun rememberCareNow(fast: Boolean = false): Long {
    val owner = LocalLifecycleOwner.current
    val state = produceState(System.currentTimeMillis(), fast, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val at = System.currentTimeMillis()
                value = at
                val period = if (fast) 1_000L else TodoMath.MINUTE
                // Lands just after the boundary, so a minute tick arrives when
                // the clock in the corner changes rather than halfway through.
                delay(period - at % period + 30L)
            }
        }
    }
    return state.value
}
