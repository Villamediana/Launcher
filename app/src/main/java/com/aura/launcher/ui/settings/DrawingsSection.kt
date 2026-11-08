package com.aura.launcher.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.sketch.BoardEntry
import com.aura.launcher.sketch.SketchHost
import com.aura.launcher.sketch.SketchOpening
import com.aura.launcher.sketch.SketchPainter
import com.aura.launcher.sketch.SketchProvider
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * The drawings, and the model that helps with them.
 *
 * A board opens from the gesture surface (a finger held still) or from here;
 * every board kept is a tile in this grid. Long press to throw one away.
 */
@Composable
fun DrawingsSection(app: AuraApp) {
    val colors = Aura.colors
    val entries by app.boards.entries.collectAsState()
    LaunchedEffect(Unit) { app.boards.refresh() }
    var deleting by remember { mutableStateOf<BoardEntry?>(null) }

    PrimaryAction(
        icon = Icons.Rounded.Gesture,
        label = stringResource(R.string.settings_drawings_new),
        onClick = { SketchHost.open(SketchOpening(boardId = null)) },
    )

    Spacer(Modifier.height(12.dp))
    if (entries.isEmpty()) {
        Hint(stringResource(R.string.settings_drawings_empty))
    } else {
        entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                row.forEach { entry ->
                    BoardTile(entry, Modifier.weight(1f), onOpen = { SketchHost.open(SketchOpening(entry.id)) }, onLongPress = { deleting = entry })
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    GroupLabel(stringResource(R.string.settings_drawings_model))
    val config by app.sketchModel.config.collectAsState()
    SettingsGroup {
        ChoiceRow(
            title = stringResource(R.string.settings_drawings_provider),
            options = SketchProvider.entries.map { it to it.label },
            selected = config.provider,
            onSelect = { app.sketchModel.update(config.copy(provider = it, model = "", baseUrl = "")) },
        )
        Divider()
        TextRow(
            title = stringResource(R.string.settings_drawings_key),
            value = config.apiKey,
            placeholder = "sk-…",
            secret = true,
            onChange = { app.sketchModel.update(app.sketchModel.config.value.copy(apiKey = it)) },
        )
        Divider()
        TextRow(
            title = stringResource(R.string.settings_drawings_model_name),
            value = config.model,
            placeholder = config.provider.defaultModel.ifBlank { "…" },
            onChange = { app.sketchModel.update(app.sketchModel.config.value.copy(model = it)) },
        )
        if (config.provider == SketchProvider.CUSTOM) {
            Divider()
            TextRow(
                title = stringResource(R.string.settings_drawings_base_url),
                value = config.baseUrl,
                placeholder = "https://…/v1",
                onChange = { app.sketchModel.update(app.sketchModel.config.value.copy(baseUrl = it)) },
            )
        }
    }
    ModelActions(app)
    Hint(stringResource(R.string.settings_drawings_model_hint))

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = colors.surface,
            titleContentColor = colors.ink,
            title = { Text(stringResource(R.string.settings_drawings_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    app.scope.launch { app.boards.delete(entry.id) }
                    deleting = null
                }) { Text(stringResource(R.string.settings_drawings_delete_confirm), color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel), color = colors.inkMuted) }
            },
        )
    }
}

@Composable
private fun BoardTile(entry: BoardEntry, modifier: Modifier, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val colors = Aura.colors
    val thumb by produceState<ImageBitmap?>(null, entry.thumbnail, entry.updated) {
        value = withContext(Dispatchers.IO) {
            entry.thumbnail.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.asImageBitmap()
        }
    }
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(SketchPainter.GROUND))
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        ) {
            thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(
            DateFormat.getDateInstance(DateFormat.SHORT).format(Date(entry.updated)),
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkFaint,
            modifier = Modifier.padding(top = 5.dp, start = 4.dp),
        )
    }
}

/** Pick the model from what the address offers, and see that it answers. */
@Composable
private fun ModelActions(app: AuraApp) {
    val colors = Aura.colors
    val config by app.sketchModel.config.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var offered by remember { mutableStateOf<List<String>?>(null) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val usable = config.apiKey.isNotBlank() && config.effectiveBaseUrl.isNotBlank()

    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) {
            PrimaryAction(
                icon = Icons.Rounded.List,
                label = stringResource(if (loading) R.string.settings_drawings_loading else R.string.settings_drawings_load_models),
                enabled = usable && !loading,
                filled = !loading,
                onClick = {
                    loading = true
                    status = null
                    scope.launch {
                        runCatching { app.sketchModel.models() }
                            .onSuccess { if (it.isEmpty()) status = false to context.getString(R.string.settings_drawings_no_models) else offered = it }
                            .onFailure { status = false to (it.message ?: it.javaClass.simpleName).take(120) }
                        loading = false
                    }
                },
            )
        }
        Box(Modifier.weight(1f)) {
            PrimaryAction(
                icon = Icons.Rounded.PlayArrow,
                label = stringResource(if (testing) R.string.settings_drawings_testing else R.string.settings_drawings_test),
                enabled = config.ready && !testing,
                filled = !testing,
                onClick = {
                    testing = true
                    status = null
                    scope.launch {
                        runCatching { app.sketchModel.test() }
                            .onSuccess { (reply, ms) ->
                                status = true to context.getString(R.string.settings_drawings_test_ok, String.format(java.util.Locale.getDefault(), "%.1f", ms / 1000f), reply)
                            }
                            .onFailure { status = false to (it.message ?: it.javaClass.simpleName).take(120) }
                        testing = false
                    }
                },
            )
        }
    }
    status?.let { (ok, text) ->
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) colors.sage else colors.danger,
            modifier = Modifier.padding(start = 6.dp, top = 8.dp),
        )
    }

    offered?.let { models ->
        var filter by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { offered = null },
            containerColor = colors.surface,
            titleContentColor = colors.ink,
            title = { Text(stringResource(R.string.settings_drawings_pick_model)) },
            text = {
                Column {
                    if (models.size > 8) {
                        androidx.compose.material3.OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.settings_drawings_filter), color = colors.inkFaint) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.height(360.dp)) {
                        val shown = models.filter { it.contains(filter.trim(), ignoreCase = true) }
                        items(shown.size) { i ->
                            val id = shown[i]
                            Text(
                                id,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (id == config.effectiveModel) colors.accent else colors.ink,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .combinedClickable(onClick = {
                                        app.sketchModel.update(app.sketchModel.config.value.copy(model = id))
                                        offered = null
                                        status = null
                                    })
                                    .padding(horizontal = 10.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { offered = null }) { Text(stringResource(R.string.common_close), color = colors.inkMuted) }
            },
        )
    }
}
