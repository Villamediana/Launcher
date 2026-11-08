package com.aura.launcher.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.gestures.Dot
import com.aura.launcher.gestures.Gesture
import com.aura.launcher.gestures.GestureShape
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.launch

/**
 * Teaching the launcher shapes, and saying what they are for.
 *
 * Kept in its own file, using only the two shared building blocks the settings
 * page exports, so the whole feature is a folder and one call: delete
 * `gestures/`, delete this, delete the [com.aura.launcher.ui.settings] entry
 * that opens it, and nothing else knows it existed.
 */
@Composable
fun GesturesSection(app: AuraApp) {
    val colors = Aura.colors
    val gestures by app.gestures.gestures.collectAsState()
    var building by remember { mutableStateOf<Gesture?>(null) }
    var creating by remember { mutableStateOf(false) }

    // At the top, and shaped like the record button under Shortcuts, because it
    // is the same kind of thing: the one control on the page whose job is to be
    // found without hunting. It used to sit under the list, which meant that on
    // a phone with six gestures the way to add a seventh was below the fold.
    PrimaryAction(
        icon = Icons.Rounded.Draw,
        label = stringResource(R.string.settings_gestures_teach),
        onClick = { creating = true },
    )

    if (gestures.isEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.settings_gestures_empty),
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkFaint,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    } else {
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            gestures.forEachIndexed { index, gesture ->
                if (index > 0) Divider()
                GestureRow(
                    gesture = gesture,
                    onClick = { building = gesture },
                    onToggle = { on ->
                        app.scope.launch { app.gestures.setEnabled(gesture.id, on) }
                    },
                )
            }
        }
    }

    // The same shapes, on the lock screen. Here rather than with the
    // permission it needs, because this is a thing gestures can do and this is
    // the page about gestures — the grant it leans on is one line away in
    // System, and this says so when it is missing.
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by app.currentSettings.collectAsState()
    val accessibility = remember {
        com.aura.launcher.control.ScreenController.isEnabledInSettings(context)
    }
    Spacer(Modifier.height(18.dp))
    SettingsGroup {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.lock_gestures_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (accessibility) colors.ink else colors.inkFaint,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        if (accessibility) R.string.lock_gestures_subtitle
                        else R.string.lock_gestures_needs_accessibility
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkFaint,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = settings.lockGestures && accessibility,
                enabled = accessibility,
                onCheckedChange = { on ->
                    app.scope.launch {
                        app.settingsRepository.update { it.copy(lockGestures = on) }
                        // The strip is put up and taken down by the service,
                        // which does not watch settings — so it is told.
                        com.aura.launcher.control.AuraAccessibilityService.instance
                            ?.let { service -> com.aura.launcher.lock.LockCanvas.sync(service) }
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.surfaceRaised,
                    checkedTrackColor = colors.accent,
                    uncheckedThumbColor = colors.inkFaint,
                    uncheckedTrackColor = colors.surface,
                    uncheckedBorderColor = colors.hairline,
                ),
            )
        }
    }

    if (creating || building != null) {
        GestureBuilder(
            app = app,
            editing = building,
            onClose = { creating = false; building = null },
        )
    }
}

@Composable
private fun GestureRow(gesture: Gesture, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val colors = Aura.colors
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        GestureThumb(gesture.shapes().firstOrNull().orEmpty(), Modifier.size(40.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(gesture.name, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            // The steps in the reader's own words. Two lines and then an
            // ellipsis, rather than each step pre-truncated to forty
            // characters: the row already knows how much space it has.
            Text(
                gesture.actions.joinToString("  ·  ") { stepText(it, res) },
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = gesture.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.surface,
                checkedTrackColor = colors.accent,
            ),
        )
    }
}

/**
 * A saved shape, small.
 *
 * The list would otherwise be names, and a name is the one thing about a
 * gesture nobody remembers — the hand remembers the shape. Drawn from the first
 * example, already normalised, so this is a straight mapping from the box the
 * recogniser works in onto however many pixels are going.
 */
@Composable
private fun GestureThumb(shape: List<Dot>, modifier: Modifier = Modifier) {
    val colors = Aura.colors
    androidx.compose.foundation.Canvas(modifier) {
        if (shape.size < 2) return@Canvas
        val scale = size.minDimension / GestureShape.BOX * 0.82f
        val path = Path()
        shape.forEachIndexed { index, dot ->
            val x = size.width / 2f + dot.x * scale
            val y = size.height / 2f + dot.y * scale
            // A shape drawn in two goes is drawn back in two goes.
            if (index == 0 || dot.start) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            SolidColor(colors.accent.copy(alpha = 0.9f)),
            style = Stroke(width = size.minDimension / 14f),
        )
        // The end of the stroke, so which way it was drawn is readable at a
        // glance. Up and down are different gestures and look identical here
        // without it.
        val last = shape.last()
        drawCircle(
            colors.accent,
            radius = size.minDimension / 18f,
            center = androidx.compose.ui.geometry.Offset(
                size.width / 2f + last.x * scale,
                size.height / 2f + last.y * scale,
            ),
        )
    }
}
