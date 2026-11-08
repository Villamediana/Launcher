package com.aura.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aura.launcher.AuraApp
import com.aura.launcher.data.Settings
import com.aura.launcher.ui.drawer.AppDrawerScreen
import com.aura.launcher.ui.home.HomeScreen
import com.aura.launcher.ui.onboarding.OnboardingScreen
import com.aura.launcher.ui.settings.SettingsScreen
import com.aura.launcher.ui.theme.Aura
import com.aura.launcher.ui.theme.AuraTheme
import com.aura.launcher.ui.theme.paperBackground
import com.aura.launcher.ui.theme.rememberWallpaperBitmap
import com.aura.launcher.ui.theme.wallpaperBackground
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun AuraRoot(
    app: AuraApp,
    settings: Settings,
) {
    // Wraps everything, including onboarding, so the language chosen on the
    // first step applies to the second one — and to the rest of the app on the
    // frame after it is picked, rather than on the next launch.
    WithAppLanguage(settings.language ?: defaultLanguageFor(LocalContext.current)) {
    // The theme is the theme. The wallpaper ink override used to be applied
    // here, to the whole app, which meant choosing white text on a photograph
    // also turned the drawer and the settings panel dark. It belongs to the
    // home screen alone and is applied there — see OnWallpaperInk.
    AuraTheme(mode = settings.themeMode) {
        val colors = Aura.colors
        val scope = rememberCoroutineScope()

        var drawerLetter by remember { mutableStateOf<String?>(null) }
        // Live while a finger is on the home screen's hidden edge, so the
        // drawer's alphabet can follow a gesture it never received.
        var edgeScrub by remember { mutableStateOf<Float?>(null) }

        // Both sheets move the same way, off the same mechanism.
        val drawer = rememberPanelSwipeState()
        val settingsPanel = rememberPanelSwipeState()
        // True for the couple of frames settings is being composed, parked
        // off-screen, before it starts to rise. See onOpenSettings.
        var settingsWarming by remember { mutableStateOf(false) }

        // An app launched from the drawer grows over it; the drawer is put
        // away only once it is out of sight. See PanelSwipeState.closeWhenHidden.
        val rootLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(rootLifecycle) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) drawer.onHidden()
            }
            rootLifecycle.lifecycle.addObserver(observer)
            onDispose { rootLifecycle.lifecycle.removeObserver(observer) }
        }

        val paletteAnchor by com.aura.launcher.gestures.GestureHelp.showing.collectAsState()
        val paletteOpen = paletteAnchor != null

        // A launcher home is already the bottom of Android's navigation stack.
        // Letting Back fall through here makes the system finish this Activity
        // and immediately launch HOME again, which looks like a full refresh
        // (and can race live notification updates). Keep Back useful for every
        // overlay, then consume it as a no-op on the bare home screen.
        BackHandler(enabled = true) {
            when {
                // First, because it is the thing most recently put in front.
                paletteOpen -> com.aura.launcher.gestures.GestureHelp.hide()
                settingsPanel.isOpen -> settingsPanel.close()
                drawer.isOpen -> drawer.close()
                // Home is the end of the road; there is nowhere further back
                // to navigate and, importantly, nothing to recreate.
                else -> Unit
            }
        }

        val wallpaper = rememberWallpaperBitmap(settings.wallpaperPath)
        val liveWallpaper = settings.animatedWallpaper

        // Keep the pre-DataStore mirror in step with the setting, so the next
        // cold start paints its window background at this brightness.
        val appContext = LocalContext.current.applicationContext
        LaunchedEffect(settings.wallpaperDim) {
            com.aura.launcher.ui.theme.WallpaperStore.rememberDim(appContext, settings.wallpaperDim)
        }
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged {
                    drawer.heightPx = it.height
                    settingsPanel.heightPx = it.height
                }
                .let { m ->
                    // A photograph the user picked always wins. Below it, the
                    // ordering is deliberate: a chosen animated backdrop takes
                    // the frame, and only if neither is set does the paper look
                    // draw. This is why the "no picture" case is never a flat
                    // swatch — the animated NONE is exactly the paper look and
                    // the box below still draws when nothing else does.
                    when {
                        wallpaper != null -> m.wallpaperBackground(wallpaper, settings.wallpaperDim)
                        liveWallpaper != com.aura.launcher.data.AnimatedWallpaper.NONE -> m
                        else -> m.paperBackground(colors)
                    }
                }
        ) {
            // The animated backdrop, when picked, is a composable child rather
            // than a Modifier: it needs a Canvas that recomposes with the
            // shared infinite transition every frame, and a Modifier.drawWithCache
            // would freeze it as soon as the values it captured settled.
            if (wallpaper == null && liveWallpaper != com.aura.launcher.data.AnimatedWallpaper.NONE) {
                com.aura.launcher.ui.theme.AnimatedWallpaperBox(kind = liveWallpaper)
            }
            // The weather, live, over the wallpaper and under the home. Still
            // while a panel covers it: nobody is looking at the sky then.
            com.aura.launcher.ui.home.WeatherSky(
                app = app,
                enabled = settings.liveWeather,
                active = !drawer.isOpen && !settingsPanel.isOpen,
            )
            if (!settings.onboarded) {
                OnboardingScreen(
                    app = app,
                    settings = settings,
                    onFinished = {
                        scope.launch { app.settingsRepository.update { it.copy(onboarded = true) } }
                    },
                )
                return@Box
            }

            // Under a fully open drawer or settings the home screen is not
            // seen (alpha 0), but its orb, sky and wallpaper went on asking for
            // 24 frames a second the whole time: covered, they wait.
            val homeCovered by androidx.compose.runtime.remember {
                androidx.compose.runtime.derivedStateOf { drawer.progress >= 0.99f || settingsPanel.progress >= 0.99f }
            }
            androidx.compose.runtime.DisposableEffect(homeCovered) {
                val uncover = if (homeCovered) com.aura.launcher.ui.theme.Ambient.cover() else null
                onDispose { uncover?.invoke() }
            }

            // Home stays composed underneath. Swapping whole screens meant
            // rebuilding the drawer's list — hundreds of rows and their icons —
            // during the transition, which is what made opening it stutter.
            HomeScreen(
                app = app,
                settings = settings,
                onOpenDrawer = {
                    drawerLetter = null
                    drawer.open()
                },
                onOpenDrawerAt = { letter ->
                    drawerLetter = letter
                    drawer.open()
                },
                onEdgeScrub = { fraction -> edgeScrub = fraction },
                onDrawerDragStart = {
                    // Only an opening drag arrives at no particular letter; a
                    // dismissing one must leave the list where it is.
                    if (drawer.beginDrag()) drawerLetter = null
                },
                onDrawerDrag = { delta -> drawer.dragBy(delta) },
                onDrawerDragEnd = { velocity -> drawer.endDrag(velocity) },
                modifier = Modifier.graphicsLayer {
                    val scale = 1f - 0.05f * drawer.progress
                    scaleX = scale
                    scaleY = scale
                    // Gone by the time the panel is two thirds up, so the two
                    // surfaces are never both at full strength.
                    alpha = (1f - drawer.progress * 1.5f).coerceIn(0f, 1f)
                },
            )

            // Composed from the start and parked off the bottom of the screen.
            // Building it on demand meant the first swipe paid for hundreds of
            // rows before anything moved, which is most of what made opening it
            // feel like it took a second. Off-screen it draws nothing and
            // catches no touches.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = (1f - drawer.progress) * size.height
                        // Opaque almost immediately: the panel should look
                        // solid from the first few millimetres of the drag, not
                        // like something being faded in behind it.
                        alpha = (drawer.progress * 4f).coerceIn(0f, 1f)
                    }
                    .background(colors.paper)
            ) {
                // The same two-finger canvas the home screen has.
                //
                // Wrapped here rather than around everything, because "here" is
                // the other place a gesture makes sense. Over the settings
                // panel it would be drawing on top of a form; over the drawer it is the same wallpaper-and-icons
                // surface the gesture was designed for, and the drawer's own
                // one-finger scroll is untouched — the layer takes nothing
                // until a second finger lands.
                //
                // Only while the drawer is actually open. Parked off-screen it
                // catches no touches anyway, and arming a canvas that cannot be
                // reached is a subscription paid for nothing.
                com.aura.launcher.ui.home.GestureSurface(
                    app = app,
                    enabled = drawer.isOpen,
                    modifier = Modifier.fillMaxSize(),
                ) {
                AppDrawerScreen(
                    app = app,
                    settings = settings,
                    initialLetter = drawerLetter,
                    visible = drawer.isOpen,
                    scrubFraction = edgeScrub,
                    progress = drawer.progress,
                    onDragStart = {
                        if (drawer.beginDrag()) drawerLetter = null
                    },
                    onDrag = { delta -> drawer.dragBy(delta) },
                    onDragEnd = { velocity -> drawer.endDrag(velocity) },
                    onClose = { drawer.close() },
                    onLaunched = { drawer.closeWhenHidden() },
                    onOpenSettings = {
                        // Settings replaces the drawer; keeping both panels
                        // fully drawn and animating on top of one another made
                        // the first open needlessly heavy on slower phones.
                        //
                        // Settings rises over the drawer, and the drawer is
                        // put away only once it is covered. Closing it at the
                        // same time slid the home screen past on the way —
                        // settings looked like a detour through home.
                        //
                        // Composed first, moved second. Settings is built on
                        // demand, and building it used to happen on the very
                        // frame the panel started rising — so the heaviest
                        // frame of the whole transition was its first, and the
                        // slide visibly caught. Now the screen is composed
                        // parked below the edge, two frames go by, and only
                        // then does it start to move, with nothing left to
                        // build.
                        settingsWarming = true
                        scope.launch {
                            androidx.compose.runtime.withFrameNanos { }
                            androidx.compose.runtime.withFrameNanos { }
                            settingsPanel.open()
                            settingsWarming = false
                            androidx.compose.runtime.snapshotFlow { settingsPanel.isSettledOpen }
                                .first { it }
                            drawer.snapClosed()
                        }
                    },
                )
                }
            }

            // Built on demand, unlike the drawer. Settings is a long screen that
            // is opened rarely, so paying for it on the way in is the better
            // trade — the drawer is the one that had to be instant.
            if (settingsPanel.isOpen || settingsWarming) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = (1f - settingsPanel.progress) * size.height
                            alpha = (settingsPanel.progress * 4f).coerceIn(0f, 1f)
                        }
                        .background(colors.paper)
                ) {
                    SettingsScreen(
                        app = app,
                        settings = settings,
                        onDragStart = { settingsPanel.beginDrag() },
                        onDrag = { delta -> settingsPanel.dragBy(delta) },
                        onDragEnd = { velocity -> settingsPanel.endDrag(velocity) },
                        onClose = { settingsPanel.close() },
                    )
                }
            }

            // Over the launcher. Opened by a shape, from
            // wherever the hand was, so it belongs at the top rather than
            // inside whichever screen happened to be in front.
            com.aura.launcher.ui.home.GesturePalette(app)

            // The drawing board, above everything: it opens from wherever the
            // hand held still, and from settings. Its own Back comes first.
            val sketch by com.aura.launcher.sketch.SketchHost.open.collectAsState()
            sketch?.let { opening ->
                androidx.compose.runtime.key(opening) {
                    com.aura.launcher.ui.sketch.SketchScreen(app, opening) {
                        com.aura.launcher.sketch.SketchHost.close()
                    }
                }
            }

            // A thin band at the bottom that intercepts a short upward swipe
            // when a sheet is open. Composed only while there is a sheet to
            // close, so on the plain home screen the band does not exist and
            // Android's own bottom-edge gesture — the app switcher — reaches
            // the system unimpeded.
            //
            // The intended UX: one small flick up from just above the nav bar
            // closes the drawer or settings before it would otherwise have
            // opened Recents. A long or fast swipe still falls through to the
            // system, so the second flick brings up the app switcher as usual.
            if (drawer.isOpen || settingsPanel.isOpen) {
                var swipeAccum by remember { mutableStateOf(0f) }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(BOTTOM_SWIPE_BAND_DP.dp)
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                // Negative delta is upward. Accumulate; act when
                                // the upward travel crosses the trigger, or
                                // release when it gets too long — that is when
                                // the user meant the system gesture, not this.
                                swipeAccum += delta
                            },
                            onDragStarted = { swipeAccum = 0f },
                            onDragStopped = {
                                val upward = -swipeAccum
                                if (upward in SWIPE_CLOSE_MIN_PX..SWIPE_CLOSE_MAX_PX) {
                                    when {
                                        settingsPanel.isOpen -> settingsPanel.close()
                                        drawer.isOpen -> drawer.close()
                                    }
                                }
                                swipeAccum = 0f
                            },
                        )
                )
            }
        }
    }
}
}

/**
 * How tall the invisible bottom-edge zone is. Wide enough that a thumb reach
 * from the navigation bar always lands on it, narrow enough that the drawer's
 * own content is not covered by a phantom target.
 */
private const val BOTTOM_SWIPE_BAND_DP = 32

/**
 * The upward-drag distance range that counts as "close the panel". Below the
 * minimum the user was fidgeting; above the maximum they meant the app
 * switcher and the close would have been a hijack. Expressed in raw pixels
 * because the draggable delta arrives that way — 3 pixels per dp on a
 * mid-density phone, so 40-100 dp becomes roughly this range on hardware.
 */
private const val SWIPE_CLOSE_MIN_PX = 120f
private const val SWIPE_CLOSE_MAX_PX = 300f
