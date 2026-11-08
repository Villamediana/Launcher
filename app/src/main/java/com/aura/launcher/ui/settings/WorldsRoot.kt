package com.aura.launcher.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.data.Settings
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.launch
import com.aura.launcher.ui.home.Sky
import com.aura.launcher.ui.home.SkyKind

/** The three places everything in Ajustes lives. */
internal enum class World { TELA, ACOES, DADOS }

/**
 * Ajustes, opened: three doors and what is still missing.
 *
 * Organised by what the person wants rather than by how the launcher is
 * built — the screen, the gestures, and the data — with the things still
 * missing offered underneath as one-tap fixes, so nothing has to be hunted for.
 */
@Composable
internal fun WorldsRoot(app: AuraApp, settings: Settings, onOpen: (World) -> Unit) {
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WorldTile(World.TELA, 0, Modifier.weight(1f), onOpen)
            WorldTile(World.ACOES, 1, Modifier.weight(1f), onOpen)
        }
        WorldTile(World.DADOS, 2, Modifier.fillMaxWidth(), onOpen)
    }
    Spacer(Modifier.height(26.dp))
    Suggestions(app = app, settings = settings, onOpen = onOpen)
}

@Composable
internal fun worldTitle(world: World): String = stringResource(
    when (world) {
        World.TELA -> R.string.world_tela
        World.ACOES -> R.string.world_acoes
        World.DADOS -> R.string.world_dados
    },
)

@Composable
private fun WorldTile(world: World, index: Int, modifier: Modifier, onOpen: (World) -> Unit) {
    val colors = Aura.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 800f), label = "tilePress")
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(45L * index)
        enter.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 360f))
    }
    val background = when (world) {
        World.TELA -> colors.accent.copy(alpha = 0.13f)
        World.ACOES -> colors.sage.copy(alpha = 0.18f)
        World.DADOS -> colors.surface
    }
    val title = colors.ink
    val body = colors.inkMuted
    Box(
        modifier = modifier
            .height(196.dp)
            .graphicsLayer {
                val e = enter.value
                translationY = (1f - e) * 24.dp.toPx()
                alpha = e.coerceIn(0f, 1f)
                val scale = 1f - 0.03f * press
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(28.dp))
            .background(background)
            .then(if (world == World.DADOS) Modifier.border(1.dp, colors.hairline, RoundedCornerShape(28.dp)) else Modifier)
            .clickable(interactionSource = interaction, indication = null) { onOpen(world) },
    ) {
        // Each door says what is behind it by a picture before a word.
        when (world) {
            World.TELA -> Text(
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light, fontSize = 42.sp),
                color = colors.ink,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp),
            )
            World.ACOES -> Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 16.dp, top = 18.dp),
            ) {
                listOf(1f, 0.7f, 0.42f).forEach { a ->
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(11.dp)).background(colors.sage.copy(alpha = a)))
                }
            }
            World.DADOS -> Box(Modifier.padding(start = 16.dp, top = 16.dp)) {
                Box(Modifier.size(width = 40.dp, height = 48.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, colors.inkFaint, RoundedCornerShape(8.dp)))
                Box(
                    Modifier
                        .offset(x = 12.dp, y = 12.dp)
                        .size(width = 40.dp, height = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.paper)
                        .border(2.dp, colors.inkMuted, RoundedCornerShape(8.dp))
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(worldTitle(world), style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium), color = title)
            Text(
                stringResource(
                    when (world) {
                        World.TELA -> R.string.world_tela_hint
                        World.ACOES -> R.string.world_acoes_hint
                        World.DADOS -> R.string.world_dados_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                color = body,
            )
        }
    }
}

private class Suggestion(val title: String, val why: String, val action: String, val run: () -> Unit)

/**
 * What is still missing, offered as fixes. Each one is a real check — a
 * grant that was never given — re-read whenever the
 * person comes back from Android's own settings, and each button does the
 * fix rather than explaining it.
 */
@Composable
private fun Suggestions(app: AuraApp, settings: Settings, onOpen: (World) -> Unit) {
    val colors = Aura.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    fun open(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    fun ask(p: String) {
        scope.launch {
            app.permissionRequester(p)
            tick++
        }
    }

    val allow = stringResource(R.string.suggest_allow)
    val alarmTitle = stringResource(R.string.suggest_alarm_title)
    val alarmWhy = stringResource(R.string.suggest_alarm_why)
    val turnOn = stringResource(R.string.suggest_turn_on)
    val notifTitle = stringResource(R.string.suggest_notif_title)
    val notifWhy = stringResource(R.string.suggest_notif_why)

    val list = remember(tick, settings) {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Suggestion(notifTitle, notifWhy, allow) { ask(Manifest.permission.POST_NOTIFICATIONS) })
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms() == false
            ) {
                add(Suggestion(alarmTitle, alarmWhy, turnOn) { open(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) })
            }
        }.take(3)
    }

    Text(
        stringResource(R.string.suggest_header),
        style = MaterialTheme.typography.labelLarge,
        color = colors.inkMuted,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )
    if (list.isEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(colors.sage.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = colors.sage, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.suggest_none), style = MaterialTheme.typography.bodyMedium, color = colors.ink)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        list.forEachIndexed { i, s ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
                    .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(s.title, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                    Text(s.why, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (i == 0) colors.accent else colors.ink.copy(alpha = 0.07f))
                        .clickable(onClick = s.run)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        s.action,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (i == 0) Color.White else colors.ink,
                    )
                }
            }
        }
    }
}

