package com.aura.launcher.ui.settings

import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aura.launcher.AuraApp
import com.aura.launcher.control.ScreenController
import com.aura.launcher.data.Settings
import com.aura.launcher.ui.components.ActionButton
import com.aura.launcher.ui.onboarding.rememberHomeRoleState
import com.aura.launcher.ui.components.PaperCard
import androidx.compose.ui.text.style.TextOverflow
import com.aura.launcher.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aura.launcher.data.AppLanguage
import com.aura.launcher.ui.defaultLanguageFor
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    app: AuraApp,
    settings: Settings,
    /** A finger has gone down on the header; see PanelSwipeState. */
    onDragStart: () -> Unit = {},
    /** Vertical movement in pixels; positive is downward, towards closed. */
    onDrag: (Float) -> Unit = {},
    /** Lift-off, with the fling velocity in pixels per second. */
    onDragEnd: (Float) -> Unit = {},
    onClose: () -> Unit,
) {
    val colors = Aura.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun update(block: (Settings) -> Settings) {
        // TextRow flushes a pending debounced edit while this screen is being
        // disposed. A composition-owned scope is cancelled at that exact
        // moment and could silently lose the user's final characters.
        app.scope.launch { app.settingsRepository.update(block) }
    }

    // Settings usually opens from the drawer, where the search field may still
    // hold focus with the keyboard up. Arriving over a keyboard means half the
    // screen is missing before the user has done anything.
    LaunchedEffect(Unit) {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    // Pulling down when the page is already at the top closes it. The drag
    // handler on the header only sees gestures outside the scrolling area,
    // because a scrollable consumes drags before its parent hears about them —
    // this is what catches the gesture people actually make.
    // Remembered without keys on purpose. Recomposition happens on every frame
    // of the gesture it is driving, and rebuilding the connection each time
    // would lose `pulling` mid-drag — which reads as the page starting a fresh
    // gesture sixty times a second.
    val onDragStartNow by rememberUpdatedState(onDragStart)
    val onDragNow by rememberUpdatedState(onDrag)
    val onDragEndNow by rememberUpdatedState(onDragEnd)
    val pullToClose = remember {
        object : NestedScrollConnection {
            private var pulling = false

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y > 0f) {
                    if (!pulling) {
                        pulling = true
                        onDragStartNow()
                    }
                    onDragNow(available.y)
                } else if (available.y < 0f && pulling) {
                    pulling = false
                    onDragEndNow(0f)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pulling) {
                    pulling = false
                    onDragEndNow(available.y)
                }
                return Velocity.Zero
            }
        }
    }

    // Which subscreen is on. Settings opens on ROOT — a page of four cards —
    // and each card pushes onto one of the routes below. Kept simple: there
    // is no back stack because there is nothing deeper than a subscreen.
    var route by remember { mutableStateOf(SettingsRoute.ROOT) }

    // Android back returns to ROOT from a subscreen; on ROOT it falls through
    // to the panel's own close, which is what the header X already calls.
    BackHandler(enabled = route != SettingsRoute.ROOT) { route = SettingsRoute.ROOT }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Cutout included, for the same reason the drawer does it: with the
            // status bar hidden its inset is zero, and the title would sit
            // behind the camera.
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .imePadding()
            // Pull-to-close only at ROOT. Inside a subscreen the scroll owns
            // the vertical gesture and letting the parent snatch overscroll
            // would fight it.
            .then(if (route == SettingsRoute.ROOT) Modifier.nestedScroll(pullToClose) else Modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                // The title bar is the handle at ROOT, mirroring the drawer:
                // the page below it scrolls, so the gesture needs somewhere
                // of its own. Inside a subscreen the drag would fight the
                // internal scroll, so the header just sits still.
                .then(
                    if (route == SettingsRoute.ROOT) {
                        Modifier.draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta -> onDrag(delta) },
                            onDragStarted = {
                                keyboard?.hide()
                                onDragStart()
                            },
                            onDragStopped = { velocity -> onDragEnd(velocity) },
                        )
                    } else Modifier
                )
                .padding(horizontal = 22.dp, vertical = 14.dp),
        ) {
            if (route != SettingsRoute.ROOT) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
                        .clickable { route = SettingsRoute.ROOT },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        stringResource(R.string.settings_back_cd),
                        tint = colors.inkMuted,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = when (route) {
                    SettingsRoute.ROOT -> stringResource(R.string.settings_title)
                    SettingsRoute.TELA -> worldTitle(World.TELA)
                    SettingsRoute.ACOES -> worldTitle(World.ACOES)
                    SettingsRoute.DADOS -> worldTitle(World.DADOS)
                },
                style = MaterialTheme.typography.titleLarge,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, stringResource(R.string.common_close), tint = colors.inkMuted, modifier = Modifier.size(17.dp))
            }
        }

        // Pages move along one axis: going in slides the new page from the
        // right, coming back brings the list in from the left, so where you
        // are is also where you came from.
        androidx.compose.animation.AnimatedContent(
            targetState = route,
            transitionSpec = {
                val forward = targetState != SettingsRoute.ROOT
                val dir = if (forward) 1 else -1
                (
                    androidx.compose.animation.slideInHorizontally(ROUTE_SLIDE) { it / 5 * dir } +
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 40))
                    ) togetherWith (
                    androidx.compose.animation.slideOutHorizontally(ROUTE_SLIDE) { -it / 8 * dir } +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                    )
            },
            label = "settingsRoute",
            modifier = Modifier.fillMaxSize(),
        ) { shown ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            // Three places, by what the person wants.
            when (shown) {
                SettingsRoute.ROOT -> WorldsRoot(app = app, settings = settings, onOpen = { route = it.route })
                SettingsRoute.TELA -> {
                    HomeLookPicker(settings = settings, update = ::update)
                    HomeSubscreen(app = app, settings = settings, update = ::update)
                    Spacer(Modifier.height(18.dp))
                    WidgetsSettings(app = app, settings = settings, update = ::update)
                }
                SettingsRoute.ACOES -> GesturesSubscreen(app = app)
                SettingsRoute.DADOS -> {
                    DataSubscreen(app = app, settings = settings, update = ::update)
                    SystemSubscreen(app = app, settings = settings, update = ::update)
                }
            }
            Spacer(Modifier.height(48.dp))
        }
        }
    }
}

