package com.aura.launcher.ui.home

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.todo.Todo
import com.aura.launcher.todo.TodoData
import com.aura.launcher.todo.TodoMath
import com.aura.launcher.todo.TodoRepeat
import com.aura.launcher.todo.TodoStore
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * Every to-do, and the form to make or change one.
 *
 * Opened from Settings › Widgets, or by holding a line on the home card (which
 * goes straight to that to-do's form).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TodoSheet(app: AuraApp, onDismiss: () -> Unit, openId: String? = null, openNew: Boolean = false) {
    val colors = Aura.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val data by app.todos.data.collectAsState()
    // null = the list; "" = a new to-do; anything else = that to-do's form.
    var screen by remember { mutableStateOf(openId ?: if (openNew) "" else null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.ink,
        scrimColor = Color.Black.copy(alpha = 0.32f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.92f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .navigationBarsPadding(),
        ) {
            when (val current = screen) {
                null -> TodoList(data = data, onOpen = { screen = it }, onNew = { screen = "" })
                else -> {
                    val existing = data.todos.firstOrNull { it.id == current }
                    TodoForm(
                        existing = existing,
                        onBack = { if (openId != null) onDismiss() else screen = null },
                        onSave = { todo ->
                            app.scope.launch { app.todos.upsert(todo) }
                            if (openId != null) onDismiss() else screen = null
                        },
                        onDelete = existing?.let {
                            {
                                app.scope.launch { app.todos.remove(it.id) }
                                if (openId != null) onDismiss() else screen = null
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun TodoList(data: TodoData, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val colors = Aura.colors
    val now = System.currentTimeMillis()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.todo_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(colors.accent)
                .clickable(onClick = onNew),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, stringResource(R.string.todo_new), tint = colors.paper, modifier = Modifier.size(19.dp))
        }
    }
    Spacer(Modifier.height(16.dp))
    if (data.todos.isEmpty()) {
        Text(
            text = stringResource(R.string.todo_none),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkFaint,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        return
    }
    data.todos
        .sortedWith(compareBy({ it.archived }, { TodoMath.next(data, it, now) ?: Long.MAX_VALUE }))
        .forEach { todo ->
            val next = TodoMath.next(data, todo, now)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.paperDeep)
                    .clickable { onOpen(todo.id) }
                    .padding(14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = todo.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (todo.archived || next == null) colors.inkFaint else colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = when {
                            todo.archived -> stringResource(R.string.todo_paused)
                            next == null -> stringResource(R.string.todo_all_done)
                            else -> listOfNotNull(
                                TodoFormat.slot(todo.timed, next, now) ?: stringResource(R.string.todo_today),
                                repeatLabel(todo),
                            ).joinToString(" · ")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkFaint,
                    )
                }
                if (todo.repeat != TodoRepeat.NONE) {
                    Icon(Icons.Rounded.Repeat, null, tint = colors.inkFaint, modifier = Modifier.size(16.dp))
                }
            }
        }
}

/** "Todo dia", "A cada 8 h", "Seg, Qua" — or null for a one-off. */
@Composable
internal fun repeatLabel(todo: Todo): String? {
    val n = todo.every.coerceAtLeast(1)
    return when (todo.repeat) {
        TodoRepeat.NONE -> null
        TodoRepeat.HOURS -> stringResource(R.string.todo_every_hours, n)
        TodoRepeat.DAILY -> if (n == 1) stringResource(R.string.todo_daily) else stringResource(R.string.todo_every_days, n)
        TodoRepeat.WEEKLY -> {
            val names = todo.days.sorted().joinToString(", ") { weekdayShort(it) }
            val base = if (n == 1) stringResource(R.string.todo_weekly) else stringResource(R.string.todo_every_weeks, n)
            if (names.isBlank()) base else "$base · $names"
        }
        TodoRepeat.MONTHLY -> if (n == 1) stringResource(R.string.todo_monthly) else stringResource(R.string.todo_every_months, n)
    }
}

private fun weekdayShort(iso: Int): String =
    DayOfWeek.of(iso).getDisplayName(JavaTextStyle.SHORT, Locale.getDefault()).trimEnd('.')
        .replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodoForm(
    existing: Todo?,
    onBack: () -> Unit,
    onSave: (Todo) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = Aura.colors
    val context = LocalContext.current
    val today = LocalDate.now()

    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var start by remember { mutableStateOf(existing?.startDay?.let { TodoMath.date(it) } ?: today) }
    var minute by remember { mutableIntStateOf(existing?.minuteOfDay ?: Todo.NO_TIME) }
    var repeat by remember { mutableStateOf(existing?.repeat ?: TodoRepeat.NONE) }
    var every by remember { mutableIntStateOf(existing?.every ?: 1) }
    var days by remember { mutableStateOf(existing?.days?.toSet() ?: emptySet()) }
    var end by remember { mutableStateOf(existing?.endDay?.let { TodoMath.date(it) }) }
    var remind by remember { mutableStateOf(existing?.remind ?: true) }
    var paused by remember { mutableStateOf(existing?.archived ?: false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = colors.inkMuted)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(if (existing == null) R.string.todo_new else R.string.todo_edit),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(16.dp))

    Field(value = title, placeholder = stringResource(R.string.todo_field_title), big = true) { title = it }
    Spacer(Modifier.height(10.dp))
    Field(value = note, placeholder = stringResource(R.string.todo_field_note)) { note = it }

    Label(stringResource(R.string.todo_field_when))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(stringResource(R.string.todo_today), start == today) { start = today }
        Chip(stringResource(R.string.todo_tomorrow), start == today.plusDays(1)) { start = today.plusDays(1) }
        val other = start != today && start != today.plusDays(1)
        Chip(if (other) TodoFormat.date(TodoMath.startOfDay(TodoMath.format(start))) else stringResource(R.string.todo_pick_date), other) {
            DatePickerDialog(context, { _, y, m, d -> start = LocalDate.of(y, m + 1, d) }, start.year, start.monthValue - 1, start.dayOfMonth).show()
        }
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(stringResource(R.string.todo_no_time), minute == Todo.NO_TIME) { minute = Todo.NO_TIME }
        Chip(
            if (minute == Todo.NO_TIME) stringResource(R.string.todo_pick_time)
            else "%02d:%02d".format(minute / 60, minute % 60),
            minute != Todo.NO_TIME,
        ) {
            val m = if (minute == Todo.NO_TIME) 9 * 60 else minute
            TimePickerDialog(context, { _, h, mm -> minute = h * 60 + mm }, m / 60, m % 60, DateFormat.is24HourFormat(context)).show()
        }
    }

    Label(stringResource(R.string.todo_field_repeat))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(stringResource(R.string.todo_repeat_none), repeat == TodoRepeat.NONE) { repeat = TodoRepeat.NONE }
        Chip(stringResource(R.string.todo_repeat_hours), repeat == TodoRepeat.HOURS) {
            repeat = TodoRepeat.HOURS
            if (every < 2) every = 8
            if (minute == Todo.NO_TIME) minute = 8 * 60
        }
        Chip(stringResource(R.string.todo_daily), repeat == TodoRepeat.DAILY) { repeat = TodoRepeat.DAILY; every = 1 }
        Chip(stringResource(R.string.todo_weekly), repeat == TodoRepeat.WEEKLY) {
            repeat = TodoRepeat.WEEKLY
            every = 1
            if (days.isEmpty()) days = setOf(start.dayOfWeek.value)
        }
        Chip(stringResource(R.string.todo_monthly), repeat == TodoRepeat.MONTHLY) { repeat = TodoRepeat.MONTHLY; every = 1 }
    }
    if (repeat != TodoRepeat.NONE) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.todo_every), color = colors.inkMuted, fontSize = 15.sp)
            Spacer(Modifier.width(10.dp))
            Stepper(every, if (repeat == TodoRepeat.HOURS) 72 else 30) { every = it }
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(
                    when (repeat) {
                        TodoRepeat.HOURS -> R.string.todo_unit_hours
                        TodoRepeat.DAILY -> R.string.todo_unit_days
                        TodoRepeat.WEEKLY -> R.string.todo_unit_weeks
                        else -> R.string.todo_unit_months
                    },
                ),
                color = colors.inkMuted,
                fontSize = 15.sp,
            )
        }
    }
    if (repeat == TodoRepeat.WEEKLY) {
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (d in 1..7) {
                Chip(weekdayShort(d), d in days) {
                    days = if (d in days && days.size > 1) days - d else days + d
                }
            }
        }
    }
    if (repeat != TodoRepeat.NONE) {
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.todo_forever), end == null) { end = null }
            Chip(
                end?.let { stringResource(R.string.todo_until, TodoFormat.date(TodoMath.startOfDay(TodoMath.format(it)))) }
                    ?: stringResource(R.string.todo_pick_end),
                end != null,
            ) {
                val e = end ?: start.plusDays(7)
                DatePickerDialog(context, { _, y, m, d -> end = LocalDate.of(y, m + 1, d) }, e.year, e.monthValue - 1, e.dayOfMonth).show()
            }
        }
    }

    if (minute != Todo.NO_TIME) {
        Spacer(Modifier.height(14.dp))
        SwitchLine(stringResource(R.string.todo_remind), remind) { remind = it }
    }
    if (existing != null) {
        SwitchLine(stringResource(R.string.todo_pause), paused) { paused = it }
    }

    Spacer(Modifier.height(22.dp))
    val canSave = title.isNotBlank()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (canSave) colors.accent else colors.paperDeep)
            .clickable(enabled = canSave) {
                onSave(
                    Todo(
                        id = existing?.id ?: TodoStore.newId(),
                        title = title.trim(),
                        note = note.trim(),
                        startDay = TodoMath.format(start),
                        minuteOfDay = minute,
                        repeat = repeat,
                        every = every.coerceAtLeast(1),
                        days = if (repeat == TodoRepeat.WEEKLY) days.sorted() else emptyList(),
                        endDay = if (repeat == TodoRepeat.NONE) null else end?.let { TodoMath.format(it) },
                        remind = remind,
                        archived = paused,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    ),
                )
            },
    ) {
        Text(
            stringResource(R.string.todo_save),
            color = if (canSave) colors.paper else colors.inkFaint,
            fontWeight = FontWeight.SemiBold,
        )
    }
    if (onDelete != null) {
        Spacer(Modifier.height(10.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .clickable(onClick = onDelete),
        ) {
            Text(stringResource(R.string.todo_delete), color = colors.danger)
        }
    }
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text, style = MaterialTheme.typography.labelMedium, color = Aura.colors.inkFaint)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Field(value: String, placeholder: String, big: Boolean = false, onChange: (String) -> Unit) {
    val colors = Aura.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.paperDeep)
            .padding(horizontal = 16.dp, vertical = if (big) 15.dp else 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = colors.inkFaint, fontSize = if (big) 18.sp else 15.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = !big,
            textStyle = TextStyle(color = colors.ink, fontSize = if (big) 18.sp else 15.sp),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Aura.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.14f) else colors.paperDeep)
            .border(1.dp, if (selected) colors.accent.copy(alpha = 0.45f) else colors.hairline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            fontSize = 14.sp,
            color = if (selected) colors.accent else colors.inkMuted,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun Stepper(value: Int, max: Int, onChange: (Int) -> Unit) {
    val colors = Aura.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(colors.paperDeep),
    ) {
        Box(Modifier.size(36.dp).clickable { onChange((value - 1).coerceAtLeast(1)) }, contentAlignment = Alignment.Center) {
            Text("−", color = colors.inkMuted, fontSize = 18.sp)
        }
        Text("$value", color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Box(Modifier.size(36.dp).clickable { onChange((value + 1).coerceAtMost(max)) }, contentAlignment = Alignment.Center) {
            Text("+", color = colors.inkMuted, fontSize = 18.sp)
        }
    }
}

@Composable
private fun SwitchLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = Aura.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
    ) {
        Text(text, color = colors.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.paper),
        )
    }
}
