package com.aura.launcher.ui.components

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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.apps.AppEntry
import com.aura.launcher.apps.AppShortcut
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Long-press menu for an app: pin to the home screen, hide it, inspect it,
 * uninstall it.
 *
 * Pinning lives here rather than in a settings list because this is where the
 * user is already looking at the app they mean — a separate "choose your
 * favourites" screen makes you find the app twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppActionsSheet(
    app: AuraApp,
    entry: AppEntry,
    onDismiss: () -> Unit,
) {
    val colors = Aura.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    /**
     * Where the writes run.
     *
     * The application's scope, not the composition's. Every row here does its
     * work and then closes the sheet — and closing the sheet takes this
     * composable out of the tree, which cancelled the coroutine that was still
     * writing. That is the whole of why "Pin to home screen" did nothing: the
     * DataStore edit touches disk, so it lost that race almost every time. The
     * reordering rows worked only because they happen not to dismiss.
     */
    val scope = app.scope

    val favorites by app.settingsRepository.favorites.collectAsState(initial = emptyList())
    val hidden by app.settingsRepository.hidden.collectAsState(initial = emptySet())

    val isFavorite = entry.key in favorites
    val isHidden = entry.key in hidden
    var renaming by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.ink,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(34.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.inkFaint.copy(alpha = 0.4f))
                )
            }
        },
    ) {
        Column(Modifier.padding(bottom = 28.dp)) {
            // Name and package only. The drawer has no icons, so showing one
            // here would be the single place in the app where an app's branding
            // appears — jarring rather than helpful.
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(
                    text = entry.displayLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.alias.isNullOrBlank()) entry.packageName
                    else "${entry.label} · ${entry.packageName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(12.dp))

            // The app's own shortcuts, above everything the launcher can do to
            // it — because they are what the long-press is for on every other
            // launcher, and because they are the only rows here that do the
            // thing the user actually wants rather than administer the icon.
            //
            // Read once per sheet, off the main thread: getShortcuts is a
            // binder call into the system, and the sheet animates in while it
            // is happening. Absent entirely when there are none, or when Aura
            // is not the home app — the system only hands shortcuts to the
            // current launcher, and an empty heading explaining that is worse
            // than no heading.
            val settings by app.currentSettings.collectAsState()
            var shortcuts by remember(entry.key) { mutableStateOf<List<AppShortcut>>(emptyList()) }
            LaunchedEffect(entry.key, settings.appShortcutsEnabled, settings.appShortcutCount) {
                shortcuts = if (!settings.appShortcutsEnabled) {
                    emptyList()
                } else {
                    withContext(Dispatchers.IO) {
                        app.appRepository.shortcutsFor(entry, settings.appShortcutCount)
                    }
                }
            }

            if (shortcuts.isNotEmpty()) {
                for (shortcut in shortcuts) {
                    SheetAction(
                        icon = Icons.Rounded.Bolt,
                        label = shortcut.label,
                        tint = colors.accent,
                    ) {
                        app.appRepository.launchShortcut(shortcut)
                        scope.launch { app.settingsRepository.recordLaunch(entry.key) }
                        onDismiss()
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 26.dp)
                        .height(1.dp)
                        .background(colors.hairline)
                )
                Spacer(Modifier.height(8.dp))
            }

            SheetAction(
                icon = if (isFavorite) Icons.Outlined.StarOutline else Icons.Outlined.StarBorder,
                label = if (isFavorite) stringResource(R.string.app_action_unpin) else stringResource(R.string.app_action_pin),
                tint = if (isFavorite) colors.inkMuted else colors.accent,
            ) {
                scope.launch {
                    val updated = if (isFavorite) {
                        favorites - entry.key
                    } else {
                        favorites + entry.key
                    }
                    app.settingsRepository.setFavorites(updated)
                }
                onDismiss()
            }

            // Reordering by moving one place at a time. Drag-and-drop inside a
            // sheet that is itself draggable fights the sheet's own gesture;
            // two taps are unglamorous but they always do what you meant.
            if (isFavorite) {
                val position = favorites.indexOf(entry.key)
                if (position > 0) {
                    SheetAction(icon = Icons.Rounded.KeyboardArrowUp, label = stringResource(R.string.app_action_move_up)) {
                        scope.launch {
                            val reordered = favorites.toMutableList()
                            reordered.removeAt(position)
                            reordered.add(position - 1, entry.key)
                            app.settingsRepository.setFavorites(reordered)
                        }
                    }
                }
                if (position >= 0 && position < favorites.lastIndex) {
                    SheetAction(icon = Icons.Rounded.KeyboardArrowDown, label = stringResource(R.string.app_action_move_down)) {
                        scope.launch {
                            val reordered = favorites.toMutableList()
                            reordered.removeAt(position)
                            reordered.add(position + 1, entry.key)
                            app.settingsRepository.setFavorites(reordered)
                        }
                    }
                }
            }

            SheetAction(
                icon = Icons.Outlined.DriveFileRenameOutline,
                label = if (entry.alias.isNullOrBlank()) stringResource(R.string.app_action_rename)
                else stringResource(R.string.app_action_rename_current, entry.alias),
            ) {
                renaming = true
            }

            SheetAction(
                icon = Icons.Outlined.VisibilityOff,
                label = if (isHidden) stringResource(R.string.app_action_show) else stringResource(R.string.app_action_hide),
            ) {
                scope.launch { app.settingsRepository.toggleHidden(entry.key) }
                onDismiss()
            }

            // Screen control is not offered here any more. It was a row on every
            // app's menu for a decision almost nobody makes by hand: the banking
            // apps that need the pause are detected on their own, before they
            // start, which is the only moment it helps. Marking one by hand is
            // gone with it, rather than sitting five millimetres from Uninstall
            // on the menu people open to pin things.

            SheetAction(icon = Icons.Outlined.Info, label = stringResource(R.string.app_action_info)) {
                app.appRepository.openAppInfo(entry)
                onDismiss()
            }

            SheetAction(
                icon = Icons.Outlined.DeleteOutline,
                label = stringResource(R.string.app_action_uninstall),
                tint = colors.danger,
            ) {
                app.appRepository.requestUninstall(entry)
                onDismiss()
            }
        }
    }

    if (renaming) {
        RenameDialog(
            entry = entry,
            onConfirm = { newName ->
                scope.launch { app.settingsRepository.setAlias(entry.key, newName) }
                renaming = false
                onDismiss()
            },
            onClear = {
                scope.launch { app.settingsRepository.setAlias(entry.key, "") }
                renaming = false
                onDismiss()
            },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun RenameDialog(
    entry: AppEntry,
    onConfirm: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Aura.colors
    // Prefill with the current alias when one exists, so editing feels like
    // editing, not typing over. A blank field with the real label as
    // placeholder makes the "clear" path obvious too.
    var text by remember { mutableStateOf(entry.alias.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.ink,
        textContentColor = colors.ink,
        title = { Text(stringResource(R.string.app_rename_dialog_title, entry.label)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.app_rename_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(40) },
                    placeholder = { Text(entry.label, color = colors.inkFaint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.common_save), color = colors.accent)
            }
        },
        dismissButton = {
            Row {
                if (!entry.alias.isNullOrBlank()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.common_clear), color = colors.danger)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel), color = colors.inkMuted)
                }
            }
        },
    )
}

/**
 * One row of the menu: an outlined glyph, then the words.
 *
 * Outlined rather than filled, and larger than they were. A filled icon at
 * 19dp beside body text reads as a bullet point — a mark that the line exists
 * rather than a thing you can identify at a glance. At 22dp with a stroke it is
 * legible on its own, which is the whole reason for putting an icon on a menu
 * row: the shape is found faster than the sentence is read.
 *
 * The row is taller too. This is a menu opened by holding a finger on
 * something, so it is used by the same thumb that is already in the way of it.
 */
@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    tint: Color = Aura.colors.ink,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 17.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
