package com.aura.launcher.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class BatteryState(
    val percent: Int,
    val charging: Boolean,
) {
    /** Below this, the number stops being background information. */
    val low: Boolean get() = percent <= 20 && !charging
}

/**
 * Battery level, without polling.
 *
 * `ACTION_BATTERY_CHANGED` is a sticky broadcast, so registering for it hands
 * over the current state immediately and then again on every change. There is
 * nothing to poll and nothing to wake up.
 */
fun batteryFlow(context: Context): Flow<BatteryState> = callbackFlow {
    fun read(intent: Intent?): BatteryState? {
        val i = intent ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return BatteryState(
            percent = (level * 100f / scale).toInt().coerceIn(0, 100),
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            read(intent)?.let { trySend(it) }
        }
    }

    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    // registerReceiver returns the sticky intent, which is the current state.
    val sticky = context.registerReceiver(receiver, filter)
    read(sticky)?.let { trySend(it) }

    awaitClose { runCatching { context.unregisterReceiver(receiver) } }
}.distinctUntilChanged()
