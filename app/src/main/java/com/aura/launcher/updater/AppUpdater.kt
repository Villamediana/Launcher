package com.aura.launcher.updater

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The description of one published release, exactly as it appears in
 * `dist/latest.json` alongside the APK it points at.
 *
 * Deliberately simple: no signing manifest, no delta patches, no cryptographic
 * chain of trust — the checksum here is against tampering in transit and cache
 * corruption, not against an attacker who owns the GitHub repository. If they
 * do, the APK download URL is theirs to change anyway.
 */
@Serializable
data class ReleaseManifest(
    val versionCode: Int,
    val versionName: String,
    /** One or two lines about what changed. Rendered as plain text. */
    val notes: String = "",
    /** Direct download URL to the APK. Usually a `raw.githubusercontent.com` link. */
    val apk: String,
    /** Hex SHA-256 of the APK. Required before any download is accepted. */
    val sha256: String = "",
)

sealed interface UpdaterState {
    data object Idle : UpdaterState

    data object Checking : UpdaterState

    /** The check ran and nothing newer is out there. */
    data class UpToDate(val current: Int) : UpdaterState

    /** A newer release exists. */
    data class Available(val current: Int, val manifest: ReleaseManifest) : UpdaterState

    /** Downloading the APK for [manifest]. */
    data class Downloading(
        val manifest: ReleaseManifest,
        val bytes: Long,
        val total: Long,
    ) : UpdaterState {
        val fraction: Float get() = if (total <= 0) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Download finished, verifying the checksum. Runs briefly. */
    data class Verifying(val manifest: ReleaseManifest) : UpdaterState

    /**
     * The APK is verified and made ready for the system installer. On Android
     * 10+ it is copied to Downloads; older systems use the private staging
     * copy. Nothing is installed automatically.
     */
    data class Downloaded(
        val manifest: ReleaseManifest,
    ) : UpdaterState

    /** Handed to Android's installer; the app restarts when it is done. */
    data object Installing : UpdaterState

    data class Failed(val message: String) : UpdaterState
}

/**
 * Self-update, driven by a JSON manifest committed alongside the APK.
 *
 * The flow is: fetch [MANIFEST_URL], compare its versionCode against ours,
 * download the APK, checksum it, then make the verified file available to the
 * system installer and hand the user a link to open it.
 * Nothing installs on its own — the user tapping Open (or later tapping
 * the file in their file manager) is the explicit action that starts the
 * system install prompt. Silent installs are a Play Store privilege we
 * do not have, and would not want; the confirmation is what makes the
 * boundary visible.
 *
 * State is exposed as a [StateFlow] the settings screen collects, so a check
 * or a download that outlives a screen recomposition still shows its progress
 * when the user comes back.
 */
class AppUpdater(private val context: Context) {

