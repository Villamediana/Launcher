package com.aura.launcher.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.apps.AppEntry
import com.aura.launcher.apps.AppShortcut
import com.aura.launcher.control.GlobalAction
import com.aura.launcher.gestures.CARRIED
import com.aura.launcher.gestures.GestureAction
import com.aura.launcher.ui.theme.Aura
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Choosing one step: what the apps on this phone say they can do, plus the
 * phone's own buttons. Android lets a launcher read the shortcuts every app
 * publishes for itself, so the list is not something anybody here wrote.
 *
 * The other step kinds stay in [GestureAction] because the runner still knows
 * how to run them.
 */
@Composable
fun StepPicker(app: AuraApp, onPick: (GestureAction) -> Unit, onDismiss: () -> Unit) {
    val colors = Aura.colors

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.surface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 22.dp)
                .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 700f)),
        ) {
            Text(
                stringResource(R.string.step_picker_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
            )
            Spacer(Modifier.height(16.dp))

            AppsStep(app, onPick)
        }
    }
}

/**
 * Everything the apps on this phone can be asked to do, app by app.
 *
 * The one door whose contents nobody here wrote. Android lets a launcher read
 * the shortcuts each installed app publishes for itself — the list under a long
 * press on its icon — so what WhatsApp can do is answered by WhatsApp, today,
 * on this phone. An app that adds one next week turns up without a release.
 *
 * Every app has at least one: opening it. The rest depends on the app, and is
 * capped, because a few apps publish a dozen and a list that long stops being a
 * list of what this app does and becomes a second app drawer.
 *
 * Two groups are not apps. The launcher's own (the list of gestures), and the
 * phone, because a torch and the back button belong to no app at all.
 */
@Composable
private fun AppsStep(app: AuraApp, onPick: (GestureAction) -> Unit) {
    val res = LocalContext.current.resources
    val apps by app.appRepository.apps.collectAsState()
    var expanded by remember { mutableStateOf("") }

    // No scroll of its own. The dialog already scrolls, and a list that scrolls
    // inside something that scrolls is two surfaces competing for one finger —
    // the thumb moves whichever it happens to catch, and neither goes where it
    // was pushed.
    Column {
        AppGroup(
            title = stringResource(R.string.step_group_aura),
            open = expanded == AURA_KEY,
            onToggle = { expanded = if (expanded == AURA_KEY) "" else AURA_KEY },
        ) {
            Leaf(stringResource(R.string.gesture_show_gestures)) {
                onPick(GestureAction.ShowGestures)
            }
        }

        AppGroup(
            title = stringResource(R.string.step_group_phone),
            open = expanded == PHONE_KEY,
            onToggle = { expanded = if (expanded == PHONE_KEY) "" else PHONE_KEY },
        ) {
            Leaf(stringResource(R.string.gesture_torch_on)) { onPick(GestureAction.Torch(true)) }
            Leaf(stringResource(R.string.gesture_torch_off)) { onPick(GestureAction.Torch(false)) }
            GlobalAction.entries.forEach { action ->
                Leaf(systemLabel(action, res)) { onPick(GestureAction.System(action)) }
            }
        }

        apps.sortedBy { it.label.lowercase() }.forEach { entry ->
            AppGroup(
                title = entry.label,
                open = expanded == entry.packageName,
                onToggle = {
                    expanded = if (expanded == entry.packageName) "" else entry.packageName
                },
            ) {
                // Always, and first. Every app can be opened; the rest is
                // whatever it chose to publish.
                Leaf(stringResource(R.string.step_open_app)) {
                    onPick(GestureAction.OpenApp(entry.packageName, entry.label))
                }
                AppShortcuts(app, entry, onPick)
            }
        }
    }
}

@Composable
private fun AppShortcuts(app: AuraApp, entry: AppEntry, onPick: (GestureAction) -> Unit) {
    val colors = Aura.colors
    var shortcuts by remember(entry.packageName) { mutableStateOf<List<AppShortcut>?>(null) }

    // Off the main thread, and only once the app has been opened: reading
    // another app's shortcuts is a binder call, and doing it for every row
    // would be one per installed app before anything is shown at all.
    LaunchedEffect(entry.packageName) {
        shortcuts = withContext(Dispatchers.IO) {
            app.appRepository.shortcutsFor(entry, limit = MAX_APP_ACTIONS)
        }
    }

    val found = shortcuts
    if (found == null) {
        Text(
            stringResource(R.string.step_shortcuts_loading),
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkFaint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        return
    }
    found.forEach { shortcut ->
        Leaf(shortcut.label) {
            onPick(
                GestureAction.AppShortcut(
                    packageName = shortcut.packageName,
                    shortcutId = shortcut.id,
                    label = shortcut.label,
                    appLabel = entry.label,
                )
            )
        }
    }
}

/** An app in the list: a name, and what it can do folded behind it. */
@Composable
private fun AppGroup(
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = Aura.colors
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (open) colors.accent else colors.ink,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 11.dp),
        )
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = 800f)) + fadeIn(),
            exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 800f)) + fadeOut(),
        ) {
            Column(Modifier.padding(start = 14.dp)) { content() }
        }
    }
}

@Composable
private fun Leaf(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = Aura.colors.inkMuted,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * A system button's name, as a button rather than as a past-tense report.
 *
 * [GlobalAction.description] reads "Went back" — written for the model, which
 * is told what happened after the fact. On a row somebody is about to tap that
 * is both the wrong tense and the wrong language.
 */
internal fun systemLabel(action: GlobalAction, res: android.content.res.Resources): String =
    res.getString(
        when (action) {
            GlobalAction.BACK -> R.string.gesture_system_back
            GlobalAction.HOME -> R.string.gesture_system_home
            GlobalAction.RECENTS -> R.string.gesture_system_recents
            GlobalAction.NOTIFICATIONS -> R.string.gesture_system_notifications
            GlobalAction.QUICK_SETTINGS -> R.string.gesture_system_quick_settings
            GlobalAction.LOCK_SCREEN -> R.string.gesture_system_lock
        }
    )

private const val AURA_KEY = " aura"
private const val PHONE_KEY = " phone"

/**
 * How many of an app's own actions to show.
 *
 * A few apps publish a dozen. A list that long stops being "what this app can
 * do" and becomes a second app drawer inside a picker.
 */
private const val MAX_APP_ACTIONS = 6
