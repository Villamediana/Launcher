package com.aura.launcher.control

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * The launcher's handle on its accessibility service.
 *
 * The service exists for two things only: the gesture strip over the lock
 * screen and other apps, and the system actions a gesture may end in (back,
 * recents, the shade, locking the screen). Nothing here reads or touches
 * another app's screen.
 */
object ScreenController {

    val isReady: Boolean get() = AuraAccessibilityService.isConnected

    /** True when the user has granted the service in system settings. */
    fun isEnabledInSettings(context: Context): Boolean {
        if (AuraAccessibilityService.isConnected) return true
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull().orEmpty()
        val expected = ComponentName(context, AuraAccessibilityService::class.java)
        return enabledAccessibilityServicesContain(enabled, expected)
    }

    /**
     * Android's accessibility page, scrolled to Aura and with Aura highlighted.
     *
     * The page that holds only Aura's switch is reserved for app stores, so
     * this asks Settings to land on the list with Aura's row marked — the same
     * extras Settings' own search uses. Brands that ignore them still open the
     * list.
     */
    fun openAccessibilitySettings(context: Context): Boolean = runCatching {
        context.startActivity(accessibilityIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun accessibilityIntent(context: Context): Intent {
        val key = ComponentName(context, AuraAccessibilityService::class.java).flattenToString()
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", key)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
    }

    /** App info for Aura, where Android 13+ keeps "Allow restricted settings". */
    fun openAppInfo(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    /**
     * Where Android 13+'s "restricted setting" wall stands for this install.
     *
     * An app installed from a downloaded file may not have its accessibility
     * service switched on until the person unlocks it in App info — and the
     * unlock only appears after they have tried once and been refused. The
     * app-op behind it says which of those three moments we are in, so setup
     * can show the one step that applies instead of all three.
     */
    enum class Restriction { NONE, NOT_TRIED, CAN_UNLOCK }

    fun restriction(context: Context): Restriction {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Restriction.NONE
        val ops = context.getSystemService(android.app.AppOpsManager::class.java) ?: return Restriction.NONE
        val mode = runCatching {
            ops.unsafeCheckOpNoThrow(
                "android:access_restricted_settings",
                android.os.Process.myUid(),
                context.packageName,
            )
        }.getOrNull() ?: return Restriction.NONE
        return when (mode) {
            android.app.AppOpsManager.MODE_ERRORED -> Restriction.NOT_TRIED
            android.app.AppOpsManager.MODE_IGNORED -> Restriction.CAN_UNLOCK
            else -> Restriction.NONE
        }
    }

    /** Whether Aura may switch its own service on — granted once over adb. */
    fun canSelfEnable(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Switches screen control on without the person, when that was allowed.
     *
     * Only possible with WRITE_SECURE_SETTINGS, which no app can ask for — it
     * is given from a computer, once (`adb shell pm grant … WRITE_SECURE_SETTINGS`).
     * With it, coming back from the bank turns screen control straight back
     * on; without it this does nothing and the one-tap notification remains.
     */
    fun selfEnable(context: Context): Boolean {
        if (!canSelfEnable(context) || isEnabledInSettings(context)) return false
        val resolver = context.contentResolver
        val component = ComponentName(context, AuraAccessibilityService::class.java).flattenToString()
        return runCatching {
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val next = (current.split(':').filter { it.isNotBlank() } + component).distinct().joinToString(":")
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }

    /** Performs one of Android's global actions; false when the service is off or refused. */
    fun globalAction(action: GlobalAction): Boolean {
        val service = AuraAccessibilityService.instance ?: return false
        val code = when (action) {
            GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            GlobalAction.LOCK_SCREEN -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            }
        }
        return runCatching { service.performGlobalAction(code) }.getOrDefault(false)
    }
}

/** Exact component matching avoids release being mistaken for the debug app. */
internal fun enabledAccessibilityServicesContain(
    enabled: String,
    expected: ComponentName,
): Boolean = enabled
    .split(':')
    .mapNotNull(ComponentName::unflattenFromString)
    .any { it == expected }

enum class GlobalAction(val description: String) {
    BACK("Went back"),
    HOME("Went to the home screen"),
    RECENTS("Opened recent apps"),
    NOTIFICATIONS("Opened notifications"),
    QUICK_SETTINGS("Opened quick settings"),
    LOCK_SCREEN("Locked the screen"),
}
