package com.aura.launcher.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.control.GlobalAction
import com.aura.launcher.gestures.CARRIED
import com.aura.launcher.gestures.Dot
import com.aura.launcher.gestures.Gesture
import com.aura.launcher.gestures.GestureAction
import com.aura.launcher.gestures.GestureShape
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How many times a new gesture is drawn before it is saved. */
private const val WANTED_DRAWINGS = 3

/**
 * Teaching one gesture: draw it, name it, say what it does.
 *
 * Three drawings rather than one, and that is the whole reason this is a flow
 * and not a dialog. A shape captured once only matches when it is drawn the
 * same way twice, which nobody does — the second and third examples are not
 * confirmation, they are the tolerance. Asking for them here, while the person
 * is looking at the screen and expecting to be asked, is far kinder than
 * letting them find out later that their gesture works four times in ten.
 *
 * Two things are refused, both while they can still be redrawn: examples that
 * are not the same shape as each other, and a shape too close to a gesture
 * already saved. Neither produces an error later — they produce a gesture that
 * silently does the wrong thing, or nothing.
 */
@Composable
fun GestureBuilder(app: AuraApp, editing: Gesture?, onClose: () -> Unit) {
    val colors = Aura.colors
    val saved by app.gestures.gestures.collectAsState()

    val drawings = remember { mutableStateListOf<List<Dot>>() }
    val actions = remember { mutableStateListOf<GestureAction>() }
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var complaint by remember { mutableStateOf<String?>(null) }
    var redrawing by remember { mutableStateOf(editing == null) }
    var picking by remember { mutableStateOf(false) }

    // Editing keeps the shape unless the user asks to redraw it: the point of
    // opening a saved gesture is nearly always to change what it does.
    val existingShapes = remember(editing) { editing?.shapes().orEmpty() }
    editing?.let { current ->
        remember(current.id) {
            actions.clear()
            actions.addAll(current.actions)
            true
        }
    }

    val disagreeMessage = stringResource(R.string.gesture_builder_disagree)
    val res = LocalContext.current.resources
    val shortMessage = stringResource(R.string.gesture_builder_too_short)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
        ) {
            Text(
                text = if (editing == null) {
                    stringResource(R.string.gesture_builder_new)
                } else {
                    editing.name
                },
                style = MaterialTheme.typography.headlineSmall,
                color = colors.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (redrawing) {
                    stringResource(R.string.gesture_builder_draw_times, drawings.size, WANTED_DRAWINGS)
                } else {
                    stringResource(R.string.gesture_builder_what_it_does)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkFaint,
            )

            Spacer(Modifier.height(18.dp))

            if (redrawing) {
                GesturePad(
                    onStroke = { stroke ->
                        complaint = null
                        val shape = GestureShape.normaliseStrokes(stroke)
                        when {
                            !GestureShape.strokesLongEnough(stroke) || shape.isEmpty() ->
                                complaint = shortMessage

                            drawings.isNotEmpty() &&
                                !GestureShape.agreeWithEachOther(drawings + listOf(shape)) ->
                                complaint = disagreeMessage

                            else -> {
                                val clash = GestureShape.collidingName(
                                    listOf(shape),
                                    saved.filter { it.id != editing?.id }
                                        .map { it.name to it.shapes() },
                                )
                                if (clash != null) {
                                    complaint =
                                        res.getString(R.string.gesture_builder_collides, clash)
                                } else {
                                    drawings += shape
                                    if (drawings.size >= WANTED_DRAWINGS) redrawing = false
                                }
                            }
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    repeat(WANTED_DRAWINGS) { index ->
                        Box(
                            Modifier
                                .padding(horizontal = 5.dp)
                                .size(9.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (index < drawings.size) colors.accent
                                    else colors.hairline
                                )
                        )
                    }
                }
            } else {
                // The shape, as it will be matched. Shown while the actions are
                // chosen so the two halves of the gesture are on screen at once.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShapePreview(
                        (drawings.firstOrNull() ?: existingShapes.firstOrNull()).orEmpty(),
                        Modifier.size(64.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    IconAction(
                        icon = Icons.Rounded.Refresh,
                        description = stringResource(R.string.gesture_builder_redraw),
                        tint = colors.accent,
                    ) { drawings.clear(); redrawing = true }
                }

                Spacer(Modifier.height(20.dp))
                NameField(name, colors.ink, colors.hairline) { name = it }

                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.gesture_builder_actions),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.height(8.dp))
                StepFlow(
                    steps = actions,
                    onUp = { index ->
                        if (index > 0) {
                            val moved = actions.removeAt(index)
                            actions.add(index - 1, moved)
                        }
                    },
                    onDown = { index ->
                        if (index < actions.lastIndex) {
                            val moved = actions.removeAt(index)
                            actions.add(index + 1, moved)
                        }
                    },
                    onRemove = { index -> actions.removeAt(index) },
                    onAdd = { picking = true },
                )
            }

            complaint?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.danger)
            }

            Spacer(Modifier.height(28.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (editing != null) {
                    IconAction(
                        icon = Icons.Rounded.DeleteOutline,
                        description = stringResource(R.string.gesture_builder_delete),
                        tint = colors.danger,
                        size = 44.dp,
                    ) {
                        app.scope.launch { app.gestures.delete(editing.id) }
                        onClose()
                    }
                    Spacer(Modifier.weight(1f))
                }
                IconAction(
                    icon = Icons.Rounded.Close,
                    description = stringResource(R.string.gesture_builder_cancel),
                    tint = colors.inkMuted,
                    size = 44.dp,
                    onClick = onClose,
                )
                Spacer(Modifier.width(6.dp))
                val ready = !redrawing && name.isNotBlank() && actions.isNotEmpty()
                IconAction(
                    icon = Icons.Rounded.Check,
                    description = stringResource(R.string.gesture_builder_save),
                    tint = colors.accent,
                    enabled = ready,
                    filled = true,
                    size = 44.dp,
                ) {
                    val shapes = drawings.toList().ifEmpty { existingShapes }
                    app.scope.launch {
                        app.gestures.save(
                            name = name,
                            drawings = shapes,
                            actions = actions.toList(),
                            replacing = editing?.id,
                        )
                    }
                    onClose()
                }
            }
        }
    }

    if (picking) {
        StepPicker(
            app = app,
            onPick = { step ->
                actions += step
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The steps, as a thing that visibly runs top to bottom.
 *
 * A numbered list would carry the same information and none of the meaning.
 * What is being built here is a sequence — each step handing to the next, one
 * of them possibly passing a value along — and the line down the left is the
 * cheapest way to say so on a screen this size. A canvas with boxes and wires
 * is the desktop answer to the same problem; on a phone it is a thing to pan
 * around rather than a thing to read.
 */
@Composable
private fun StepFlow(
    steps: List<GestureAction>,
    onUp: (Int) -> Unit,
    onDown: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    val colors = Aura.colors
    Column(Modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                // The thread. Drawn as a column beside the cards rather than
                // between them so it does not break where a card is tall.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(26.dp).fillMaxHeight(),
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(50))
                            .background(colors.accent)
                    )
                    Box(
                        Modifier
                            .width(2.dp)
                            .weight(1f)
                            .background(
                                if (index == steps.lastIndex) Color.Transparent
                                else colors.accent.copy(alpha = 0.35f)
                            )
                    )
                }

                Column(
                    Modifier
                        .weight(1f)
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.hairline.copy(alpha = 0.22f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        stepText(step, LocalContext.current.resources),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.ink,
                    )
                    if (step.usesCarried()) {
                        Text(
                            stringResource(R.string.step_uses_carried),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.accent,
                        )
                    }
                    if (step.producesCarried()) {
                        Text(
                            stringResource(R.string.step_produces_carried),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkFaint,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        IconAction(
                            icon = Icons.Rounded.KeyboardArrowUp,
                            description = stringResource(R.string.app_action_move_up),
                            enabled = index > 0,
                        ) { onUp(index) }
                        IconAction(
                            icon = Icons.Rounded.KeyboardArrowDown,
                            description = stringResource(R.string.app_action_move_down),
                            enabled = index < steps.lastIndex,
                        ) { onDown(index) }
                        IconAction(
                            icon = Icons.Rounded.DeleteOutline,
                            description = stringResource(R.string.gesture_builder_remove),
                            tint = colors.danger,
                        ) {
                            onRemove(index)
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(26.dp)
                    .padding(start = 8.dp)
                    .size(9.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.accent.copy(alpha = 0.4f))
            )
            IconAction(
                icon = Icons.Rounded.Add,
                description = stringResource(R.string.step_add),
                tint = colors.accent,
                onClick = onAdd,
            )
        }
    }
}

/** Compact toolbar action: the spoken label remains available to accessibility. */
@Composable
private fun IconAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    tint: Color = Aura.colors.inkMuted,
    filled: Boolean = false,
    size: Dp = 40.dp,
    onClick: () -> Unit,
) {
    val colors = Aura.colors
    val foreground = if (enabled) {
        if (filled) colors.surface else tint
    } else {
        colors.inkFaint.copy(alpha = 0.4f)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .background(
                when {
                    filled && enabled -> tint
                    filled -> colors.hairline.copy(alpha = 0.35f)
                    else -> Color.Transparent
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = foreground,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * What a step says on its card, in the reader's language and in full.
 *
 * [GestureAction.summary] is not that. It is a compact, English, forty
 * character line meant for a log and for the one-line description under a
 * gesture's name — and used here it turned a sentence the user had just typed
 * into "Ask: Chame um uber da minha localização até a", cut mid word, prefixed
 * in a language the rest of the screen is not in.
 *
 * The card is where the step is being edited. Whatever was written belongs on
 * it, all of it, exactly as written.
 */
internal fun stepText(step: GestureAction, res: android.content.res.Resources): String = when (step) {
    is GestureAction.OpenApp -> res.getString(R.string.step_open_named, step.label)
    is GestureAction.AppShortcut -> "${step.appLabel}: ${step.label}"
    is GestureAction.Torch ->
        res.getString(if (step.on) R.string.gesture_torch_on else R.string.gesture_torch_off)
    is GestureAction.System -> systemLabel(step.action, res)
    GestureAction.ShowGestures -> res.getString(R.string.gesture_show_gestures)
    // Everything else is a kind the picker no longer offers — kept because
    // gestures saved before it still use them, and its own line is fine.
    else -> step.summary()
}

/** Whether this step reads what the one before it produced. */
private fun GestureAction.usesCarried(): Boolean = when (this) {
    is GestureAction.SendText -> text.contains(CARRIED)
    is GestureAction.OpenLink -> url.contains(CARRIED)
    is GestureAction.Clipboard -> !read && text.contains(CARRIED)
    else -> false
}

/** Whether this step hands something to the one after it. */
private fun GestureAction.producesCarried(): Boolean = when (this) {
    is GestureAction.Clipboard -> read
    else -> false
}

/**
 * The square you draw in, with the same light the real thing has.
 *
 * Deliberately the same aesthetic as the overlay rather than a wireframe pad:
 * what is being taught here is a physical movement, and it should feel like the
 * movement it will be.
 */
@Composable
private fun GesturePad(onStroke: (List<List<Dot>>) -> Unit) {
    val colors = Aura.colors
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val points = remember { mutableStateListOf<Offset>() }
    var finished by remember { mutableStateOf(0) }

    // The shape is done once the hand has stayed off the pad.
    //
    // The same rule the real canvas uses, and it has to be the same or a shape
    // taught here would not be the shape recognised there. Lifting ends a
    // stroke; a pause ends the drawing.
    LaunchedEffect(finished) {
        if (finished == 0 || strokes.isEmpty()) return@LaunchedEffect
        delay(NEXT_STROKE_MS)
        if (points.isNotEmpty()) return@LaunchedEffect
        onStroke(strokes.map { stroke -> stroke.map { Dot(it.x, it.y) } })
        strokes.clear()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceRaised)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        points.clear()
                        points += start
                    },
                    onDrag = { change, _ ->
                        points += change.position
                        change.consume()
                    },
                    onDragEnd = {
                        if (points.size >= 2) strokes += points.toList()
                        points.clear()
                        finished++
                    },
                    onDragCancel = { points.clear() },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val drawn = strokes.filter { it.size >= 2 } +
                listOf(points.toList()).filter { it.size >= 2 }
            if (drawn.isEmpty()) return@Canvas
            val path = Path()
            drawn.forEach { stroke ->
                path.moveTo(stroke.first().x, stroke.first().y)
                stroke.drop(1).forEach { path.lineTo(it.x, it.y) }
            }
            // The same four passes as the overlay: a haze, then a core.
            listOf(22f to 0.10f, 13f to 0.20f, 7f to 0.45f, 3f to 1f).forEach { (w, a) ->
                drawPath(
                    path,
                    brush = SolidColor(colors.accent.copy(alpha = a)),
                    style = Stroke(width = w),
                )
            }
            if (points.size >= 2) drawCircle(colors.accent, radius = 7f, center = points.last())
        }
    }
}

/** Kept in step with the canvas: see GestureLayer. */
private const val NEXT_STROKE_MS = 380L

@Composable
private fun ShapePreview(shape: List<Dot>, modifier: Modifier = Modifier) {
    val colors = Aura.colors
    Canvas(modifier) {
        if (shape.size < 2) return@Canvas
        val factor = size.minDimension / GestureShape.BOX * 0.8f
        val path = Path()
        shape.forEachIndexed { index, dot ->
            val x = size.width / 2f + dot.x * factor
            val y = size.height / 2f + dot.y * factor
            // A shape drawn in two goes is drawn back in two goes.
            if (index == 0 || dot.start) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, SolidColor(colors.accent), style = Stroke(width = size.minDimension / 16f))
        val last = shape.last()
        drawCircle(
            colors.accent,
            radius = size.minDimension / 18f,
            center = Offset(size.width / 2f + last.x * factor, size.height / 2f + last.y * factor),
        )
    }
}

@Composable
private fun NameField(value: String, ink: androidx.compose.ui.graphics.Color, line: androidx.compose.ui.graphics.Color, onChange: (String) -> Unit) {
    val colors = Aura.colors
    Column {
        Text(
            stringResource(R.string.gesture_builder_name),
            style = MaterialTheme.typography.labelLarge,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(line.copy(alpha = 0.25f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}
