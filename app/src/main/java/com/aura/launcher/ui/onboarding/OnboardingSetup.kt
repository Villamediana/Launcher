package com.aura.launcher.ui.onboarding

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.data.Settings
import com.aura.launcher.ui.components.ActionButton
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.launch

/**
 * The step that makes setup end with a launcher that works: without these the
 * weather says nothing and the agenda is empty.
 */

// ------------------------------------------------------------- permissions --

/**
 * Every permission, asked here rather than found later.
 *
 * The runtime ones go through one button, because six consecutive system
 * dialogs is a smaller thing to sit through once than six separate discoveries
 * that a widget is blank for a reason. The special accesses — notification
 * listener, accessibility, overlay, exact alarms, install — cannot be batched:
 * each is its own page in Android's settings, so each is its own row.
 *
 * Nothing here is required. Skipping it costs the weather, the agenda and the
 * reminders, and the rows below say which is which by staying unticked.
 */
@Composable
fun PermissionsStep(app: AuraApp) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }

    // Rechecked on resume as well as after the run, because a permission can
    // also be granted from Android's own settings page while this screen is in
    // the background — and a button still offering to ask for what is already
    // allowed is the kind of thing that makes people doubt the tick beside it.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val missing = remember(asking, tick) { runtimePermissions().filterNot { isGranted(context, it) } }

    Column {
        StepHeader(
            icon = Icons.Rounded.Lock,
            title = stringResource(R.string.ob_perms_title),
            subtitle = stringResource(R.string.ob_perms_subtitle),
        )
        Spacer(Modifier.height(20.dp))

        if (missing.isEmpty()) {
            DoneRow(stringResource(R.string.ob_perms_all_granted))
        } else {
            ActionButton(
                text = stringResource(R.string.ob_perms_allow_all),
                filled = false,
                enabled = !asking,
            ) {
                asking = true
                scope.launch {
                    // One at a time and in order: Android shows one dialog at a
                    // time anyway, and a denial on one must not stop the rest
                    // from being offered.
                    runtimePermissions().forEach { permission ->
                        if (!isGranted(context, permission)) app.permissionRequester(permission)
                    }
                    asking = false
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        // The two that are a page of their own in Android's settings. Each is
        // one tap there and back; nothing else is asked for here. The rest
        // (overlay for lock-screen gestures, for instance) is in Settings ›
        // Permissions for whoever wants it.
        val notifAccess = remember(tick) { com.aura.launcher.notifications.NotificationHub.hasAccess(context) }
        StepLine(
            number = 1,
            title = stringResource(R.string.ob_perms_notif_access),
            action = stringResource(R.string.ob_perms_allow),
            done = notifAccess,
        ) {
            // Straight to the launcher's own switch where Android allows it (11+).
            val direct = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(
                            android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            android.content.ComponentName(
                                context,
                                com.aura.launcher.notifications.AuraNotificationListener::class.java,
                            ).flattenToString(),
                        )
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (!direct) runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val canInstall = remember(tick) { context.packageManager.canRequestPackageInstalls() }
        StepLine(
            number = 2,
            title = stringResource(R.string.ob_perms_updates),
            action = stringResource(R.string.ob_perms_allow),
            done = canInstall,
        ) {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:${context.packageName}"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Hint(stringResource(R.string.ob_perms_note))
    }
}

/** The permissions that have a system dialog, in the order they make sense in. */
private fun runtimePermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.READ_CALENDAR)
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED


@Composable
internal fun DoneRow(text: String) {
    val colors = Aura.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.sage.copy(alpha = 0.10f))
            .border(1.dp, colors.sage.copy(alpha = 0.24f), RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Icon(Icons.Rounded.Check, null, tint = colors.sage, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.sage)
    }
}
