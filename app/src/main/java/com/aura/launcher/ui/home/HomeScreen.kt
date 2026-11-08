package com.aura.launcher.ui.home

import android.content.Intent
import android.provider.AlarmClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aura.launcher.AuraApp
import com.aura.launcher.apps.AppEntry
import com.aura.launcher.data.HomeLayout
import com.aura.launcher.data.Settings
import com.aura.launcher.ui.components.AppActionsSheet
import com.aura.launcher.ui.components.FavoritesList
import com.aura.launcher.ui.theme.Aura
import com.aura.launcher.R
import androidx.compose.ui.res.stringResource
import com.aura.launcher.ui.theme.OnWallpaperInk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    app: AuraApp,
    settings: Settings,
    onOpenDrawer: () -> Unit,
    onOpenDrawerAt: (String) -> Unit,
    /** Thumb position on the hidden edge, or null once it lifts. */
    onEdgeScrub: (Float?) -> Unit,
    /** A finger has gone down and may be about to pull the drawer up. */
    onDrawerDragStart: () -> Unit,
    /** Vertical movement in pixels; negative is upward. */
    onDrawerDrag: (Float) -> Unit,
    /** Lift-off, with the fling velocity in pixels per second. */
    onDrawerDragEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Aura.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Which ink prints on top of the picture. Null — the usual case — means the
    // theme decides, exactly as it always has. It is deliberately scoped to the
    // things that sit directly on the wallpaper: the cards below carry their own
    // surface, and forcing white text onto a white card only made it vanish.
    //
    // The animated backdrops carry their own light-or-dark answer: a night sky
    // wants white ink even under a light theme, and a dawn wants dark ink even
    // under a dark one. That answer overrides the [wallpaperInk] choice, because
    // the setting only exists for photographs — nothing on the animated picker
    // makes an ink question.
    val wallpaperInk: Boolean? = when {
        settings.wallpaperPath.isNotEmpty() -> when (settings.wallpaperInk) {
            com.aura.launcher.data.WallpaperInk.LIGHT -> true
            com.aura.launcher.data.WallpaperInk.DARK -> false
            com.aura.launcher.data.WallpaperInk.FOLLOW -> null
        }
        settings.animatedWallpaper != com.aura.launcher.data.AnimatedWallpaper.NONE ->
            inkForAnimatedWallpaper(settings.animatedWallpaper, colors.isDark)
        else -> null
    }

    val apps by app.appRepository.apps.collectAsState()
    // Read from the application's copy, which is populated before the first
    // frame — see AuraApp.favoriteKeys. Collecting the repository flow here
    // meant an empty first frame and a row that animated in behind it.
    val favoriteKeys by app.favoriteKeys.collectAsState()
    val hidden by app.settingsRepository.hidden.collectAsState(initial = emptySet())

    // Which letters would actually land somewhere in the drawer.
    val activeLetters = remember(apps, hidden) {
        apps.asSequence().filter { it.key !in hidden }.map { it.bucket }.toSet()
    }

    // Only what the user pinned. An automatic "most used" list meant apps
    // appearing on a home screen nobody put them on — the opposite of the point.
    val favorites: List<AppEntry> = remember(apps, favoriteKeys) {
        val byKey = apps.associateBy { it.key }
        // Capped: the list sits in fixed space above the ask bar, and an
        // unbounded one would push the bar off the bottom of the screen.
        favoriteKeys.mapNotNull { byKey[it] }.take(MAX_FAVORITES)
    }

    var selectedApp by remember { mutableStateOf<AppEntry?>(null) }
    var daySheetOpen by remember { mutableStateOf(false) }
    var oneFingerGestureMode by remember { mutableStateOf(false) }
    /** True while the widgets are loose and can be dragged around. */
    var arranging by remember { mutableStateOf(false) }
    /** Bumped on every pick-up, to restart the quiet timer below. */
    var arrangeTick by remember { mutableStateOf(0) }

    // The mode puts itself away. A tap on bare wallpaper ends it, but that is
    // something the user has to know; a home screen left tilting because the
    // last drag ended on a widget is the launcher waiting for a gesture nobody
    // was told about.
    LaunchedEffect(arranging, arrangeTick) {
        if (!arranging) return@LaunchedEffect
        delay(ARRANGE_TIMEOUT_MS)
        arranging = false
    }

    var now by remember { mutableStateOf(Date()) }
    val clockLifecycle = LocalLifecycleOwner.current
    LaunchedEffect(clockLifecycle) {
        // Gated on the lifecycle, not just started once. A launcher's
        // composition is never torn down — the user opens another app and this
        // Activity is only stopped — so a bare `LaunchedEffect(Unit)` loop goes
        // on ticking all day behind whatever they are actually using. Nobody
        // reads a clock they cannot see, and the first thing it does on the way
        // back is read the time again.
        clockLifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = Date()
                // Tick on the minute boundary: the clock shows minutes, and a
                // per-second wakeup on the home screen is pure battery cost.
                val millisIntoMinute = System.currentTimeMillis() % 60_000
                delay(60_000 - millisIntoMinute + 50)
            }
        }
    }

    val timeText = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) }
    val dateText = remember(now) {
        SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
            .replaceFirstChar { it.uppercase() }
    }

    // Coming home. Every time the launcher comes back into view — pressing
    // home from an app, turning the screen on — the screen settles in: a
    // breath of scale and light, so arriving reads as arriving rather than as
    // a still picture that was there all along. Short and damped: it has to be
    // over before a thumb reaches the first app.
    val arrival = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(clockLifecycle) {
        clockLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            arrival.snapTo(0f)
            arrival.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 320f))
        }
    }

    GestureSurface(
        app = app,
        oneFingerMode = oneFingerGestureMode,
        onOneFingerModeChange = { oneFingerGestureMode = it },
        modifier = modifier.fillMaxSize(),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val a = arrival.value
                val scale = 0.96f + 0.04f * a
                scaleX = scale
                scaleY = scale
                alpha = 0.3f + 0.7f * a
            }
            .systemBarsPadding()
            .imePadding()
            // The drawer follows the thumb rather than waiting for it to lift.
            // `draggable` is what makes that possible: it reports every delta
            // as it happens and hands back a real fling velocity at the end,
            // which a plain drag detector cannot do.
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> onDrawerDrag(delta) },
                onDragStarted = { onDrawerDragStart() },
                onDragStopped = { velocity -> onDrawerDragEnd(velocity) },
            )
            .padding(horizontal = 26.dp),
    ) {
        Box(Modifier.weight(1f)) {
        // Behind every real home element. It therefore receives taps only on
        // bare wallpaper: clock, cards, notifications and favourites keep
        // their own click/double-click behaviour untouched.
        GestureBackgroundActivator(
            onActivate = { oneFingerGestureMode = true },
            modifier = Modifier.matchParentSize(),
        )

        // A tap on bare wallpaper puts the widgets back down. Nothing else
        // ends the mode: a button to leave it would be a button on the home
        // screen for the rest of the time.
        //
        // Behind the widgets, deliberately. Stretched over them it swallowed
        // the next tap anywhere on the screen — which is why the play button
        // stopped starting feeds once anything had been held.
        if (arranging) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { arranging = false })
                    },
            )
        }


        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(72.dp))

            // Everything below the top margin is a board the user owns: hold
            // a widget to pick it up, drag to reorder, drop one on another to
            // stack them. Holding the clock used to open the face picker —
            // that moved to Settings › Widgets, because one gesture cannot
            // both rearrange the screen and configure what is under the thumb.
            // Home 3, "Dia em linha": the calendar and the to-dos are stops on
            // the day's line under the clock, so they do not appear twice.
            val dayHome = settings.homeStyle == com.aura.launcher.data.HomeStyle.DAY
            val order = remember(settings.homeLayout, settings.hiddenWidgets, dayHome) {
                HomeLayout.resolve(settings.homeLayout) { widget ->
                    if (dayHome && (widget == com.aura.launcher.data.HomeWidget.CALENDAR || widget == com.aura.launcher.data.HomeWidget.TODO)) {
                        return@resolve false
                    }
                    // The clock's slot also carries the strip, so it survives
                    // the clock itself being turned off: somebody who wants the
                    // date and the battery without the big time still has a
                    // place for them.
                    if (widget == com.aura.launcher.data.HomeWidget.CLOCK) {
                        settings.showClock || settings.showDate ||
                            settings.showBattery || settings.showWeather
                    } else {
                        settings.shows(widget)
                    }
                }
            }
            fun writeLayout(next: List<com.aura.launcher.data.HomeWidget>) {
                scope.launch {
                    app.settingsRepository.update { it.copy(homeLayout = HomeLayout.encode(next)) }
                }
            }

            WidgetBoard(
                order = order,
                editing = arranging,
                onEditingChange = { on ->
                    arranging = on
                    if (on) arrangeTick++
                },
                onMove = { widget, to -> writeLayout(HomeLayout.move(order, widget, to)) },
            ) { widget ->
                @Composable
                fun Card(content: @Composable () -> Unit) {
                    OnWallpaperInk(wallpaperInk) { content() }
                }
                when (widget) {
                    com.aura.launcher.data.HomeWidget.CLOCK -> if (dayHome) {
                        OnWallpaperInk(wallpaperInk) {
                            Column {
                                CompactClock(
                                    timeText = timeText,
                                    dayText = remember(now) {
                                        SimpleDateFormat("EEEE, d", Locale.getDefault()).format(now)
                                    },
                                    onTime = { openClockApp(context) },
                                    onDay = { daySheetOpen = true },
                                )
                                Spacer(Modifier.height(22.dp))
                                DayLine(app = app, onOpenDay = { daySheetOpen = true })
                            }
                        }
                    } else OnWallpaperInk(wallpaperInk) {
                        Column {
                            if (settings.showClock) {
                                HomeClock(
                                    style = settings.clockStyle,
                                    now = now.time,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { openClockApp(context) },
                                )
                            }

                            // Under the clock, all on one line. The date, the
                            // battery and the temperature are the same kind of
                            // fact — small, current, read on the way past — and
                            // they used to be two thin lines above the clock
                            // doing the job of one below it.
                            StatusStrip(
                                app = app,
                                showBattery = settings.showBattery,
                                showWeather = settings.showWeather,
                                dateText = dateText.takeIf { settings.showDate },
                                onOpen = { daySheetOpen = true },
                            )
                        }
                    }

                    com.aura.launcher.data.HomeWidget.TIMER -> Card { TimerCard(app = app) }

                    com.aura.launcher.data.HomeWidget.TODO -> Card { TodoCard(app = app) }

                    com.aura.launcher.data.HomeWidget.NOW_PLAYING -> NowPlayingCard()

                    com.aura.launcher.data.HomeWidget.CALENDAR ->
                        CalendarPills(app = app, eventCount = settings.calendarEventCount)

                    com.aura.launcher.data.HomeWidget.NOTIFICATIONS -> NotificationList()

                    com.aura.launcher.data.HomeWidget.MAP -> Card { MapCard(app = app) }

                    com.aura.launcher.data.HomeWidget.NOW -> Card { NowCard(app = app) }

                    com.aura.launcher.data.HomeWidget.SKY -> Card { SkyCard() }

                    else -> Unit
                }
            }

            Spacer(Modifier.weight(1f))

            AnimatedVisibility(
                visible = favorites.isNotEmpty() && (!dayHome || settings.dayFavorites),
                enter = fadeIn(tween(240)) + slideInVertically { it / 4 },
                exit = fadeOut(tween(160)) + slideOutVertically { it / 4 },
            ) {
                OnWallpaperInk(wallpaperInk) {
                    FavoritesList(
                        entries = favorites,
                        onClick = { entry ->
                            scope.launch { app.settingsRepository.recordLaunch(entry.key) }
                            app.appRepository.launchFromTouch(entry)
                        },
                        onLongClick = { entry -> selectedApp = entry },
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
        }

        }


        Spacer(Modifier.height(14.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OnWallpaperInk(wallpaperInk) {
                DrawerHint(onClick = onOpenDrawer)
            }
        }

        Spacer(Modifier.height(14.dp))
    }

    // Above the rest so the edge always wins the gesture.
    HomeAlphabetEdge(
        activeLetters = activeLetters,
        onOpenAt = onOpenDrawerAt,
        onScrub = onEdgeScrub,
        // Bottom aligned to match the drawer's strip, which is where the
        // band sits so a thumb never has to reach for the first letters.
        modifier = Modifier.align(Alignment.BottomEnd).systemBarsPadding(),
    )
    }

    selectedApp?.let { entry ->
        AppActionsSheet(app = app, entry = entry, onDismiss = { selectedApp = null })
    }

    if (daySheetOpen) {
        DaySheet(app = app, onDismiss = { daySheetOpen = false })
    }


}

