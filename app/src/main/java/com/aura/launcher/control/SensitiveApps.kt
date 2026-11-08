package com.aura.launcher.control

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Steps out of the way of banking apps.
 *
 * Banks refuse to run while an accessibility service is enabled — Nubank puts
 * up a "suspicious app, uninstall it" wall and closes — and they are right to.
 * A service that can read every screen and tap every button is exactly the
 * shape of the attack they are defending against, and no amount of good
 * intentions on this side is visible from theirs.
 *
 * So Aura gives the permission up rather than argue. When a banking app is
 * about to come to the front, the accessibility service disables itself, and
 * the bank finds a clean phone. Nobody wants an assistant driving their bank
 * anyway.
 *
 * Turning it back on needs the user, because Android has no way for an app to
 * re-grant itself an accessibility service — which is the whole point of the
 * grant. [wasAutoPaused] is what lets the launcher offer that as one tap
 * instead of leaving the feature quietly dead.
 */
object SensitiveApps {

    private const val PREFS = "aura_screen_control"
    private const val KEY_AUTO_PAUSED = "auto_paused_for_sensitive_app"
    private const val KEY_LAST_PACKAGE = "auto_paused_package"

    private const val KEY_USER_MARKED = "user_marked_packages"

    /**
     * Banks known to put up the wall, as a starting point.
     *
     * Android has no "finance" application category, so there is nothing an app
     * declares that this could read instead — the list is the only signal
     * available up front. It is therefore a starting point and not the
     * mechanism: anything missing can be marked by holding the app in the
     * drawer, and that mark is what actually scales.
     *
     * Deliberately fragments rather than exact names. A bank ships
     * `com.nu.production`, `com.nu.production.beta` and three regional
     * variants, and missing one means the wall comes back.
     */
    private val FINANCE_HINTS = listOf(
        "nubank", "com.nu.", "santander", "itau", "bradesco", "bancodobrasil",
        "caixa", "bancointer", "c6bank", "picpay", "mercadopago", "banrisul",
        "sicredi", "sicoob", "safra", "original", "neon", "pagbank", "pagseguro",
        "willbank", "next.", "btg", "xpi", "nuinvest", "binance", "revolut",
        "paypal", "wise", "chase", "bankofamerica", "wellsfargo", "bbva",
    )

    fun isSensitive(context: Context, packageName: String?): Boolean {
        val name = packageName?.lowercase() ?: return false
        if (name == context.packageName) return false
        if (packageName in userMarked(context)) return true
        return FINANCE_HINTS.any { it in name }
    }

    /** Packages the user has marked themselves, by holding the app. */
    fun userMarked(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_USER_MARKED, emptySet()).orEmpty()

    /**
     * Adds or removes a package from the user's own list.
     *
     * Kept in SharedPreferences rather than the app's DataStore because the
     * accessibility callback that reads it runs on a binder thread and cannot
     * suspend — and it has to answer before the other app draws its first
     * frame.
     */
    fun toggleUserMarked(context: Context, packageName: String) {
        val next = userMarked(context).toMutableSet()
        if (!next.add(packageName)) next.remove(packageName)
        prefs(context).edit().putStringSet(KEY_USER_MARKED, next).apply()
    }

    /** True when the built-in list already covers it, so the row can say so. */
    fun isKnownBank(packageName: String): Boolean =
        FINANCE_HINTS.any { it in packageName.lowercase() }

    /**
     * Called just before a banking app reaches the front.
     *
     * Returns whether anything was actually given up, so a caller can say so.
     */
    fun pauseIfSensitive(context: Context, packageName: String?): Boolean {
        if (!ScreenController.isReady) return false
        if (!isSensitive(context, packageName)) return false

        Log.i(TAG, "Pausing screen control for $packageName")
        prefs(context).edit()
            .putBoolean(KEY_AUTO_PAUSED, true)
            .putString(KEY_LAST_PACKAGE, packageName)
            // commit, not apply: the process may be about to lose focus, and a
            // flag that never reached disk is a feature that silently stays off
            // with nothing offering to bring it back.
            .commit()

        // Before disabling, because after it this process may have nothing left
        // running to post anything with.
        ScreenControlPausedNotification.show(
            context,
            pausedForLabel(context) ?: packageName.orEmpty(),
        )

        AuraAccessibilityService.instance?.let { runCatching { it.disableSelf() } }
        return true
    }

    /** True when screen control was given up for a bank and never restored. */
    fun wasAutoPaused(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_PAUSED, false) && !ScreenController.isReady

    /** The app it stepped aside for, for the message offering to resume. */
    fun pausedFor(context: Context): String? =
        prefs(context).getString(KEY_LAST_PACKAGE, null)

    /** Label of that app, falling back to the package name. */
    fun pausedForLabel(context: Context): String? {
        val pkg = pausedFor(context) ?: return null
        return runCatching {
            val pm: PackageManager = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    fun clearAutoPause(context: Context) {
        prefs(context).edit().putBoolean(KEY_AUTO_PAUSED, false).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "SensitiveApps"
}
