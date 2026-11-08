package com.aura.launcher.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Process
import android.os.UserHandle
import android.util.Log
import com.aura.launcher.control.SensitiveApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * One of an app's own shortcuts.
 *
 * Held as four fields rather than as the platform's `ShortcutInfo`, which is a
 * heavy object tied to a query that has already been answered — everything
 * needed to start one again is here.
 */
data class AppShortcut(
    val id: String,
    val packageName: String,
    val label: String,
    val user: UserHandle,
)

/** A launchable activity, as shown in the drawer. */
data class AppEntry(
    val label: String,
    val packageName: String,
    val className: String,
    val user: UserHandle,
    /** `ApplicationInfo.category`, or -1. Android's own idea of what this is. */
    val category: Int = -1,
    /** Invalidates a cached model-written profile when the app updates. */
    val versionCode: Long = 0L,
    /**
     * User-picked name to show in the launcher, if any.
     *
     * The real [label] is left as the system reports it: uninstall dialogs,
     * accessibility labels, and the assistant's screen-reading of another
     * app's package all need what Android knows the app as. Everything the
     * user themselves sees goes through [displayLabel].
     */
    val alias: String? = null,
) {
    /** Stable identity used for favourites, hidden apps and usage counters. */
    val key: String get() = "$packageName/$className"

    val component: ComponentName get() = ComponentName(packageName, className)

    /** What the launcher shows: alias if set, otherwise the system label. */
    val displayLabel: String
        get() = alias?.takeIf { it.isNotBlank() } ?: label

    /** Accent-free lowercase [displayLabel], used by every matcher. */
    val normalized: String by lazy { normalizeText(displayLabel) }

    /** Display label split into searchable tokens, plus the package's last segment. */
    val tokens: List<String> by lazy {
        buildList {
            addAll(normalized.split(' ', '-', '_', '.', '&', '/').filter { it.isNotBlank() })
            packageName.substringAfterLast('.').takeIf { it.length > 2 }?.let { add(normalizeText(it)) }
        }.distinct()
    }

    /** First letter used by the drawer's A–Z index. */
    val bucket: String
        get() = normalized.firstOrNull()
            ?.let { if (it.isLetter()) it.uppercaseChar().toString() else "#" }
            ?: "#"
}

/**
 * Owns the installed-app list and keeps it fresh across installs, removals and
 * profile changes.
 *
 * No icons: the drawer shows names only, so there is no loading, caching or
 * drawable bridge in the scroll path at all.
 */
class AppRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    /**
     * The current alias map, resolved fresh on every reload.
     *
     * Not a Flow on purpose — reload is what refreshes the list and it is what
     * refreshes the aliases, and squeezing an observer in the middle would only
     * add a race between the two.
     */
    private val aliasesProvider: () -> Map<String, String> = { emptyMap() },
) {

    private val launcherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val reloadLock = Mutex()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            scope.launch { reload() }
        }
    }

    private val launcherCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p: String?, u: UserHandle?) = trigger()
        override fun onPackageAdded(p: String?, u: UserHandle?) = trigger()
        override fun onPackageChanged(p: String?, u: UserHandle?) = trigger()
        override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = trigger()
        override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = trigger()
        private fun trigger() {
            scope.launch { reload() }
        }
    }

    fun start() {
        val la = launcherApps
        if (la != null) {
            runCatching { la.registerCallback(launcherCallback) }
                .onFailure { Log.w(TAG, "LauncherApps callback unavailable", it) }
        } else {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            runCatching { context.registerReceiver(packageReceiver, filter) }
        }
        scope.launch { reload() }
    }

    fun stop() {
        launcherApps?.let { runCatching { it.unregisterCallback(launcherCallback) } }
        runCatching { context.unregisterReceiver(packageReceiver) }
    }

    suspend fun reload() = reloadLock.withLock {
        _apps.value = withContext(Dispatchers.IO) { query() }
    }

    /**
     * Android's own category for a package, plus its version.
     *
     * The category is free, accurate metadata that covers every app ever
     * installed — it is what lets search understand "music" or "maps" before
     * the model has described anything.
     */
    private fun metadataOf(packageName: String): Pair<Int, Long> {
        val pm = context.packageManager
        val category = runCatching {
            pm.getApplicationInfo(packageName, 0).category
        }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)
        val version = runCatching {
            val info = pm.getPackageInfo(packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)
        return category to version
    }

    private fun query(): List<AppEntry> {
        val self = context.packageName
        val aliases = aliasesProvider()
        val result = mutableListOf<AppEntry>()
        // One lookup per package, not per activity: apps with several launcher
        // entries would otherwise hit the package manager repeatedly.
        val metadata = mutableMapOf<String, Pair<Int, Long>>()

        val la = launcherApps
        if (la != null) {
            val profiles = runCatching { la.profiles }.getOrNull()
                ?: listOf(Process.myUserHandle())
            for (user in profiles) {
                val activities = runCatching { la.getActivityList(null, user) }
                    .getOrElse { emptyList() }
                for (a in activities) {
                    val label = runCatching { a.label?.toString() }.getOrNull().orEmpty()
                    if (label.isBlank()) continue
                    val pkg = a.componentName.packageName
                    if (pkg == self) continue
                    val (category, version) = metadata.getOrPut(pkg) { metadataOf(pkg) }
                    val entryKey = "$pkg/${a.componentName.className}"
                    result += AppEntry(
                        label = label,
                        packageName = pkg,
                        className = a.componentName.className,
                        user = user,
                        category = category,
                        versionCode = version,
                        alias = aliases[entryKey],
                    )
                }
            }
        }

        if (result.isEmpty()) {
            // Fallback for devices where LauncherApps is unavailable to us.
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved: List<ResolveInfo> =
                runCatching { context.packageManager.queryIntentActivities(intent, 0) }
                    .getOrElse { emptyList() }
            val me = Process.myUserHandle()
            for (r in resolved) {
                val ai = r.activityInfo ?: continue
                if (ai.packageName == self) continue
                val label = runCatching { r.loadLabel(context.packageManager).toString() }
                    .getOrNull().orEmpty()
                if (label.isBlank()) continue
                val (category, version) = metadata.getOrPut(ai.packageName) { metadataOf(ai.packageName) }
                val entryKey = "${ai.packageName}/${ai.name}"
                result += AppEntry(
                    label = label,
                    packageName = ai.packageName,
                    className = ai.name,
                    user = me,
                    category = category,
                    versionCode = version,
                    alias = aliases[entryKey],
                )
            }
        }

        return result
            .distinctBy { it.key }
            // Sort by what the user sees, not by the system label. An alias
            // "Trabalho" for Microsoft Teams belongs under T, not M.
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayLabel })
    }

    /** Launches [entry], returning false when the activity has gone away. */
    /** [launch], opening out of the point the finger last touched. */
    fun launchFromTouch(entry: AppEntry): Boolean = launch(
        entry,
        com.aura.launcher.ui.LaunchReveal.bounds(),
        com.aura.launcher.ui.LaunchReveal.options(),
    )

    fun launch(
        entry: AppEntry,
        sourceBounds: android.graphics.Rect? = null,
        /** ActivityOptions as a bundle — the opening animation. See LaunchReveal. */
        options: android.os.Bundle? = null,
    ): Boolean {
        // Before the app starts, not after. A bank checks for accessibility
        // services as it comes up, so giving the permission back once it is
        // already on screen is too late to stop the wall.
        SensitiveApps.pauseIfSensitive(context, entry.packageName)

        val la = launcherApps
        if (la != null) {
            val ok = runCatching {
                la.startMainActivity(entry.component, entry.user, sourceBounds, options)
            }.isSuccess
            if (ok) return true
        }
        return runCatching {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(entry.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(intent, options)
            true
        }.getOrElse {
            runCatching {
                val i = context.packageManager.getLaunchIntentForPackage(entry.packageName)
                    ?: return@getOrElse false
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
    }

    /**
     * The app's own shortcuts — "New message", "Selfie", "Scan a code".
     *
     * Published by the app itself, either in its manifest or at runtime, and
     * only readable by whichever launcher is currently the home app. That is
     * the important caveat: on a phone where Aura is installed but not set as
     * the launcher this returns nothing, which is correct rather than broken,
     * and is why the caller shows the section only when there is something in
     * it rather than an empty heading with an explanation nobody asked for.
     *
     * Pinned shortcuts are asked for alongside the other two kinds so a
     * shortcut the user pinned from another launcher still turns up here.
     */
    fun shortcutsFor(entry: AppEntry, limit: Int = MAX_SHORTCUTS): List<AppShortcut> {
        val la = launcherApps ?: return emptyList()
        if (!runCatching { la.hasShortcutHostPermission() }.getOrDefault(false)) return emptyList()

        val query = LauncherApps.ShortcutQuery()
            .setPackage(entry.packageName)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )

        val found = runCatching { la.getShortcuts(query, entry.user) }.getOrNull().orEmpty()
        return found
            .filter { it.isEnabled }
            // Rank is the app's own idea of which matters most; it only orders
            // manifest shortcuts, so ties fall back to the label.
            .sortedWith(compareBy({ it.rank }, { it.shortLabel?.toString().orEmpty() }))
            .mapNotNull { info ->
                val label = (info.shortLabel ?: info.longLabel)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                AppShortcut(id = info.id, packageName = info.`package`, label = label, user = info.userHandle)
            }
            .distinctBy { it.label.lowercase() }
            .take(limit.coerceIn(1, MAX_SHORTCUTS))
    }

    /** Starts one of [shortcutsFor]'s entries. False when the app withdrew it. */
    @Volatile private var everyShortcut: Pair<Long, List<Pair<AppShortcut, String>>>? = null

    /**
     * Every shortcut every app publishes, with the app's name — what the
     * universal search looks through. Read once a minute at most: it is a
     * binder call per profile, and the search runs on each keystroke.
     */
    fun allShortcuts(now: Long = System.currentTimeMillis()): List<Pair<AppShortcut, String>> {
        everyShortcut?.let { (at, list) -> if (now - at < SHORTCUT_CACHE_MS) return list }
        val la = launcherApps ?: return emptyList()
        if (!runCatching { la.hasShortcutHostPermission() }.getOrDefault(false)) return emptyList()
        val labels = _apps.value.associate { it.packageName to it.displayLabel }
        val users = _apps.value.map { it.user }.distinct()
        val query = LauncherApps.ShortcutQuery().setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
        )
        val found = users.flatMap { user ->
            runCatching { la.getShortcuts(query, user) }.getOrNull().orEmpty()
        }.filter { it.isEnabled && it.`package` in labels }.mapNotNull { info ->
            val label = (info.shortLabel ?: info.longLabel)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            AppShortcut(id = info.id, packageName = info.`package`, label = label, user = info.userHandle) to
                labels.getValue(info.`package`)
        }.distinctBy { it.first.packageName + "/" + it.first.label.lowercase() }
        everyShortcut = now to found
        return found
    }

    fun launchShortcut(shortcut: AppShortcut, sourceBounds: android.graphics.Rect? = null): Boolean {
        val la = launcherApps ?: return false
        SensitiveApps.pauseIfSensitive(context, shortcut.packageName)
        return runCatching {
            la.startShortcut(shortcut.packageName, shortcut.id, sourceBounds, null, shortcut.user)
            true
        }.getOrElse {
            Log.w(TAG, "Could not start shortcut ${shortcut.id}", it)
            false
        }
    }

    fun openAppInfo(entry: AppEntry) {
        runCatching {
            launcherApps?.startAppDetailsActivity(entry.component, entry.user, null, null)
                ?: context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${entry.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
        }
    }

    /**
     * Asks the system to uninstall [entry], returning false if it would not.
     *
     * Three ways in, because no single one covers every phone. The documented
     * action is tried first; `ACTION_DELETE` is what most launchers use and what
     * some OEM installers actually register for; and when neither resolves —
     * a system app, a work profile, a device with the installer locked down —
     * the app's own settings page is the only remaining route, and taking the
     * user there beats a menu item that does nothing at all.
     */
    fun requestUninstall(entry: AppEntry): Boolean {
        val uri = android.net.Uri.parse("package:${entry.packageName}")
        val attempts = listOf(
            @Suppress("DEPRECATION")
            Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri),
            Intent(Intent.ACTION_DELETE, uri),
        )
        for (intent in attempts) {
            val started = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrElse {
                Log.w(TAG, "Uninstall via ${intent.action} refused for ${entry.packageName}", it)
                false
            }
            if (started) return true
        }
        openAppInfo(entry)
        return false
    }

    private companion object {
        const val TAG = "AppRepository"

        /**
         * The most an app's shortcuts may ever take, whatever the setting says.
         *
         * A few apps publish a dozen dynamic ones, and a long-press sheet that
         * scrolls has stopped being a shortcut to anything.
         */
        const val MAX_SHORTCUTS = 8
        const val SHORTCUT_CACHE_MS = 60_000L
    }
}