private val ROUTE_SLIDE = androidx.compose.animation.core.spring(
    dampingRatio = 0.9f,
    stiffness = 900f,
    visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold,
)

/**
 * Whether Aura is the home screen, and the way to make it one.
 *
 * Only onboarding offered this, which is exactly the wrong place for it to live
 * alone: onboarding is seen once, and "actually, let me try it as my launcher"
 * is a decision people reach days later. Absent entirely when it already is one
 * — a row that does nothing is worse than no row.
 */
@Composable
private fun DefaultLauncherRow() {
    val colors = Aura.colors
    val homeRole = rememberHomeRoleState()
    if (homeRole.isDefault) return

    SectionHeader(Icons.Rounded.Home, stringResource(R.string.settings_launcher_header_title), stringResource(R.string.settings_launcher_header_subtitle))
    SettingsGroup {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { homeRole.request() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                RowTitle(
                    stringResource(R.string.settings_launcher_title),
                    stringResource(R.string.settings_launcher_subtitle),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.settings_launcher_button),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accent,
            )
        }
    }
    // Inside the early-returning composable on purpose: the gap belongs to this
    // row, and a Spacer left at the top of the page for a row that is not there
    // is a margin nobody can explain.
    Spacer(Modifier.height(20.dp))
}

/**
 * Icon-led section heading.
 *
 * Settings was a wall of identical text labels; an icon and a one-line
 * description give each block a shape you can find by scanning rather than by
 * reading every row.
 */
/**
 * A [SettingsCategory] with its open/closed state kept locally. Every
 * subscreen wraps its groups in one of these so the section arrives
 * folded — nothing here decides for the user which block they want
 * open. Tap the header, it expands; tap again, it folds. State does
 * not survive leaving the subscreen on purpose: coming back to a
 * cleanly-folded panel is easier than remembering which fold was
 * still open from last time.
 */
