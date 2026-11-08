package com.aura.launcher.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.todo.TodoMath
import com.aura.launcher.todo.TodoRepeat
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * What is owed today, on the home screen.
 *
 * One line per to-do: a ring to tick, the title, and the hour when it has one.
 * Late ones first, in the danger colour; the next one in accent. A tap ticks
 * (and a second tap unticks, for the mis-tap); holding a line opens it.
 *
 * Nothing registered, nothing drawn — a heading over "nothing to do" is a
 * widget asking to be set up, and the place to set it up is Settings › Widgets
 * or simply asking Aura.
 */
@Composable
fun TodoCard(app: AuraApp, modifier: Modifier = Modifier) {
    val colors = Aura.colors
    val scope = rememberCoroutineScope()
    val data by app.todos.data.collectAsState()
    val now = rememberCareNow()
    val rows = remember(data, now) { TodoMath.rows(data, now) }
    var editing by remember { mutableStateOf<String?>(null) }

    if (data.todos.isEmpty() || rows.isEmpty()) return

    val soonest = rows.firstOrNull { !it.done && !it.late && it.todo.timed }?.at
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 700f)),
    ) {
        rows.take(MAX_ROWS).forEach { row ->
            key(row.todo.id, row.at) {
                TodoLine(
                    row = row,
                    now = now,
                    imminent = soonest != null && row.at == soonest,
                    onToggle = { scope.launch { app.todos.setDone(row.todo.id, row.at, !row.done) } },
                    onOpen = { editing = row.todo.id },
                )
            }
        }
        val hidden = rows.size - MAX_ROWS
        if (hidden > 0) {
            Text(
                text = "+$hidden",
                fontSize = 12.sp,
                color = colors.inkFaint,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    editing?.let { id ->
        TodoSheet(app = app, onDismiss = { editing = null }, openId = id)
    }
}

@Composable
private fun TodoLine(
    row: TodoMath.Row,
    now: Long,
    imminent: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = Aura.colors
    val haptics = LocalHapticFeedback.current
    val check by animateFloatAsState(
        targetValue = if (row.done) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "todoCheck",
    )
    val whenText = TodoFormat.slot(row.todo.timed, row.at, now)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(CircleShape)
            .pointerInput(row.todo.id, row.at, row.done) {
                detectTapGestures(
                    onTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggle()
                    },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpen()
                    },
                )
            }
            .semantics {
                contentDescription = listOfNotNull(row.todo.title, whenText).joinToString(", ")
                role = Role.Checkbox
                toggleableState = ToggleableState(row.done)
            },
    ) {
        TodoMark(check = check, late = row.late && !row.done, dim = !imminent && !row.late && !row.done)
        Spacer(Modifier.width(10.dp))
        Text(
            text = row.todo.title,
            fontSize = 15.sp,
            color = when {
                row.done -> colors.inkFaint
                row.late || imminent -> colors.ink
                else -> colors.inkMuted
            },
            textDecoration = if (row.done) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (row.todo.repeat != TodoRepeat.NONE) {
            Spacer(Modifier.width(5.dp))
            Icon(
                imageVector = Icons.Rounded.Repeat,
                contentDescription = null,
                tint = colors.inkFaint.copy(alpha = 0.7f),
                modifier = Modifier.size(12.dp),
            )
        }
        if (whenText != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = whenText,
                fontSize = 15.sp,
                fontWeight = if (imminent || row.late) FontWeight.Medium else FontWeight.Normal,
                color = when {
                    row.done -> colors.inkFaint
                    row.late -> colors.danger
                    imminent -> colors.accent
                    else -> colors.inkMuted.copy(alpha = 0.7f)
                },
            )
        }
    }
}

/** The ring that fills when a to-do is ticked. */
@Composable
internal fun TodoMark(check: Float, late: Boolean, dim: Boolean = false) {
    val colors = Aura.colors
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(
                    width = 1.4.dp,
                    color = (if (late) colors.danger else colors.inkFaint)
                        .copy(alpha = (if (dim) 0.4f else 0.65f) * (1f - check)),
                    shape = CircleShape,
                ),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = check
                    scaleY = check
                    alpha = check
                }
                .size(20.dp)
                .clip(CircleShape)
                .background(colors.sage),
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.paper,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** Wording shared by the card and the sheet. */
internal object TodoFormat {

    fun time(at: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

    fun date(at: Long): String = SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(at))

    /** "08:00", "amanhã 08:00", "ontem", "12/10" — the day only when it is not today. */
    @Composable
    fun slot(timed: Boolean, at: Long, now: Long): String? {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val prefix = when (day) {
            today -> null
            today.plusDays(1) -> stringResource(R.string.todo_tomorrow)
            today.minusDays(1) -> stringResource(R.string.todo_yesterday)
            else -> date(at)
        }
        return when {
            timed && prefix != null -> "$prefix ${time(at)}"
            timed -> time(at)
            else -> prefix
        }
    }
}

/** Six lines is as much of the home screen as a list should take. */
private const val MAX_ROWS = 6
