package com.aura.launcher.data

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Where the phone is, as far as the system already knows.
 *
 * Deliberately never asks for a fresh fix. A launcher requesting GPS every time
 * something needs a coordinate is a battery problem, and for "what is near me"
 * or "how do I get there" the last known position is already far more precise
 * than the question. Extracted here because the weather line, the map and the
 * places search all need the same answer and should not each have their own idea
 * of how to get it.
 */
object Locator {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The freshest position any enabled provider has on file, or null. */
    fun lastKnown(context: Context): Location? {
        if (!hasPermission(context)) return null
        return runCatching {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return null
            manager.getProviders(true)
                .mapNotNull { provider ->
                    @Suppress("MissingPermission")
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    /**
     * A usable position, asking Android for one only when its stored fix is old
     * or absent.
     *
     * Fresh installs and phones just restarted commonly have no last-known
     * position even though permission and Location are both on. Weather used
     * to stay blank in that state forever because nothing here ever requested
     * the first fix. The compatibility API performs one bounded request and is
     * cancelled with the coroutine; it does not leave ongoing GPS updates
     * behind.
     */
    suspend fun currentOrLastKnown(context: Context): Location? {
        if (!hasPermission(context)) return null
        val previous = lastKnown(context)
        val age = previous?.let { System.currentTimeMillis() - it.time }
        if (previous != null && age != null && age in 0..FRESH_LOCATION_MS) return previous

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return previous
        if (!LocationManagerCompat.isLocationEnabled(manager)) return previous

        val enabledProviders = manager.getProviders(true)
        // Network is the natural match for coarse permission and usually
        // answers without waking GPS. Some devices expose only a fused provider
        // here, so fall through to the first active non-passive source.
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in enabledProviders }
            ?: enabledProviders.firstOrNull { it != LocationManager.PASSIVE_PROVIDER }
            ?: return previous

        val current = withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationSignal()
                continuation.invokeOnCancellation { cancellation.cancel() }
                runCatching {
                    @Suppress("MissingPermission")
                    LocationManagerCompat.getCurrentLocation(
                        manager,
                        provider,
                        cancellation,
                        ContextCompat.getMainExecutor(context),
                    ) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        // Keeping the older fix as a fallback preserves useful weather during
        // a temporary provider failure, while still making a real attempt to
        // replace it whenever it has gone stale.
        return current ?: previous
    }

    private const val FRESH_LOCATION_MS = 2 * 60 * 60 * 1000L
    private const val CURRENT_LOCATION_TIMEOUT_MS = 8_000L
}