@Composable
private fun CollapsibleSection(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    /**
     * Whether this one arrives open.
     *
     * For the first section of a subscreen whose whole point is one choice —
     * which engine answers, which language the interface is in. Those used to
     * sit outside the folds entirely, which made them look like a different
     * kind of thing from everything under them; open by default says "start
     * here" without breaking the pattern.
     */
    initiallyOpen: Boolean = false,
    /**
     * Whether the fold draws a card around what it contains.
     *
     * True for a fold that is a list of rows — switches, choices, sliders —
     * which is the one thing a card is for. False for the sections whose
     * content already builds its own cards, and those are most of them:
     * shortcuts, schedules, memories, servers, permissions. Those were
     * arriving inside a *second* card, and a card inside a card does not read
     * as nesting. It reads as a border pressed up against another border, and
     * the loose lines of prose that live between those inner cards — every
     * [Hint] in the section — were left squeezed against an edge that should
     * never have been drawn around them.
     */
    boxed: Boolean = true,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    SettingsCategory(
        icon = icon,
        title = title,
        subtitle = subtitle,
        open = open,
        onToggle = { open = !open },
    ) {
        if (boxed) SettingsGroup { content() } else content()
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String, subtitle: String) {
    val colors = Aura.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp, start = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(colors.accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.inkFaint)
        }
    }
}

private const val ADDING = "adding"

/** The tap-target text used inside MCP cards. Compact and consistent. */
@Composable
private fun LinkAction(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (enabled) color else Aura.colors.inkFaint,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * Apps kept out of the drawer, and the way back.
 *
 * Hiding an app is done by long-pressing it in the drawer — which is exactly
 * where it then stops being, so there was no way to undo it except remembering
 * the name and searching for it. A one-way door in a settings-free corner of the
 * app is a bug however it is spelled.
 *
 * Absent when nothing is hidden: an empty list here would only advertise a
 * feature by describing it, and this one is discovered by having been used.
 */
@Composable
private fun HiddenAppsSection(app: AuraApp) {
    val hidden by app.settingsRepository.hidden.collectAsState(initial = emptySet())
    val apps by app.appRepository.apps.collectAsState()

    // Resolved against what is installed, so an app hidden and later uninstalled
    // leaves a key behind rather than a row for something that is not there.
    val entries = remember(hidden, apps) {
        apps.filter { it.key in hidden }.sortedBy { it.displayLabel.lowercase() }
    }
    if (entries.isEmpty()) return

    // Folded like every other block on the subscreen. It used to draw its own
    // header and sit permanently open below the folds, which read as a section
    // that had forgotten to close rather than as a deliberate exception.
    CollapsibleSection(
        icon = Icons.Rounded.VisibilityOff,
        title = stringResource(R.string.settings_hidden_apps_title),
        subtitle = if (entries.size == 1) stringResource(R.string.settings_hidden_apps_one)
        else stringResource(R.string.settings_hidden_apps_many, entries.size),
    ) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) Divider()
            SwipeAwayRow(
                title = entry.displayLabel,
                subtitle = if (entry.alias.isNullOrBlank()) entry.packageName
                else "${entry.label} · ${entry.packageName}",
                actionLabel = stringResource(R.string.settings_hidden_show),
                revealLabel = stringResource(R.string.settings_hidden_show_reveal),
                onAction = { app.scope.launch { app.settingsRepository.toggleHidden(entry.key) } },
            )
        }
    }
}

/**
 * A row that is swiped aside to do something to it.
 *
 * Shared by the hidden apps and the memories, because they are the same gesture on
 * the same kind of list — a thing you put away, and the way back. Two copies would
 * have drifted in threshold and in feel, and a list that behaves almost like the
 * one above it is worse than one that behaves differently on purpose.
 *
 * Plain state while a finger is down and an [androidx.compose.animation.core.Animatable]
 * once it lifts — the same split the notification cards and the drawer use, and
 * for the same reason: a row that chases the thumb through a coroutine never feels
 * attached to it.
 */
