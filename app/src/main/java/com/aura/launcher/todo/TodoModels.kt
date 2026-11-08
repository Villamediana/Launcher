package com.aura.launcher.todo

import kotlinx.serialization.Serializable

/**
 * The data behind the to-do widget.
 *
 * One file, one JSON document — the bargain the other stores here
 * makes. A list of things to do produces a handful of rows a day, and a database
 * here would buy nothing but migrations.
 *
 * It replaced the medicine card. A medicine is one kind of thing to do on a
 * schedule; so is paying the rent on the 5th, watering the plants every three
 * days, or taking the bins out on Tuesdays. The old `care.json` is read once
 * and turned into recurring to-dos — see [TodoStore.migrateCare].
 */
@Serializable
data class TodoData(
    val todos: List<Todo> = emptyList(),
    val done: List<TodoDone> = emptyList(),
)

/** How a to-do comes round again. */
@Serializable
enum class TodoRepeat {
    /** Once. Done is done. */
    NONE,

    /** Every [Todo.every] hours from the first time — the medicine shape. */
    HOURS,

    /** Every [Todo.every] days. */
    DAILY,

    /** On [Todo.days] of the week, every [Todo.every] weeks. */
    WEEKLY,

    /** On the start day's date, every [Todo.every] months. */
    MONTHLY,
}

@Serializable
data class Todo(
    val id: String,
    val title: String,
    val note: String = "",
    /** Local day of the first occurrence, `yyyy-MM-dd`. */
    val startDay: String,
    /** Minutes past midnight, or [NO_TIME] for something due "today" at no particular hour. */
    val minuteOfDay: Int = NO_TIME,
    val repeat: TodoRepeat = TodoRepeat.NONE,
    /** The interval [repeat] counts in. Always at least 1. */
    val every: Int = 1,
    /** ISO days of the week (1 = Monday … 7 = Sunday) for [TodoRepeat.WEEKLY]. */
    val days: List<Int> = emptyList(),
    /** Last local day it comes round, or null for "until I stop it". */
    val endDay: String? = null,
    /** A notification at the time. Only meaningful when there is a time. */
    val remind: Boolean = true,
    /** Paused: still listed, never due. */
    val archived: Boolean = false,
    val createdAt: Long = 0L,
) {
    val timed: Boolean get() = minuteOfDay != NO_TIME

    companion object {
        const val NO_TIME = -1
    }
}

/** One occurrence ticked off, pinned to the slot it belongs to. */
@Serializable
data class TodoDone(
    val todoId: String,
    val slotAt: Long,
    val doneAt: Long,
)
