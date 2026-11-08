package com.aura.launcher.ui.onboarding

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * State and action for "make Aura the home screen".
 *
 * The role dialog has to be started **for a result** from the Activity. Firing
 * `createRequestRoleIntent` through `Context.startActivity` with NEW_TASK — the
 * obvious-looking approach — is rejected by the system and nothing happens,
 * which is exactly the silent no-op this replaces.
 */
class HomeRoleState(
    val isDefault: Boolean,
    val request: () -> Unit,
)

@Composable
fun rememberHomeRoleState(): HomeRoleState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isDefault by remember { mutableStateOf(isDefaultLauncher(context)) }

    // The user may set the default from the system dialog *or* by wandering
    // into Settings, and neither reports back. Re-check whenever we resume.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isDefault = isDefaultLauncher(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isDefault = isDefaultLauncher(context)
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isDefault = isDefaultLauncher(context)
    }

    return HomeRoleState(
        isDefault = isDefault,
        request = {
            val roleIntent = homeRoleIntent(context)
            if (roleIntent != null) {
                val launched = runCatching { roleLauncher.launch(roleIntent) }.isSuccess
                if (launched) return@HomeRoleState
            }
            // No role manager, or the role is already held by someone else in a
            // way the dialog will not offer: fall back to the settings screen.
            for (intent in fallbackIntents()) {
                if (runCatching { settingsLauncher.launch(intent) }.isSuccess) return@HomeRoleState
            }
        },
    )
}

private fun homeRoleIntent(context: Context): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
    if (!roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) return null
    if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) return null
    return runCatching { roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME) }.getOrNull()
}

private fun fallbackIntents(): List<Intent> = listOf(
    Intent(Settings.ACTION_HOME_SETTINGS),
    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
    Intent(Settings.ACTION_SETTINGS),
)

/** Asks the package manager who currently owns the home intent. */
fun isDefaultLauncher(context: Context): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val resolved = context.packageManager.resolveActivity(
        intent,
        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
    )
    resolved?.activityInfo?.packageName == context.packageName
}.getOrDefault(false)