@Composable
internal fun SwipeAwayRow(
    title: String,
    subtitle: String?,
    /** The button, for anyone who has not discovered the gesture. */
    actionLabel: String,
    /** What appears behind the row as it travels. */
    revealLabel: String,
    onAction: () -> Unit,
    /**
     * Tapping the row itself, where that means something. Null leaves the row
     * inert, which is what a memory or a hidden app wants — there is nothing
     * behind those to open.
     */
    onClick: (() -> Unit)? = null,
    titleLines: Int = 2,
) {
    val colors = Aura.colors
    val scope = rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    val threshold = remember(density) { with(density) { 110.dp.toPx() } }
    val settle = remember { androidx.compose.animation.core.Animatable(0f) }
    var dragX by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    val offset = if (dragging) dragX else settle.value
    val progress = (kotlin.math.abs(offset) / threshold).coerceIn(0f, 1f)

    Box(Modifier.fillMaxWidth()) {
        // Says what letting go will do, and gets clearer the further it travels.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 22.dp),
        ) {
            Text(
                text = revealLabel,
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent.copy(alpha = progress),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = offset
                    alpha = 1f - progress * 0.45f
                }
                .background(colors.surface)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta -> dragX += delta },
                    onDragStarted = {
                        scope.launch { settle.stop() }
                        dragX = settle.value
                        dragging = true
                    },
                    onDragStopped = { velocity ->
                        val far = kotlin.math.abs(dragX) > threshold ||
                            kotlin.math.abs(velocity) > 900f
                        scope.launch {
                            settle.snapTo(dragX)
                            dragging = false
                            if (far) {
                                haptics.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                )
                                // Off the side first, then out of the list: the gap
                                // closing is a separate beat from the row leaving.
                                settle.animateTo(
                                    if (dragX > 0) threshold * 4f else -threshold * 4f,
                                    spring(dampingRatio = 1f, stiffness = 900f),
                                )
                                onAction()
                            } else {
                                settle.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = 700f))
                            }
                        }
                    },
                )
                .padding(horizontal = 18.dp, vertical = 13.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .then(
                        if (onClick != null) Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onClick)
                            .padding(vertical = 2.dp)
                        else Modifier
                    ),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.ink,
                    // Memories are sentences, not names: two lines, so a fact is
                    // readable rather than trailing off in an ellipsis.
                    maxLines = titleLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // A tap target as well as the gesture. Swiping is the nicer way and a
            // discoverable one only once you know it is there.
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * A folded-away part of the settings.
 *
 * There are a dozen sections now, and a dozen sections in one column is not a
 * settings screen, it is a scroll. Folding them behind six rows means the page
 * opens as a list of places rather than a wall of switches, and the one thing
 * anybody changes in a hurry — which model answers — stays where it always was,
 * above all of this and never folded.
 *
 * Only one is open at a time. Several open at once rebuilds the wall, slowly.
 */
@Composable
private fun SettingsCategory(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = Aura.colors
    val turn by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "categoryChevron",
    )
    val lit by animateFloatAsState(if (open) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = 600f), label = "categoryLit")
    // Rises into place once, like the doors on the first page.
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 420f)) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 800f), label = "categoryPress")

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .graphicsLayer {
                val e = enter.value
                translationY = (1f - e) * 14.dp.toPx()
                alpha = e.coerceIn(0f, 1f)
            },
    ) {
        // The section's door: a card of its own, with the picture-first icon
        // block the rest of Ajustes uses. Open, it warms a little.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val sc = 1f - 0.015f * press
                    scaleX = sc
                    scaleY = sc
                }
                .clip(RoundedCornerShape(22.dp))
                .background(colors.surface)
                .background(colors.accent.copy(alpha = 0.06f * lit))
                .border(1.dp, if (open) colors.accent.copy(alpha = 0.35f) else colors.hairline, RoundedCornerShape(22.dp))
                .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (open) colors.accent else colors.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (open) Color.White else colors.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                    color = colors.ink,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(colors.ink.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colors.inkMuted,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = turn },
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = open,
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 0.9f, stiffness = 700f)) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180, delayMillis = 40)),
            exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 0.95f, stiffness = 900f)) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(110)),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 12.dp)) { content() }
        }
    }
}

// ------------------------------------------------------------- primitives --

/**
 * The palette every switch in here uses.
 *
 * Extracted when the MCP rows needed it: a second hand-written copy of these five
 * colours is how one screen ends up with two kinds of switch on it.
 */