/**
 * Passively recognises two quick taps without consuming either one. A swipe
 * that starts on the same wallpaper still reaches the drawer's draggable.
 */
@Composable
private fun GestureBackgroundActivator(
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activate by rememberUpdatedState(onActivate)
    val maxDistance = with(LocalDensity.current) { DOUBLE_TAP_DISTANCE_DP.dp.toPx() }

    Box(
        modifier.pointerInput(maxDistance) {
            awaitPointerEventScope {
                var downAt = 0L
                var downPosition: Offset? = null
                var moved = false
                var previousTapAt = 0L
                var previousTapPosition: Offset? = null

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.changes.size != 1) {
                        downPosition = null
                        previousTapAt = 0L
                        previousTapPosition = null
                        continue
                    }

                    val change = event.changes.first()
                    when {
                        change.pressed && !change.previousPressed -> {
                            downAt = change.uptimeMillis
                            downPosition = change.position
                            moved = false
                        }

                        change.pressed && change.previousPressed -> {
                            val origin = downPosition
                            if (origin != null && (change.position - origin).getDistance() > maxDistance) {
                                moved = true
                            }
                        }

                        !change.pressed && change.previousPressed -> {
                            val origin = downPosition
                            val quick = !moved && origin != null &&
                                change.uptimeMillis - downAt <= MAX_TAP_MS
                            val previous = previousTapPosition
                            val doubleTap = quick && previous != null &&
                                change.uptimeMillis - previousTapAt <= DOUBLE_TAP_GAP_MS &&
                                (change.position - previous).getDistance() <= maxDistance

                            if (doubleTap) {
                                activate()
                                previousTapAt = 0L
                                previousTapPosition = null
                            } else if (quick) {
                                previousTapAt = change.uptimeMillis
                                previousTapPosition = change.position
                            } else {
                                previousTapAt = 0L
                                previousTapPosition = null
                            }
                            downPosition = null
                        }
                    }
                }
            }
        },
    )
}