    private val _state = MutableStateFlow<UpdaterState>(UpdaterState.Idle)
    val state: StateFlow<UpdaterState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // The APK itself is small (a few MB) but there is no reason to cap
        // the whole call — a slow tether should not fail a working transfer.
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    /**
     * Where the APK downloads to.
     *
     * External files dir is app-private but sits outside the cache the system
     * can reclaim under pressure — an install prompt open for a minute must
     * not have its APK evaporated from under it. Also, empirically, some
     * OEM package installers refuse to parse an APK served from `cacheDir`
     * over a FileProvider ("problema ao analisar o pacote"). Same authority,
     * different backing directory.
     */
    private fun apkFile(): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "updates").apply { mkdirs() }
        return File(dir, APK_FILE_NAME)
    }

    fun reset() {
        _state.value = UpdaterState.Idle
    }

    /**
     * Reads the manifest and compares versionCode against ours.
     *
     * A missing manifest, a network failure or a malformed reply all land in
     * [UpdaterState.Failed] — the user needs to see the reason, and a silent
     * "up to date" would lie in every one of those cases.
     */
    suspend fun check(currentVersionCode: Int) = withContext(Dispatchers.IO) {
        _state.value = UpdaterState.Checking
        UpdaterDiagnostics.log("check", "Fetching manifest from $MANIFEST_URL (installed=$currentVersionCode)")
        val manifest = fetchManifest()
            .getOrElse {
                UpdaterDiagnostics.log("check", "Manifest fetch failed: ${it.message ?: it::class.java.simpleName}")
                _state.value = UpdaterState.Failed(it.message ?: "Could not reach the update server")
                return@withContext
            }

        UpdaterDiagnostics.log(
            "check",
            "Manifest OK — versionCode=${manifest.versionCode}, name=${manifest.versionName}, sha256=${manifest.sha256.take(12)}…",
        )

        _state.value = if (manifest.versionCode > currentVersionCode) {
            UpdaterDiagnostics.log("check", "Newer release available")
            UpdaterState.Available(currentVersionCode, manifest)
        } else {
            UpdaterDiagnostics.log("check", "Already on the latest")
            UpdaterState.UpToDate(currentVersionCode)
        }
    }

    /**
     * Downloads and verifies the APK.
     *
     * Not resumable on purpose: the APK is small enough to redo from scratch
     * on failure, and range-request bookkeeping for something a person retries
     * with one tap would be overkill. The multi-gigabyte model has resume for a
     * good reason; an APK measured in tens of megabytes does not.
     */
    suspend fun download(manifest: ReleaseManifest) = withContext(Dispatchers.IO) {
        try {
            if (!validApkUrl(manifest.apk)) {
                _state.value = UpdaterState.Failed("The update manifest contains an untrusted APK URL.")
                return@withContext
            }
            if (!SHA256.matches(manifest.sha256)) {
                _state.value = UpdaterState.Failed("The update manifest has no valid SHA-256 checksum.")
                return@withContext
            }
            val file = apkFile()
            UpdaterDiagnostics.log(
                "download",
                "Target ${file.absolutePath} — pre-existing=${file.exists()}, free=${file.parentFile?.freeSpace ?: -1} bytes",
            )
            if (file.exists()) file.delete()

            val request = plainRequest(manifest.apk).build()
            UpdaterDiagnostics.log("download", "GET ${manifest.apk}")

            client.newCall(request).execute().use { response ->
                UpdaterDiagnostics.log(
                    "download",
                    "HTTP ${response.code} · content-length=${response.header("content-length")} · content-type=${response.header("content-type")}",
                )
                if (!response.isSuccessful) {
                    _state.value = UpdaterState.Failed("HTTP ${response.code}: ${response.message}")
                    return@withContext
                }
                val body = response.body ?: run {
                    UpdaterDiagnostics.log("download", "Empty response body")
                    _state.value = UpdaterState.Failed("Empty response from the server")
                    return@withContext
                }

                val total = body.contentLength()
                _state.value = UpdaterState.Downloading(manifest, 0L, total)

                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            read += n
                            // Emit every 128 KB so the progress bar moves
                            // without allocating a state object per chunk.
                            if (read - lastEmit >= 128 * 1024L) {
                                lastEmit = read
                                _state.value = UpdaterState.Downloading(manifest, read, total)
                            }
                        }
                        _state.value = UpdaterState.Downloading(manifest, read, if (total > 0) total else read)
                        UpdaterDiagnostics.log(
                            "download",
                            "Wrote $read bytes (expected content-length=$total)",
                        )
                    }
                }
            }

            UpdaterDiagnostics.log(
                "download",
                "APK on disk: size=${file.length()} bytes, readable=${file.canRead()}",
            )

            _state.value = UpdaterState.Verifying(manifest)
            val actual = sha256(file)
            UpdaterDiagnostics.log(
                "verify",
                "expected=${manifest.sha256.take(16)}… actual=${actual.take(16)}… match=${actual.equals(manifest.sha256, ignoreCase = true)}",
            )
            if (!actual.equals(manifest.sha256, ignoreCase = true)) {
                file.delete()
                _state.value = UpdaterState.Failed(
                    "Checksum mismatch. The download did not match what was published — try again."
                )
                return@withContext
            }

            // Ask the OS to parse the APK ourselves so a broken or mismatched
            // package is caught here rather than by the installer with a bare
            // "problema ao analisar o pacote".
            preflightParse(file, manifest).getOrElse {
                file.delete()
                _state.value = UpdaterState.Failed(it.message ?: "The downloaded APK is invalid")
                return@withContext
            }

            // Verified and kept where it was streamed to. Installing goes
            // through a PackageInstaller session — see [install] — and the
            // copy in Downloads is only made if that ever fails.
            lastManifest = manifest
            _state.value = UpdaterState.Downloaded(manifest)
        } catch (e: Exception) {
            Log.w(TAG, "Update download failed", e)
            UpdaterDiagnostics.log(
                "download",
                "Exception: ${e::class.java.simpleName} — ${e.message ?: "no message"}",
            )
            _state.value = UpdaterState.Failed(e.message ?: "Download failed")
        }
    }

    /**
     * Runs Android's own APK parser against the downloaded file and verifies
     * that its identity matches the manifest and this installed application.
     */
    private fun preflightParse(file: File, manifest: ReleaseManifest): Result<Unit> = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Android could not parse the downloaded APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val installedCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            installed.versionCode.toLong()
        }
        val newCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        UpdaterDiagnostics.log(
            "preflight",
            "parsed OK — package=${info.packageName}, versionCode=$newCode, versionName=${info.versionName}, installedCode=$installedCode",
        )
        require(info.packageName == context.packageName) {
            "The downloaded APK is for '${info.packageName}', not '${context.packageName}'."
        }
        require(newCode == manifest.versionCode.toLong()) {
            "The APK version ($newCode) does not match the update manifest (${manifest.versionCode})."
        }
        require(installedCode < 0L || newCode > installedCode) {
            "The downloaded APK is not newer than the installed version."
        }
        val installedSigners = signerDigests(installed)
        val downloadedSigners = signerDigests(info)
        require(installedSigners.isNotEmpty() && downloadedSigners.any(installedSigners::contains)) {
            "The downloaded APK was not signed by Launcher's update key."
        }
    }

    private fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory
        } else {
            info.signatures
        }
        return signatures.orEmpty().mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * URI of the last APK saved into Downloads. Cached so [openDownloaded]
     * can hand it to Android's viewer straight away, without the app
     * having to walk MediaStore for a name it just wrote.
     */
    @Volatile
    private var lastDownloadedUri: Uri? = null

    /**
     * Persist the URI so [UpdateCleanupReceiver] can delete the file once
     * the installer succeeds and Android relaunches the app. The receiver
     * runs in a fresh process, so a plain in-memory cache would not do.
     */
    private fun rememberForCleanup(uri: Uri) {
        context.getSharedPreferences(UpdateCleanupReceiver.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(UpdateCleanupReceiver.KEY_DOWNLOAD_URI, uri.toString())
            .apply()
    }

    /**
     * Makes the verified APK available to the system installer.
     *
     * The previous flow launched the system installer automatically the
     * moment the download landed, and some OEM installers responded with
     * an "unknown source" dialog against valid APKs. A plain file in
     * Downloads that the user taps themselves sidesteps that entirely —
     * same install prompt, different entry point.
     *
     * Uses MediaStore.Downloads on API 29+ (no permission needed). Android
     * 8/9 install directly from the already verified app-private staging file;
     * exposing public storage there required a dangerous permission and made
     * the APK replaceable between verification and the user's tap.
     */
    private fun makeAvailable(manifest: ReleaseManifest, source: File): Result<Unit> {
        val safeVersion = manifest.versionName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(48)
            .ifBlank { manifest.versionCode.toString() }
        val displayName = "aura-$safeVersion.apk"
        UpdaterDiagnostics.log("export", "Saving APK to Downloads as $displayName")

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                // A second download before installing used to overwrite the
                // cleanup pointer and strand the previous APK in Downloads.
                forgetPreviousPublicExport()
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore refused to create the entry")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    } ?: error("Could not open the output stream")
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    check(resolver.update(uri, values, null, null) == 1) {
                        "Could not finish the Downloads entry"
                    }
                    lastDownloadedUri = uri
                    rememberForCleanup(uri)
                    UpdaterDiagnostics.log("export", "Saved to $uri")
                } catch (failure: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw failure
                }
            } else {
                // The source already lives under one of the narrow updates/
                // roots declared by update_paths.xml (external files normally,
                // internal files when external storage is unavailable).
                val fileUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.updates",
                    source,
                )
                lastDownloadedUri = fileUri
                rememberForCleanup(Uri.fromFile(source))
                UpdaterDiagnostics.log("export", "Ready at ${source.absolutePath}")
            }
            Unit
        }
    }

    private fun forgetPreviousPublicExport() {
        val prefs = context.getSharedPreferences(UpdateCleanupReceiver.PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(UpdateCleanupReceiver.KEY_DOWNLOAD_URI, null)
            ?.let(Uri::parse)
        if (previous?.scheme == "content") {
            runCatching { context.contentResolver.delete(previous, null, null) }
        }
        prefs.edit().remove(UpdateCleanupReceiver.KEY_DOWNLOAD_URI).apply()
        lastDownloadedUri = null
    }

    /**
     * Opens the APK we just saved so the user can install it.
     *
     * ACTION_VIEW with the package-archive mime type routes to the same
     * installer as tapping the file in a file manager — but this time
     * the intent is the user's own action, so the "unknown source"
     * checks apply to whatever app started the view, not to Aura.
     */
    @Volatile
    private var lastManifest: ReleaseManifest? = null

    /**
     * Installs the verified APK through a PackageInstaller session.
     *
     * The old path saved the APK to Downloads and handed it to whatever opened
     * .apk files — a file manager, a browser — which is exactly the shape of
     * install Play Protect and "unknown sources" are suspicious of, and it made
     * an update four screens long. A session is how stores install: Android
     * shows one confirmation, and from the update after that, with Aura now
     * the installer of record of itself, Android 12+ lets it update with no
     * prompt at all (USER_ACTION_NOT_REQUIRED). Falls back to the Downloads
     * copy if the session cannot be made.
     */
    fun install(): Boolean {
        val file = apkFile()
        if (!file.isFile) {
            UpdaterDiagnostics.log("install", "No staged APK")
            return false
        }
        val ok = runCatching { commitSession(file) }
            .onFailure { UpdaterDiagnostics.log("install", "Session failed: ${it::class.java.simpleName} ${it.message}") }
            .isSuccess
        if (ok) return true
        val manifest = lastManifest ?: return false
        return makeAvailable(manifest, file).isSuccess && openDownloaded()
    }

    private fun commitSession(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setRequestUpdateOwnership(true)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("aura.apk", 0, file.length()).use { out ->
                        input.copyTo(out, 256 * 1024)
                        session.fsync(out)
                    }
                }
                val status = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION_STATUS),
                    // Mutable: the installer writes the outcome into it.
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
                )
                UpdaterDiagnostics.log("install", "Committing session $id (${file.length()} bytes)")
                _state.value = UpdaterState.Installing
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** What the session reported, when it was not success (success restarts the app). */
    fun installFailed(message: String) {
        _state.value = UpdaterState.Failed(message)
    }

    /**
     * The once-a-day look, from the home screen coming back.
     *
     * Checks quietly and, when there is something newer, downloads and
     * verifies it in the background and puts up one notification: "ready —
     * tap to install". Nothing installs without that tap.
     */
    suspend fun checkInBackground(currentVersionCode: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_AUTO_CHECK, 0L) < AUTO_CHECK_EVERY_MS) return
        if (_state.value !is UpdaterState.Idle && _state.value !is UpdaterState.UpToDate) return
        prefs.edit().putLong(KEY_LAST_AUTO_CHECK, now).apply()
        check(currentVersionCode)
        val available = _state.value as? UpdaterState.Available ?: return
        download(available.manifest)
        val ready = _state.value as? UpdaterState.Downloaded ?: return
        UpdateInstallReceiver.notifyReady(context, ready.manifest.versionName)
    }

    fun openDownloaded(): Boolean {
        val uri = lastDownloadedUri ?: run {
            UpdaterDiagnostics.log("open", "No downloaded APK URI cached")
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        UpdaterDiagnostics.log("open", "Launching viewer for $uri")
        return runCatching { context.startActivity(intent) }
            .onFailure {
                UpdaterDiagnostics.log("open", "startActivity threw ${it::class.java.simpleName}: ${it.message}")
            }
            .isSuccess
    }

    private fun fetchManifest(): Result<ReleaseManifest> = runCatching {
        client.newCall(plainRequest(MANIFEST_URL).build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) error("Empty manifest")
            json.decodeFromString<ReleaseManifest>(body)
        }
    }

    /**
     * A plain Request builder — no auth. The manifest and the APK both live
     * in a public repository specifically so anonymous clients can read
     * them, and adding a token here would be extra surface for no benefit.
     *
     * Cache-Control: no-cache is sent because GitHub's CDN caches
     * raw.githubusercontent.com objects for five minutes at a time. That
     * is fine for the manifest but bad for the APK: same URL, different
     * bytes each release, and a stale copy is exactly the "downloaded
     * but the installer refuses it" story.
     */
    private fun plainRequest(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("User-Agent", "Launcher-Updater/1.0 (Android)")
            .header("Cache-Control", "no-cache")

    private fun validApkUrl(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.host == "raw.githubusercontent.com" &&
            uri.port == -1 &&
            uri.encodedQuery == null &&
            uri.fragment == null &&
            uri.path == "/Villamediana/LauncherAPK/main/launcher.apk"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "AppUpdater"
        const val PREFS = "updater"
        const val KEY_LAST_AUTO_CHECK = "last_auto_check"
        const val AUTO_CHECK_EVERY_MS = 20 * 60 * 60 * 1000L
        const val APK_FILE_NAME = "aura-update.apk"
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")

        /**
         * Where the release manifest lives. `raw.githubusercontent.com` because
         * that is the URL that always answers with the file bytes, without the
         * HTML wrapper the browser view returns for repository URLs.
         */
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/Villamediana/LauncherAPK/main/latest.json"
    }
}
