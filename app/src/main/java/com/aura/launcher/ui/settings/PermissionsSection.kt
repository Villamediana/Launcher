package com.aura.launcher.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.control.ScreenController
import com.aura.launcher.control.SensitiveApps
import com.aura.launcher.notifications.NotificationHub
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.launch

/**
 * One place for every permission Aura needs.
 *
 * Runtime dangerous permissions (contacts, phone, mic, calendar,
 * location, notifications) are asked with the OS's own dialog. Special
 * accesses (install unknown apps, exact alarms, notification listener,
 * accessibility) do not have a runtime dialog on Android — they can
 * only be granted from a specific system settings screen, so the row
 * opens that screen directly. Either way, the point of this panel is
 * that nobody has to remember which of Android's twenty settings
 * pages the switch lives on.
 */
@Composable
fun PermissionsSection(app: AuraApp) {
    val colors = Aura.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Every row rechecks state on resume, so coming back from Android's
    // settings screen refreshes without needing an explicit callback.
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // One list, runtime dialogs and special-access screens together. Splitting
    // them into two groups was an implementation detail — the user is looking
    // for the row that names the thing they want to allow, and the row's
    // Allow tap opens whichever Android surface grants it.
    SettingsGroup {
        PermissionRow(
            icon = Icons.Rounded.Notifications,
            title = stringResource(R.string.perm_notifications_title),
            granted = remember(tick) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
                else isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
            },
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                scope.launch { app.permissionRequester(Manifest.permission.POST_NOTIFICATIONS); tick++ }
            }
        }
        Divider()
        PermissionRow(
            icon = Icons.Rounded.LocationOn,
            title = stringResource(R.string.perm_location_title),
            granted = remember(tick) { isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION) },
        ) {
            scope.launch { app.permissionRequester(Manifest.permission.ACCESS_COARSE_LOCATION); tick++ }
        }
        Divider()
        PermissionRow(
            icon = Icons.Rounded.CalendarMonth,
            title = stringResource(R.string.perm_calendar_title),
            granted = remember(tick) { isGranted(context, Manifest.permission.READ_CALENDAR) },
        ) {
            scope.launch { app.permissionRequester(Manifest.permission.READ_CALENDAR); tick++ }
        }
        Divider()
        PermissionRow(
            icon = Icons.Rounded.Notifications,
            title = stringResource(R.string.perm_notif_access_title),
            granted = remember(tick) { NotificationHub.hasAccess(context) },
        ) { openSpecial(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        Divider()
        // The gesture service, and the one thing that turns it off without
        // being asked: a banking app coming to the front. That used to be announced
        // on the home screen, which is both the wrong place — nothing about it
        // is urgent, and it appeared on every trip to the bank — and the wrong
        // shape, a pill in the middle of somebody's wallpaper. It belongs here,
        // under the switch it is talking about.
        val pausedFor = remember(tick) {
            if (SensitiveApps.wasAutoPaused(context)) {
                SensitiveApps.pausedForLabel(context)
                    ?: context.getString(R.string.home_paused_fallback)
            } else {
                null
            }
        }
        PermissionRow(
            icon = Icons.Rounded.TouchApp,
            title = stringResource(R.string.perm_accessibility_title),
            granted = remember(tick) { ScreenController.isEnabledInSettings(context) },
            note = pausedFor?.let { stringResource(R.string.perm_accessibility_paused, it) },
        ) { ScreenController.openAccessibilitySettings(context) }

        Divider()
        // The way out of re-enabling by hand after every bank: a permission no
        // app can request, given once from a computer. The row copies the
        // command; with it granted, the service comes back by itself when
        // the home screen does.
        val selfEnable = remember(tick) { ScreenController.canSelfEnable(context) }
        PermissionRow(
            icon = Icons.Rounded.TouchApp,
            title = stringResource(R.string.perm_self_enable_title),
            granted = selfEnable,
            note = if (selfEnable) null else stringResource(R.string.perm_self_enable_note),
        ) {
            val command = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            context.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("adb", command))
            android.widget.Toast.makeText(context, R.string.perm_self_enable_copied, android.widget.Toast.LENGTH_LONG).show()
        }

        Divider()
        PermissionRow(
            icon = Icons.Rounded.Layers,
            title = stringResource(R.string.perm_overlay_title),
            granted = remember(tick) { Settings.canDrawOverlays(context) },
        ) {
            val uri = Uri.parse("package:${context.packageName}")
            openSpecial(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri))
        }
        Divider()
        PermissionRow(
            icon = Icons.Rounded.Schedule,
            title = stringResource(R.string.perm_exact_alarm_title),
            granted = remember(tick) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
                else (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                    ?.canScheduleExactAlarms() == true
            },
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                openSpecial(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }
        Divider()
        PermissionRow(
            icon = Icons.Rounded.SystemUpdate,
            title = stringResource(R.string.perm_install_title),
            granted = remember(tick) { context.packageManager.canRequestPackageInstalls() },
        ) {
            val uri = Uri.parse("package:${context.packageName}")
            openSpecial(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri))
        }
    }
}

/** One row: icon, title, and a check pill or an "Allow" tap target. */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    granted: Boolean,
    /** One line under the title, for a state the title cannot carry. */
    note: String? = null,
    onClick: () -> Unit,
) {
    val colors = Aura.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !granted) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    if (granted) colors.sage.copy(alpha = 0.14f)
                    else colors.accent.copy(alpha = 0.14f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (granted) colors.sage else colors.accent,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (granted) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.sage.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.Check, null, tint = colors.sage, modifier = Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.perm_status_on),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.sage,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.perm_allow),
                style = MaterialTheme.typography.labelMedium,
                color = colors.accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun openSpecial(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
