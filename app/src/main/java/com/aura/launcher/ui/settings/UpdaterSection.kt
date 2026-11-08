package com.aura.launcher.ui.settings

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.launcher.AuraApp
import com.aura.launcher.BuildConfig
import com.aura.launcher.R
import com.aura.launcher.ui.theme.Aura
import com.aura.launcher.updater.UpdaterState
import kotlinx.coroutines.launch

/**
 * The self-update panel in Settings.
 *
 * Shows the running version, offers a Check button, and — when there is a
 * newer release — the notes for it and a single Download button. Not
 * automatic: this file's whole job is to tell the user what state the app is in
 * and let them press the button that changes it.
 */
@Composable
fun UpdaterSection(app: AuraApp) {
    val colors = Aura.colors
    val state by app.updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val currentCode = BuildConfig.VERSION_CODE
    val currentName = BuildConfig.VERSION_NAME

    when (val s = state) {
        UpdaterState.Idle -> CheckButton(
            label = stringResource(R.string.updater_check_with_version, currentName),
        ) { scope.launch { app.updater.check(currentCode) } }

        UpdaterState.Checking -> BusyRow(stringResource(R.string.updater_checking))

        is UpdaterState.UpToDate -> {
            HintRow(stringResource(R.string.updater_up_to_date), colors.sage)
            Spacer(Modifier.height(10.dp))
            CheckButton(label = stringResource(R.string.updater_check_again)) { scope.launch { app.updater.check(currentCode) } }
        }

        is UpdaterState.Available -> AvailableCard(
            newVersion = s.manifest.versionName,
            notes = s.manifest.notes,
            onInstall = { scope.launch { app.updater.download(s.manifest) } },
            onDismiss = { app.updater.reset() },
        )

        is UpdaterState.Downloading -> DownloadProgress(
            newVersion = s.manifest.versionName,
            fraction = s.fraction,
            bytes = s.bytes,
            total = s.total,
        )

        is UpdaterState.Verifying -> BusyRow(stringResource(R.string.updater_verifying, s.manifest.versionName))

        UpdaterState.Installing -> BusyRow(stringResource(R.string.updater_installing))

        is UpdaterState.Downloaded -> DownloadedCard(
            onOpen = { app.scope.launch(kotlinx.coroutines.Dispatchers.IO) { app.updater.install() } },
            onCheckAgain = {
                app.updater.reset()
                scope.launch { app.updater.check(currentCode) }
            },
        )

        is UpdaterState.Failed -> {
            HintRow(s.message, colors.danger)
            Spacer(Modifier.height(10.dp))
            CheckButton(label = stringResource(R.string.common_try_again)) { scope.launch { app.updater.check(currentCode) } }
        }
    }
}

// ============================================================ pieces ==

@Composable
private fun CheckButton(label: String = stringResource(R.string.updater_check), onClick: () -> Unit) {
    val colors = Aura.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.accent)
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = colors.surfaceRaised,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BusyRow(text: String) {
    val colors = Aura.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(colors.accent)
        Spacer(Modifier.size(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
        )
    }
}

@Composable
private fun HintRow(text: String, dotColor: androidx.compose.ui.graphics.Color) {
    val colors = Aura.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(dotColor)
        Spacer(Modifier.size(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AvailableCard(
    newVersion: String,
    notes: String,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Aura.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.accentSoft)
            .border(1.dp, colors.accent.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.updater_available_title, newVersion),
            style = MaterialTheme.typography.titleLarge,
            color = colors.ink,
            fontWeight = FontWeight.SemiBold,
        )
        if (notes.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = notes,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.updater_not_now),
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.accent)
                    .clickable { onInstall() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.updater_download),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.surfaceRaised,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * The card shown once the APK is saved to Downloads.
 *
 * A path the user can read and a single Open link that hands the file to
 * Android's system viewer. Nothing gets installed automatically — the
 * user tapping Open (or the file itself, later, from a file manager) is
 * the explicit action that starts the install prompt.
 */
@Composable
private fun DownloadedCard(
    onOpen: () -> Unit,
    onCheckAgain: () -> Unit,
) {
    val colors = Aura.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.accentSoft)
            .border(1.dp, colors.accent.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.updater_saved),
            style = MaterialTheme.typography.titleMedium,
            color = colors.ink,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.updater_check_again),
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onCheckAgain() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.accent)
                    .clickable { onOpen() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.updater_open),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.surfaceRaised,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun DownloadProgress(newVersion: String, fraction: Float, bytes: Long, total: Long) {
    val colors = Aura.colors
    val animated by animateFloatAsState(targetValue = fraction, label = "download-progress")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.updater_downloading, newVersion),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(animated * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.paperDeep),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(colors.accent),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                R.string.updater_progress_kb,
                bytes / 1024,
                (if (total > 0) total else bytes) / 1024,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkFaint,
        )
    }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}
