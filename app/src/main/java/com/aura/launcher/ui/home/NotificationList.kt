package com.aura.launcher.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.launcher.notifications.NotificationHub
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Event
import com.aura.launcher.notifications.NotificationItem
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.aura.launcher.R

/**
 * The notification shade, on the home screen.
 *
 * Reads as cards rather than as a list: each one is its own surface, swipes
 * away sideways under the thumb, and taps through to whatever the shade would
 * have opened. Dismissing here dismisses everywhere — these are the real
 * notifications, not a copy of them.
 */
@Composable
fun NotificationList(
    modifier: Modifier = Modifier,
) {
    val colors = Aura.colors
    val items by NotificationHub.items.collectAsState()

    AnimatedVisibility(
        visible = items.isNotEmpty(),
        enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = 1400f)) + fadeIn(tween(160)),
        exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 1400f)) + fadeOut(tween(110)),
        modifier = modifier,
    ) {
        // Collapsed by default: the home screen shows only the badge, and the
        // cards themselves live one tap away. Fewer things on the surface, no
        // functionality lost — the whole shade is there when it is asked for.
        var expanded by remember { mutableStateOf(false) }

        // Everything the shade has, since the block below scrolls: capping
        // this cost the user notifications they had explicitly opened the
        // shade to look at.
        val shown = items

        // Pulling down opens the shade, pushing up folds it away — the same
        // direction the real shade moves, so it needs no explaining. Attached
        // to the outer Column so the gesture works whether the user starts on
        // the badge or on the cards.
        var pulled by remember { mutableFloatStateOf(0f) }
        val stackDrag = Modifier.draggable(
            orientation = Orientation.Vertical,
            state = rememberDraggableState { delta ->
                pulled += delta
                if (pulled > EXPAND_DISTANCE && !expanded) {
                    expanded = true
                    pulled = 0f
                } else if (pulled < -EXPAND_DISTANCE && expanded) {
                    expanded = false
                    pulled = 0f
                }
            },
            onDragStarted = { pulled = 0f },
            onDragStopped = { pulled = 0f },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(stackDrag)
                .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 900f)),
        ) {
            NotificationBadge(
                count = items.size,
                expanded = expanded,
                canClear = items.any { it.clearable },
                onToggle = { expanded = !expanded },
                onClearAll = { NotificationHub.dismissAll() },
            )

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 1100f)) + fadeIn(tween(140)),
                exit = shrinkVertically(spring(dampingRatio = 0.85f, stiffness = 1100f)) + fadeOut(tween(100)),
            ) {
                // Height is capped by [EXPANDED_MAX_HEIGHT] so a heavy shade
                // does not push the favourites off the home screen, and the
                // scroll inside kicks in the moment the cards would overflow
                // that ceiling. The list is walked as a plain Column rather
                // than a LazyColumn because the ceiling itself keeps the
                // count bounded — five to seven items visible at a time,
                // and nested-scrolling a LazyColumn inside another
                // scrollable parent is a fight not worth having.
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(max = EXPANDED_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (item in shown) {
                        key(item.key) { NotificationCard(item) }
                    }
                }
            }
        }
    }
}

/**
 * The one-line badge that stands in for the whole stack when it is folded.
 *
 * Minimal on purpose: a dot for the fact that something is there, a count of
 * how much, and a "clear all" that only appears when there is anything to
 * clear. Tapping anywhere on it toggles the shade open, so the target is the
 * whole row and not just an icon.
 */
@Composable
private fun NotificationBadge(
    count: Int,
    expanded: Boolean,
    canClear: Boolean,
    onToggle: () -> Unit,
    onClearAll: () -> Unit,
) {
    val colors = Aura.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
            // 14 + 6 + 10 puts the text at 30dp, which is where the calendar
            // pill and the notification cards start theirs. These three sit
            // directly under one another on the home screen, and at 8dp with a
            // fatter dot this one began four pixels to their left — close
            // enough to look like a mistake rather than a variation.
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.accent),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (count == 1) androidx.compose.ui.res.stringResource(com.aura.launcher.R.string.notif_count_one)
            else androidx.compose.ui.res.stringResource(com.aura.launcher.R.string.notif_count_many, count),
            style = MaterialTheme.typography.labelMedium,
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
        if (canClear && expanded) {
            Icon(
                imageVector = Icons.Rounded.ClearAll,
                contentDescription = androidx.compose.ui.res.stringResource(com.aura.launcher.R.string.notif_clear_all_cd),
                tint = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onClearAll)
                    .padding(6.dp)
                    .size(18.dp),
            )
        }
    }
}

/** How far the stack has to be pulled before it commits to opening or folding. */
private const val EXPAND_DISTANCE = 48f

/**
 * The tallest the expanded shade is allowed to be. Past this it scrolls
 * inside; the favourites and the clock never get pushed off the screen by a
 * chatty day.
 */
private val EXPANDED_MAX_HEIGHT = 450.dp

/**
 * One card, swipeable.
 *
 * The offset is plain state while a finger is down and an [Animatable] once it
 * lifts — the same split the app drawer uses, and for the same reason: a card
 * that chases the thumb through a coroutine never feels attached to it.
 */
