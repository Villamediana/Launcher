package com.aura.launcher.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Deletes the update APK from Downloads once Android has finished
 * installing it.
 *
 * The system broadcasts [Intent.ACTION_MY_PACKAGE_REPLACED] to a
 * freshly-updated app: same signature, higher versionCode, right after
 * the installer succeeds. This receiver is registered for that one
 * intent, reads the URI [AppUpdater] persisted before firing the
 * viewer, and deletes both the public copy in Downloads and the
 * private staging file in the app's external files directory.
 *
 * Deliberately quiet on failure. A missing pref or a delete that
 * cannot find the file is not something the user needs to know
 * about — the space involved is a few megabytes and the tidy-up is
 * best-effort by design.
 */
class UpdateCleanupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "Package replaced — cleaning up the downloaded APK")

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedUri = prefs.getString(KEY_DOWNLOAD_URI, null)?.let(Uri::parse)
        if (storedUri != null) {
            when (storedUri.scheme) {
                "content" -> runCatching {
                    context.contentResolver.delete(storedUri, null, null)
                }.onFailure { Log.w(TAG, "Could not delete $storedUri", it) }
                "file" -> runCatching {
                    storedUri.path?.let { File(it).takeIf(File::exists)?.delete() }
                }.onFailure { Log.w(TAG, "Could not delete $storedUri", it) }
                else -> Log.w(TAG, "Unknown URI scheme: $storedUri")
            }
            prefs.edit().remove(KEY_DOWNLOAD_URI).apply()
        }

        // The staging copy — the file we streamed the download into
        // before mirroring it to Downloads. Independent path, so even
        // if the Downloads delete failed we still reclaim this side.
        runCatching {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val staged = File(File(root, "updates"), "aura-update.apk")
            if (staged.exists()) staged.delete()
        }
    }

    companion object {
        private const val TAG = "UpdateCleanup"

        internal const val PREFS = "updater_cleanup"
        internal const val KEY_DOWNLOAD_URI = "last_download_uri"
    }
}