/** One choice among a few, as cards with a line of explanation: the Ajustes C pattern. */
@Composable
private fun <T> OptionCards(header: String, options: List<Triple<T, String, String>>, selected: T, onSelect: (T) -> Unit) {
    val colors = Aura.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 18.dp)) {
        Text(header, style = MaterialTheme.typography.labelLarge, color = colors.inkMuted, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        options.forEach { (value, title, hint) ->
            val on = value == selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) colors.accent.copy(alpha = 0.10f) else colors.surface)
                    .border(if (on) 2.dp else 1.dp, if (on) colors.accent else colors.hairline, RoundedCornerShape(20.dp))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = colors.ink)
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                }
                if (on) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.Check, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** The home's layout, first under Sua tela. */
@Composable
internal fun HomeLookPicker(settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    OptionCards(
        header = stringResource(R.string.tela_home_style),
        options = listOf(
            Triple(com.aura.launcher.data.HomeStyle.DAY, stringResource(R.string.tela_home_day), stringResource(R.string.tela_home_day_hint)),
            Triple(com.aura.launcher.data.HomeStyle.CLASSIC, stringResource(R.string.tela_home_classic), stringResource(R.string.tela_home_classic_hint)),
        ),
        selected = settings.homeStyle,
        onSelect = { v -> update { it.copy(homeStyle = v) } },
    )
    SwitchRow(
        title = stringResource(R.string.tela_live_weather),
        subtitle = stringResource(R.string.tela_live_weather_hint),
        checked = settings.liveWeather,
        onChange = { v -> update { it.copy(liveWeather = v) } },
    )
    if (settings.liveWeather) WeatherPreviewChips(settings)
    Spacer(Modifier.height(8.dp))
    if (settings.homeStyle == com.aura.launcher.data.HomeStyle.DAY) {
        SwitchRow(
            title = stringResource(R.string.tela_day_favorites),
            subtitle = stringResource(R.string.tela_day_favorites_hint),
            checked = settings.dayFavorites,
            onChange = { v -> update { it.copy(dayFavorites = v) } },
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Each sky in a frame, animated, over the wallpaper — the one picked marked.
 * "Agora" is the weather outside; nothing here touches the home itself.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WeatherPreviewChips(settings: Settings) {
    val colors = Aura.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.aura.launcher.AuraApp
    val now by androidx.compose.runtime.produceState<Sky?>(null) {
        value = runCatching { app.weather.current() }.getOrNull()?.let { com.aura.launcher.ui.home.skyOf(it) }
    }
    val skies = listOf(
        "Sol" to Sky(SkyKind.CLEAR, true, 24),
        "Calor" to Sky(SkyKind.CLEAR, true, 34),
        "Noite" to Sky(SkyKind.CLEAR, false, 18),
        "Entardecer" to Sky(SkyKind.PARTLY, true, 22, hour = 18),
        "Nublado" to Sky(SkyKind.CLOUDY, true, 20),
        "Neblina" to Sky(SkyKind.FOG, true, 14),
        "Garoa" to Sky(SkyKind.DRIZZLE, true, 19),
        "Chuva" to Sky(SkyKind.RAIN, true, 18, 10f),
        "Chuva forte" to Sky(SkyKind.HEAVY_RAIN, false, 17, 25f),
        "Tempestade" to Sky(SkyKind.STORM, false, 21, 30f),
        "Neve" to Sky(SkyKind.SNOW, true, -2),
        "Frio" to Sky(SkyKind.PARTLY, true, 3),
    )
    var picked by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("Agora") }
    val shown = if (picked == "Agora") now ?: skies[0].second else skies.first { it.first == picked }.second
    val wallpaper = com.aura.launcher.ui.theme.rememberWallpaperBitmap(settings.wallpaperPath)
    Column(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp)) {
        com.aura.launcher.ui.home.SkyPreview(
            shown,
            wallpaper,
            Modifier
                .fillMaxWidth()
                .height(230.dp)
                .clip(RoundedCornerShape(22.dp)),
        )
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("Agora") + skies.map { it.first }).forEach { label ->
                val on = label == picked
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (on) Color.White else colors.ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) colors.accent else colors.ink.copy(alpha = 0.06f))
                        .clickable { picked = label }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
