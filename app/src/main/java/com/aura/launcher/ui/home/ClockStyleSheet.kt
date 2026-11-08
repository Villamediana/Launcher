package com.aura.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.R
import com.aura.launcher.data.ClockStyle
import com.aura.launcher.ui.theme.Aura

/**
 * The picker that opens on a long-press of the clock.
 *
 * Each row is a live preview of the design — fixed at the same time so the
 * user compares shapes rather than digits. Tapping commits the choice and
 * dismisses; the current one carries an accent ring so it reads at a glance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockStyleSheet(
    current: ClockStyle,
    hideStatusBar: Boolean,
    onPick: (ClockStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Aura.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // A neutral, pretty time — 08:42 — so every face has the same digits and
    // the eye lands on the design instead of the number.
    val previewNow by remember { mutableLongStateOf(fixedPreviewMillis()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.ink,
        scrimColor = Color.Black.copy(alpha = 0.32f),
    ) {
        // Same status-bar fix as the day sheet — the sheet lives in its own
        // Dialog window, so the activity's WindowInsetsController does not
        // reach it. Grab this window's own controller and match the setting.
        val sheetView = LocalView.current
        DisposableEffect(sheetView, hideStatusBar) {
            val window = (sheetView.parent as? DialogWindowProvider)?.window
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val controller = WindowInsetsControllerCompat(window, sheetView)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (hideStatusBar) controller.hide(WindowInsetsCompat.Type.statusBars())
                else controller.show(WindowInsetsCompat.Type.statusBars())
            }
            onDispose {}
        }

        Column(
            Modifier
                .fillMaxWidth()
                // Seven faces do not fit on a short phone. The sheet scrolls
                // rather than dropping the ones at the bottom off the edge.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.clock_sheet_title),
                fontSize = 20.sp,
                color = colors.ink,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Text(
                text = stringResource(R.string.clock_sheet_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint,
                modifier = Modifier.padding(bottom = 18.dp),
            )
            for (style in ClockStyle.entries) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (style == current) colors.accent.copy(alpha = 0.10f)
                            else colors.paperDeep,
                        )
                        .border(
                            width = if (style == current) 1.5.dp else 0.dp,
                            color = if (style == current) colors.accent else Color.Transparent,
                            shape = RoundedCornerShape(18.dp),
                        )
                        .clickable { onPick(style) }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    // Fixed tile, every face drawn at the same fraction of its
                    // home size, clipped as a backstop. Previously the row
                    // dropped a full-size clock into a 110dp box: the digits
                    // spilled out of it and the sheet looked broken.
                    Box(
                        Modifier
                            .size(width = 118.dp, height = 70.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        HomeClock(
                            style = style,
                            now = previewNow,
                            scale = PREVIEW_SCALE,
                            // Each tile centres its face; the margin nudge the
                            // home screen needs would only push it off centre.
                            alignToEdge = false,
                        )
                    }
                    Column(Modifier.padding(start = 6.dp)) {
                        Text(
                            text = style.displayName(),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.ink,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = style.displaySubtitle(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkFaint,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * How much of its home size each face is drawn at in the picker. Small enough
 * that the widest one — the dot matrix — fits the tile, big enough that the
 * analog hands and the ring arcs are still legible.
 */
private const val PREVIEW_SCALE = 0.42f

/** 08:42 today — a pleasant, symmetrical preview time. */
private fun fixedPreviewMillis(): Long {
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 8)
    cal.set(java.util.Calendar.MINUTE, 42)
    cal.set(java.util.Calendar.SECOND, 0)
    return cal.timeInMillis
}

@Composable
private fun ClockStyle.displayName(): String = when (this) {
    ClockStyle.PAPER -> stringResource(R.string.clock_style_paper_name)
    ClockStyle.BOLD -> stringResource(R.string.clock_style_bold_name)
    ClockStyle.RING -> stringResource(R.string.clock_style_ring_name)
    ClockStyle.MATRIX -> stringResource(R.string.clock_style_matrix_name)
    ClockStyle.TYPE -> stringResource(R.string.clock_style_type_name)
    ClockStyle.ANALOG -> stringResource(R.string.clock_style_analog_name)
    ClockStyle.MINIMAL -> stringResource(R.string.clock_style_minimal_name)
    ClockStyle.EDITORIAL -> stringResource(R.string.clock_style_editorial_name)
}

@Composable
private fun ClockStyle.displaySubtitle(): String = when (this) {
    ClockStyle.PAPER -> stringResource(R.string.clock_style_paper_sub)
    ClockStyle.BOLD -> stringResource(R.string.clock_style_bold_sub)
    ClockStyle.RING -> stringResource(R.string.clock_style_ring_sub)
    ClockStyle.MATRIX -> stringResource(R.string.clock_style_matrix_sub)
    ClockStyle.TYPE -> stringResource(R.string.clock_style_type_sub)
    ClockStyle.ANALOG -> stringResource(R.string.clock_style_analog_sub)
    ClockStyle.MINIMAL -> stringResource(R.string.clock_style_minimal_sub)
    ClockStyle.EDITORIAL -> stringResource(R.string.clock_style_editorial_sub)
}