private const val MAX_TAP_MS = 260L
private const val DOUBLE_TAP_GAP_MS = 360L
private const val DOUBLE_TAP_DISTANCE_DP = 52

/** Fits comfortably above the ask bar on a short phone. */
private const val MAX_FAVORITES = 8

/** How long the board stays loose after the last widget was put down. */
private const val ARRANGE_TIMEOUT_MS = 5_000L

/**
 * Opens whatever the phone uses as its clock.
 *
 * `ACTION_SHOW_ALARMS` is the documented way in, but not every clock app
 * declares it — so if nothing handles it, the launcher falls back to opening
 * the clock app by package, then to the alarm list.
 */
private fun openClockApp(context: android.content.Context) {
    val attempts = listOf(
        Intent(AlarmClock.ACTION_SHOW_ALARMS),
        Intent(AlarmClock.ACTION_SET_ALARM),
    )
    for (intent in attempts) {
        val handled = intent.resolveActivity(context.packageManager) != null
        if (handled) {
            val ok = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
    }
    // Nothing declared the clock intents; try the usual packages by name.
    for (pkg in CLOCK_PACKAGES) {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
        val ok = runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (ok) return
    }
}

private val CLOCK_PACKAGES = listOf(
    "com.google.android.deskclock",
    "com.android.deskclock",
    "com.sec.android.app.clockpackage",
    "com.miui.clock",
    "com.oneplus.deskclock",
    "com.coloros.alarmclock",
)

@Composable
private fun DrawerHint(onClick: () -> Unit, enabled: Boolean = true) {
    val colors = Aura.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            // Faded out but still laid out, so it must stop being tappable
            // rather than sit there as an invisible target.
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowUp,
            contentDescription = androidx.compose.ui.res.stringResource(com.aura.launcher.R.string.home_open_drawer_cd),
            tint = colors.inkFaint,
            modifier = Modifier.size(22.dp).alpha(0.7f),
        )
    }
}

/**
 * The ink each animated backdrop asks for.
 *
 * Not a decision the user makes, because none of these backdrops has an
 * ambiguous answer: a night sky is dark whichever theme is on, and a page of
 * paper is light whichever theme is on. Returning true means "light ink" (the
 * scheme's near-white), false means "dark ink" (the near-black), and null
 * means "follow the current theme" — which is what a scheme whose fond is the
 * theme's own paper wants.
 */
private fun inkForAnimatedWallpaper(
    kind: com.aura.launcher.data.AnimatedWallpaper,
    isDark: Boolean,
): Boolean? = when (kind) {
    com.aura.launcher.data.AnimatedWallpaper.NONE -> null
    // Both of these are photographs of a night, and a night does not turn pale
    // because the launcher is set to light: the fond is dark, so the ink is
    // light. It is the same bargain a photographic wallpaper already makes —
    // choosing a picture is choosing the brightness behind the clock.
    com.aura.launcher.data.AnimatedWallpaper.NIGHT_SKY,
    com.aura.launcher.data.AnimatedWallpaper.CONSTELLATION -> true
}