@Composable
private fun auraSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Aura.colors.surfaceRaised,
    checkedTrackColor = Aura.colors.accent,
    uncheckedThumbColor = Aura.colors.inkFaint,
    uncheckedTrackColor = Aura.colors.surface,
    uncheckedBorderColor = Aura.colors.hairline,
)

@Composable
internal fun SettingsGroup(content: @Composable () -> Unit) {
    PaperCard(modifier = Modifier.fillMaxWidth(), corner = 20.dp) {
        Column { content() }
    }
}

@Composable
internal fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Aura.colors.hairline)
    )
}

@Composable
internal fun Hint(text: String) {
    Text(
        text = text,
        // Twelve-on-sixteen is the line spacing of a caption, and a caption is
        // one line. Most of these are paragraphs, and a paragraph set at
        // sixteen has its descenders in the next line's ascenders — which is
        // exactly what "the text looks cramped" describes.
        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
        color = Aura.colors.inkFaint,
        // Held at the same inset as the text inside the cards above and below,
        // so a hint reads as a note in the same column rather than as something
        // that missed the margin. Four pixels was not a margin, it was a
        // rounding error; twelve was closer and still visibly short of every
        // row it sits under.
        modifier = Modifier.padding(horizontal = HINT_INSET, vertical = 4.dp),
    )
}

/**
 * The margin a loose line of text inside a fold is held at.
 *
 * The same sixteen every row inside a [SettingsGroup] uses, so prose and rows
 * share one left edge. See [Hint].
 */
private val HINT_INSET = 16.dp

/**
 * A row that only states something. No switch, no chevron, nothing to press.
 *
 * For the places a setting's consequence needs saying in the same shape as the
 * settings around it — "this is the model that will listen, and here is why it
 * cannot yet" — rather than as a hint floating under the group.
 */
@Composable
private fun InfoRow(title: String, subtitle: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(Modifier.weight(1f)) { RowTitle(title, subtitle) }
    }
}

@Composable
internal fun RowTitle(title: String, subtitle: String?) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Aura.colors.ink)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Aura.colors.inkFaint)
        }
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = Aura.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(Modifier.weight(1f)) { RowTitle(title, subtitle) }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.surfaceRaised,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.inkFaint,
                uncheckedTrackColor = colors.surface,
                uncheckedBorderColor = colors.hairline,
            ),
        )
    }
}

@Composable
internal fun <T> ChoiceRow(
    title: String,
    subtitle: String? = null,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val colors = Aura.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        RowTitle(title, subtitle)
        Spacer(Modifier.height(12.dp))
        // Wraps, because a row that does not is not merely cramped — it is
        // broken in a way that does not look like a layout bug.
        //
        // Four chips with real words on them ("Seguir modelo", "O modelo que
        // responde") do not fit one line on a phone. In a plain Row the last
        // ones are handed what is left, which is nothing: their label then
        // wraps to one character per line, and a chip forty lines tall renders
        // as a thin vertical bar down the side of the card with a screenful of
        // empty space beside it. That is what the voice section had become.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((value, label) in options) {
                val active = value == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (active) colors.accent.copy(alpha = 0.13f) else colors.paperDeep)
                        .border(
                            1.dp,
                            if (active) colors.accent.copy(alpha = 0.35f) else colors.hairline,
                            RoundedCornerShape(13.dp),
                        )
                        .clickable { onSelect(value) }
                        .padding(horizontal = 13.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active) colors.accent else colors.inkMuted,
                        // A label never breaks. If one is somehow still too wide
                        // for a whole line it is cut with an ellipsis, which is
                        // a legible chip; the alternative is the tall thin bar
                        // this row is named after.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
    subtitle: String? = null,
) {
    val colors = Aura.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.ink, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.bodySmall, color = colors.inkFaint)
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.ink.copy(alpha = 0.10f),
            ),
        )
    }
}

