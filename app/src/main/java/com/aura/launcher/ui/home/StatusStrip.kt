package com.aura.launcher.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aura.launcher.AuraApp
import com.aura.launcher.data.BatteryState
import com.aura.launcher.data.Weather
import com.aura.launcher.data.batteryFlow
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

/**
 * The line that replaces Android's status bar.
 *
 * Only what a person actually looks up there for: how much battery is left and
 * what it is doing outside. Signal strength and the Wi-Fi glyph are left to the
 * system strip, which is one swipe away — repeating them here in a different
 * typeface would be the same information twice.
 *
 * Text, with no graphic at all. The battery began as a small filling pill, and
 * a rounded track with a rounded fill inside it is a toggle switch — people
 * quite reasonably tried to tap it. Numbers cannot be mistaken for controls,
 * and the drawer already made the case that names beat glyphs here.
 */
@Composable
fun StatusStrip(
    app: AuraApp,
    showBattery: Boolean,
    showWeather: Boolean,
    /**
     * The date, folded into this line rather than sitting on one of its own.
     *
     * All three are the same kind of fact — small, current, read at a glance —
     * and stacking them made two thin lines where one does the job. Passed in
     * rather than formatted here because the home screen already owns the
     * format and updates it on the minute.
     */
    dateText: String? = null,
    /** Opens the day sheet. Null leaves the line as a readout. */
    onOpen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = Aura.colors

    val battery by produceState<BatteryState?>(null, showBattery) {
        if (!showBattery) return@produceState
        batteryFlow(app).collect { value = it }
    }

    // Refreshed only while the strip is in front of somebody. Left ungated it
    // woke up every five minutes for the rest of the day — and every fourth one
    // outlived the repository's cache and went to the network for a temperature
    // nobody was going to read.
    val lifecycleOwner = LocalLifecycleOwner.current
    val weather by produceState<Weather?>(null, showWeather, lifecycleOwner) {
        if (!showWeather) return@produceState
        // RESUMED deliberately restarts after the location-permission dialog.
        // STARTED remains active behind that dialog, so granting permission
        // previously left the weather blank until the next five-minute tick.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = app.weather.current()
                // The repository caches; this is only about noticing that the
                // cache went stale while the home screen stayed open. A blank
                // result retries sooner so a first location fix can arrive.
                delay(if (value == null) WEATHER_RETRY_MS else WEATHER_REFRESH_MS)
            }
        }
    }

    val anything = dateText != null ||
        (showBattery && battery != null) ||
        (showWeather && weather != null)

    AnimatedVisibility(
        visible = anything,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier
                )
                // Grows down, up and to the right only. Anything on the start
                // edge would push the line out of line with the clock above it,
                // which sits flush.
                .padding(end = 10.dp, top = 5.dp, bottom = 5.dp),
        ) {
            // Date first, because it is the one that never changes shape — the
            // eye lands in the same place whether or not there is weather.
            dateText?.let { date ->
                Text(text = date, fontSize = LINE_SIZE, color = colors.inkFaint)
                Spacer(Modifier.width(12.dp))
            }

            battery?.takeIf { showBattery }?.let { b ->
                val tint = when {
                    b.charging -> colors.sage
                    b.low -> colors.danger
                    else -> colors.inkFaint
                }
                if (b.charging) {
                    ChargingBolt(size = 11.dp, tint = tint)
                    Spacer(Modifier.width(3.dp))
                }
                Text(text = "${b.percent}%", fontSize = LINE_SIZE, color = tint)
                Spacer(Modifier.width(12.dp))
            }

            weather?.takeIf { showWeather }?.let { w ->
                SkyIcon(code = w.code, size = 15.dp)
                Spacer(Modifier.width(5.dp))
                Text(text = "${w.celsius}°", fontSize = LINE_SIZE, color = colors.inkFaint)
            }
        }
    }
}

/**
 * Bigger than the caption size this used to be.
 *
 * At label-small the line was something you had to decide to read. It carries
 * the date now, and it is the only text between the clock and the rest of the
 * screen — it should be legible on the way past, not on inspection.
 */
private val LINE_SIZE = 14.sp
private const val WEATHER_RETRY_MS = 60_000L
private const val WEATHER_REFRESH_MS = 5 * 60 * 1000L
