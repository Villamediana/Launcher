package com.aura.launcher.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.data.HomeWidget
import com.aura.launcher.data.subtitleRes
import com.aura.launcher.data.titleRes
import com.aura.launcher.data.Settings
import com.aura.launcher.ui.home.ClockStyleSheet
import com.aura.launcher.ui.home.TodoSheet
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.launch

/**
 * The home screen, itemised.
 *
 * Aura spent a version with none of this: every card was fixed on, on the
 * argument that a home screen without its clock is simply worse. That argument
 * held while every widget was something everyone wants. It stopped holding the
 * moment the launcher grew cards that belong to one person's life — a medicine
 * round — because a card about somebody's course of antibiotics on the phone of
 * someone who is not taking any is not a better home screen, it is someone
 * else's.
 *
 * So the switches are back, once, in one place, and nowhere else: this page is
 * the only answer to "what is on my home screen", and each row carries the
 * widget's own setup behind the same tap target.
 */
@Composable
internal fun WidgetsSettings(
    app: AuraApp,
    settings: Settings,
    update: ((Settings) -> Settings) -> Unit,
) {
    val colors = Aura.colors
    val scope = rememberCoroutineScope()

    // Which widget has its own setup open. One at a time, by id, because two
    // sheets on top of each other is a stack nobody asked for.
    var sheetFor by remember { mutableStateOf<HomeWidget?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_widgets_header_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkFaint,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(6.dp))

        SettingsGroup {
            HomeWidget.entries.forEachIndexed { index, widget ->
                if (index > 0) Divider()
                val on = settings.shows(widget)
                WidgetRow(
                    icon = iconFor(widget),
                    title = stringResource(widget.titleRes),
                    subtitle = stringResource(widget.subtitleRes),
                    checked = on,
                    // Setup is offered only where there is something to set up,
                    // and only while the widget is actually on the screen —
                    // configuring something invisible is a dead end.
                    onConfigure = if (on) configureAction(widget) { sheetFor = it } else null,
                    onChange = { wanted ->
                        update { current ->
                            current.copy(
                                hiddenWidgets = if (wanted) current.hiddenWidgets - widget.id
                                else current.hiddenWidgets + widget.id,
                            )
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
    }

    when (sheetFor) {
        HomeWidget.CLOCK -> ClockStyleSheet(
            current = settings.clockStyle,
            hideStatusBar = settings.hideStatusBar,
            onPick = { choice ->
                scope.launch { app.settingsRepository.update { it.copy(clockStyle = choice) } }
                sheetFor = null
            },
            onDismiss = { sheetFor = null },
        )
        HomeWidget.TODO -> TodoSheet(app = app, onDismiss = { sheetFor = null })
        else -> Unit
    }
}

/** What each row's setup button does, or null when the widget has no setup. */
private fun configureAction(
    widget: HomeWidget,
    openSheet: (HomeWidget) -> Unit,
): (() -> Unit)? = when (widget) {
    HomeWidget.CLOCK, HomeWidget.TODO ->
        ({ openSheet(widget) })
    else -> null
}

private fun iconFor(widget: HomeWidget): ImageVector = when (widget) {
    HomeWidget.CLOCK -> Icons.Rounded.Schedule
    HomeWidget.DATE -> Icons.Rounded.Today
    HomeWidget.BATTERY -> Icons.Rounded.BatteryFull
    HomeWidget.WEATHER -> Icons.Rounded.Cloud
    HomeWidget.TIMER -> Icons.Rounded.Schedule
    HomeWidget.TODO -> Icons.Rounded.Checklist
    HomeWidget.NOW_PLAYING -> Icons.Rounded.MusicNote
    HomeWidget.CALENDAR -> Icons.Rounded.CalendarMonth
    HomeWidget.NOTIFICATIONS -> Icons.Rounded.Notifications
    HomeWidget.MAP -> Icons.Rounded.Map
    HomeWidget.NOW -> Icons.Rounded.Bolt
    HomeWidget.SKY -> Icons.Rounded.WbTwilight
}

/**
 * One widget: what it is, whether it is on, and the way into its own settings.
 *
 * A row that is off keeps its full contrast on the switch and loses it
 * everywhere else, so the page can be read down the left edge — what is on the
 * home screen is what is legible.
 */
@Composable
private fun WidgetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    onConfigure: (() -> Unit)?,
) {
    val colors = Aura.colors
    val dim by animateFloatAsState(
        targetValue = if (checked) 1f else 0.45f,
        animationSpec = spring(stiffness = 700f),
        label = "widgetRowDim",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Box(
            modifier = Modifier
                .alpha(dim)
                .size(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(colors.accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).alpha(dim)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.inkFaint)
        }

        if (onConfigure != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onConfigure)
                    .background(colors.surfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = stringResource(R.string.widget_configure),
                    tint = colors.inkMuted,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
        }

        Switch(
            checked = checked,
            onCheckedChange = onChange,
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