@Composable
internal fun TextRow(
    title: String,
    value: String,
    placeholder: String,
    secret: Boolean = false,
    multiline: Boolean = false,
    onChange: (String) -> Unit,
) {
    val colors = Aura.colors
    // Keep typing synchronous, but debounce persistence. The previous version
    // still called onChange for every character despite saying otherwise, so a
    // URL or instruction paragraph rewrote the complete DataStore dozens of
    // times and fed each value back through composition.
    var local by remember { mutableStateOf(value) }
    var lastSubmitted by remember { mutableStateOf(value) }
    val latestOnChange by rememberUpdatedState(onChange)

    LaunchedEffect(value) {
        // A matching value is our own write arriving from DataStore. Only a
        // genuinely external change may replace text still being edited.
        if (value != lastSubmitted) {
            local = value
            lastSubmitted = value
        }
    }
    LaunchedEffect(local) {
        if (local == lastSubmitted) return@LaunchedEffect
        kotlinx.coroutines.delay(TEXT_SAVE_DEBOUNCE_MS)
        lastSubmitted = local
        latestOnChange(local)
    }
    DisposableEffect(Unit) {
        onDispose {
            // Navigating away must not cancel the final pending edit.
            if (local != lastSubmitted) latestOnChange(local)
        }
    }
    var reveal by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.ink, modifier = Modifier.weight(1f))
            if (secret && local.isNotEmpty()) {
                Text(
                    text = if (reveal) stringResource(R.string.settings_secret_hide) else stringResource(R.string.settings_secret_show),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { reveal = !reveal }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.paperDeep)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (local.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.inkFaint)
            }
            BasicTextField(
                value = local,
                onValueChange = {
                    local = it
                },
                singleLine = !multiline,
                maxLines = if (multiline) 5 else 1,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else
                    androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val TEXT_SAVE_DEBOUNCE_MS = 350L

/**
 * An icon that is a button without a button's worth of space around it.
 *
 * [androidx.compose.material3.IconButton] reserves a 48dp touch target and
 * centres a small glyph in it, which is right for a toolbar and wrong for two
 * controls that belong together: it pushed the bin and the play apart until
 * they read as unrelated things near the same name.
 */
@Composable
private fun CompactIcon(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Dp = 20.dp,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            // Still comfortably past the smallest thing a thumb can hit; the
            // saving is the empty space either side, not the target itself.
            .padding(9.dp),
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size))
    }
}

/**
 * The one thing a section is *for*, as a button, at the top of it.
 *
 * Record a shortcut, teach a gesture, add a server: three sections whose whole
 * point is adding something, which each had a differently-shaped control in a
 * different place — one a filled pill above the list, one a plain word on a
 * card below it, one a tinted strip at the bottom. They are the same act, so
 * they are the same button, and it is the first thing in the fold rather than
 * whatever is left after the list.
 */
@Composable
internal fun PrimaryAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Aura.colors.accent,
    /**
     * Filled is the offer; outlined is the same control saying "already going".
     *
     * The filled pill used to be paint-across-the-whole-panel, which read as
     * the section's headline rather than as a control that added something.
     * A tinted outline in the section's accent colour is enough: legible,
     * findable, and quiet next to the list underneath it.
     */
    filled: Boolean = true,
    enabled: Boolean = true,
) {
    val colors = Aura.colors
    val bg = if (filled) tint.copy(alpha = 0.10f) else Color.Transparent
    val border = tint.copy(alpha = if (filled) 0.28f else 0.36f)
    val fg = tint
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(9.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = fg,
        )
    }
}


// ------------------------------------------------------------- routes ------

/**
 * Which subscreen is on. Kept as an enum because there are exactly four and
 * no argument to pass between screens — nothing that a nav library would help
 * with. A subscreen is entered from the ROOT cards and left with the header's
 * back arrow (or the Android back button, wired via BackHandler above).
 */
private enum class SettingsRoute { ROOT, TELA, ACOES, DADOS }

private val World.route: SettingsRoute
    get() = when (this) {
        World.TELA -> SettingsRoute.TELA
        World.ACOES -> SettingsRoute.ACOES
        World.DADOS -> SettingsRoute.DADOS
    }

// --------------------------------------------------------- subscreens ------

/**
 * How the launcher looks. Theme, wallpaper, and the ink printed over it.
 *
 * This page used to carry four folds: appearance, then a clock section, a cards
 * section and a shortcuts section, twelve switches between them. Every one of
 * those switches had a right answer — a home screen wants its clock, its date,
 * its battery, the cards it can draw and the Android status bar out of the way —
 * so they were twelve chances to make the launcher worse and none to make it
 * better. What is left here is the part that is genuinely a matter of taste,
 * shown rather than described. See [AppearanceSettings].
 *
 * The one question that did come back — which widgets are on the home screen —
 * has a page of its own next door, because the launcher now draws cards that
 * belong to one person's life rather than to everybody's. See [WidgetsSettings].
 *
 * The permissions those cards need did not go with them: they live in the
 * Permissions section under System, which is where every other grant already is.
 */