@Composable
private fun NotificationCard(item: NotificationItem) {
    val colors = Aura.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val density = LocalDensity.current

    val threshold = remember(density) { with(density) { DISMISS_DISTANCE.toPx() } }
    val settle = remember { Animatable(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }

    val offset = if (dragging) dragX else settle.value

    // Fades as it travels, so letting go halfway reads as "not far enough"
    // rather than as nothing having happened.
    val fade = (1f - abs(offset) / (threshold * 2f)).coerceIn(0.25f, 1f)

    LaunchedEffect(gone) {
        if (gone) NotificationHub.dismiss(item)
    }

    // Whether the reply box is open on this card. Held per card and reset when
    // the notification is replaced, so an answer typed into one message does
    // not survive into the next one that arrives in the same conversation.
    var replying by remember(item.key) { mutableStateOf(false) }
    // What a suggested answer put in the box; the box is still where it is sent from.
    var draft by remember(item.key) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = offset
                alpha = fade
            }
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    dragX += delta
                },
                onDragStarted = {
                    scope.launch { settle.stop() }
                    dragX = settle.value
                    dragging = true
                },
                onDragStopped = { velocity ->
                    val far = abs(dragX) > threshold || abs(velocity) > FLING_PX_PER_SECOND
                    scope.launch {
                        settle.snapTo(dragX)
                        dragging = false
                        if (far && item.clearable) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Off the side first, then out of the list, so the
                            // gap closing is a separate beat from the card
                            // leaving rather than both at once.
                            settle.animateTo(
                                if (dragX > 0) threshold * 4f else -threshold * 4f,
                                spring(dampingRatio = 1f, stiffness = 900f),
                                initialVelocity = velocity,
                            )
                            gone = true
                        } else {
                            settle.animateTo(
                                0f,
                                spring(dampingRatio = 0.65f, stiffness = 700f),
                                initialVelocity = velocity,
                            )
                        }
                    }
                },
            )
    ) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { NotificationHub.open(context, item) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        // 14 + 4 + 12 = 30dp to the text, matching the calendar pill and the
        // badge above it.
        Box(
            Modifier
                .width(4.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.accent.copy(alpha = 0.65f))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                // Says how many messages the one card stands for. Without it a
                // folded conversation looks like a single message whose summary
                // happens to mention several things.
                text = if (item.thread.size > 1) {
                    item.appLabel + " · " +
                        stringResource(R.string.notification_folded, item.thread.size)
                } else {
                    item.appLabel
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            // When the on-device model has rewritten the notification, the
            // rewrite already carries every useful signal — title and body
            // folded into one line — so a separate title above it would
            // just repeat what is below. When post-processing is off (or
            // the model came back with nothing), fall back to the classic
            // title-then-body layout.
            val processed = item.processedText?.takeIf { it.isNotBlank() }
            if (processed != null) {
                Text(
                    text = processed,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink,
                    // The model already distilled this to a short summary.
                    // Do not cut that summary a second time: narrow screens
                    // and the reply action can turn its 90 characters into
                    // three lines. The enclosing shade has its own capped,
                    // scrollable height, so showing every line cannot push the
                    // rest of the home screen away.
                )
            } else {
                Text(
                    text = item.title.ifBlank { item.text },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.title.isNotBlank() && item.text.isNotBlank()) {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // The affordance, and only on the messages that actually carry one.
        //
        // Most notifications cannot be answered, so this is not a button that
        // is usually greyed out — it is simply not there. On the ones that can,
        // it sits at the end of the card's own row rather than on a line of its
        // own, so a shade full of messages does not become twice as tall.
        if (item.canReply && !replying) {
            Spacer(Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .clickable { replying = true }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Reply,
                    contentDescription = stringResource(R.string.notification_reply),
                    tint = colors.accent,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }

    if (item.canReply && replying) {
        ReplyBox(
            item = item,
            initial = draft,
            onDone = { replying = false; draft = "" },
        )
    }

    }
}


/**
 * Answering a message without leaving the home screen.
 *
 * The whole feature in one row: a field, and a send. It goes through the app's
 * own reply action — the same one the notification shade uses — so what arrives
 * on the other side is an ordinary message from you, not something Aura sent on
 * your behalf.
 *
 * Failure is worth showing rather than swallowing. A reply intent goes stale
 * when the conversation has already been dealt with somewhere else, and a
 * message that silently did not send is the worst outcome this can have.
 */
@Composable
private fun ReplyBox(item: NotificationItem, initial: String = "", onDone: () -> Unit) {
    val colors = Aura.colors
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    var text by remember(item.key) { mutableStateOf(initial) }
    var failed by remember(item.key) { mutableStateOf(false) }

    // Opening the box is a decision to write something, so the keyboard comes
    // with it rather than asking for a second tap on the field.
    LaunchedEffect(item.key) { runCatching { focus.requestFocus() } }

    fun send() {
        if (text.isBlank()) return
        if (NotificationHub.reply(context, item, text)) onDone() else failed = true
    }

    Column(Modifier.padding(start = 30.dp, end = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.paperDeep)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = item.reply?.hint?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.notification_reply_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        failed = false
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Spacer(Modifier.width(6.dp))
            val canSend = text.isNotBlank()
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(if (canSend) colors.accent else colors.hairline)
                    .clickable(enabled = canSend) { send() },
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(R.string.notification_reply_send),
                    tint = if (canSend) colors.surface else colors.inkFaint,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        if (failed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.notification_reply_failed),
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
            )
        }
    }
}

/** How far a card has to travel before letting go dismisses it. */
private val DISMISS_DISTANCE = 96.dp

/** A flick faster than this decides regardless of distance. */
private const val FLING_PX_PER_SECOND = 900f