@Composable
private fun HomeSubscreen(
    app: AuraApp,
    settings: Settings,
    update: ((Settings) -> Settings) -> Unit,
) {
    DefaultLauncherRow()
    AppearanceSettings(app = app, settings = settings, update = update)

}

/** A small label over a block inside a fold: "Conexão", "Como ela te ouve". */
@Composable
internal fun GroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            letterSpacing = androidx.compose.ui.unit.TextUnit(0.6f, androidx.compose.ui.unit.TextUnitType.Sp),
        ),
        color = Aura.colors.inkMuted,
        modifier = Modifier.padding(start = HINT_INSET, top = 18.dp, bottom = 8.dp),
    )
}

/** The gestures: shapes drawn on the home screen, the drawer or the lock screen, and what each one does. */
@Composable
private fun GesturesSubscreen(app: AuraApp) {
    val gestureList by app.gestures.gestures.collectAsState()
    CollapsibleSection(
        icon = Icons.Rounded.Draw,
        title = stringResource(R.string.settings_category_gestures_title),
        subtitle = countSubtitle(gestureList.size),
        initiallyOpen = true,
        boxed = false,
    ) { GesturesSection(app) }

    val boards by app.boards.entries.collectAsState()
    LaunchedEffect(Unit) { app.boards.refresh() }
    CollapsibleSection(
        icon = Icons.Rounded.Gesture,
        title = stringResource(R.string.settings_category_drawings_title),
        subtitle = countSubtitle(boards.size),
        boxed = false,
    ) { DrawingsSection(app) }
}

/**
 * A short subtitle for a category card: a count, or a single dash when the
 * list is empty. The old subtitles were paragraphs describing the feature —
 * information the icon and title already carried — and made the page look
 * ten pages long. See [CollapsibleSection].
 */
@Composable
private fun countSubtitle(count: Int): String = when (count) {
    0 -> "—"
    1 -> stringResource(R.string.settings_count_one)
    else -> stringResource(R.string.settings_count_many, count)
}

/**
 * System settings — language, permissions and self-update. The things that
 * decide what the launcher is allowed to do and how it stays current.
 */
@Composable
private fun SystemSubscreen(
    app: AuraApp,
    settings: Settings,
    update: ((Settings) -> Settings) -> Unit,
) {
    // A fold like the two under it. It was a bare card, on the reasoning that
    // one choice is not a category — which left the first thing on the page
    // looking like a different kind of object from everything below it, and
    // the page reading as an exception followed by a pattern.
    CollapsibleSection(
        icon = Icons.Rounded.Language,
        title = stringResource(R.string.settings_language_title),
    ) {
        ChoiceRow(
            title = stringResource(R.string.settings_language_title),
            options = AppLanguage.entries.map {
                it to when (it) {
                    AppLanguage.ENGLISH -> "English"
                    AppLanguage.PORTUGUESE -> "Português"
                    AppLanguage.SPANISH -> "Español"
                }
            },
            selected = settings.language ?: defaultLanguageFor(LocalContext.current),
            onSelect = { choice -> update { it.copy(language = choice) } },
        )
    }

    CollapsibleSection(
        icon = Icons.Rounded.Shield,
        title = stringResource(R.string.settings_category_permissions_title),
        boxed = false,
    ) { PermissionsSection(app) }

    CollapsibleSection(
        icon = Icons.Rounded.SystemUpdate,
        title = stringResource(R.string.settings_category_update_title),
        boxed = false,
    ) { UpdaterSection(app) }
}

/**
 * The apps hidden from the drawer, when there are any.
 */
@Composable
private fun DataSubscreen(
    app: AuraApp,
    settings: Settings,
    update: ((Settings) -> Settings) -> Unit,
) {
    // Absent entirely when nothing is hidden — an empty fold would only
    // advertise a feature by describing it.
    HiddenAppsSection(app)
}


