package com.aura.launcher.ui.drawer

import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.composed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aura.launcher.AuraApp
import com.aura.launcher.apps.AppEntry
import com.aura.launcher.data.Settings
import com.aura.launcher.ui.components.AppActionsSheet
import com.aura.launcher.ui.components.AppRow
import com.aura.launcher.ui.components.SectionLabel
import com.aura.launcher.ui.theme.Aura
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.aura.launcher.R
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow

/**
 * The list index each letter's section begins at.
 *
 * Every section contributes its header plus its rows, so the running total has
 * to count both. Getting this wrong does not fail loudly — it silently scrolls
 * to the wrong place — which is why it lives out here where a test can reach it.
 */
internal fun sectionOffsets(grouped: Map<String, List<AppEntry>>): Map<String, Int> {
    var position = 0
    return buildMap {
        for ((letter, entries) in grouped) {
            put(letter, position)
            position += 1 + entries.size
        }
    }
}

@Composable
fun AppDrawerScreen(
    app: AuraApp,
    settings: Settings,
    /** Letter the home-edge gesture asked for; the list opens there. */
    initialLetter: String? = null,
    /**
     * Whether the drawer is on screen at all.
     *
     * The panel is never torn down — it stays composed off the bottom of the
     * screen so opening it costs nothing — so the reset that used to come free
     * with being rebuilt has to happen here instead.
     */
    visible: Boolean = true,
    /** Thumb position handed over by the home screen's edge; see AlphabetIndex. */
    scrubFraction: Float? = null,
    /**
     * How far open the panel is, 0 to 1.
     *
     * Used only for depth: the contents rise a little slower than the surface
     * carrying them, so the panel reads as something arriving rather than a
     * picture being slid up.
     */
    progress: Float = 1f,
    /** A finger has gone down on the panel's own drag area. */
    onDragStart: () -> Unit = {},
    /** Vertical movement in pixels; positive is downward, towards closed. */
    onDrag: (Float) -> Unit = {},
    /** Lift-off, with the fling velocity in pixels per second. */
    onDragEnd: (Float) -> Unit = {},
    onClose: () -> Unit,
    /** An app was started from here. Defaults to closing; see PanelSwipeState.closeWhenHidden. */
    onLaunched: () -> Unit = onClose,
    onOpenSettings: () -> Unit,
) {
    val colors = Aura.colors
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = androidx.compose.ui.platform.LocalContext.current

    val apps by app.appRepository.apps.collectAsState()
    val index by app.semanticIndex.collectAsState()
    val hidden by app.settingsRepository.hidden.collectAsState(initial = emptySet())
    // Reads the "when was this last opened" list, not the "how much has this
    // been opened" one. The strip is meant to answer "bring the last app back"
    // at a glance — a rank that fades over days is the wrong shape for that:
    // the app you were in a minute ago is not necessarily the app you have
    // touched most since the launcher was installed.
    val recentKeys by app.settingsRepository.recentlyLaunched.collectAsState(initial = emptyList())

    var query by remember { mutableStateOf("") }

    /** The letter a thumb is on right now; the list narrows to it while set. */
    var scrubLetter by remember { mutableStateOf<String?>(null) }
    var selectedApp by remember { mutableStateOf<AppEntry?>(null) }
    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val unhidden = remember(apps, hidden) { apps.filter { it.key !in hidden } }

    /**
     * The last few apps this person opened, newest first.
     *
     * Hidden apps are left out: someone who took an app out of the list did
     * not mean for it to jump to the top of it. Uninstalled ones fall away on
     * their own, because a key with no app behind it resolves to nothing.
     */
    val recent: List<AppEntry> = remember(recentKeys, unhidden) {
        val byKey = unhidden.associateBy { it.key }
        recentKeys.mapNotNull { byKey[it] }.take(RECENT_COUNT)
    }

    // Hidden apps are missing from the list and present in the search.
    //
    // Hiding is about the length of the list, not about locking anything away.
    // Someone who hides a banking app they open twice a month still expects to
    // reach it by typing its name — filtering it out of the results too made
    // "hidden" mean "gone", which is a different feature and one nobody asked
    // for. The index is cheap enough to run on every keystroke, so there is no
    // debounce and results never lag behind the text.
    val results: List<AppEntry> = remember(query, index, unhidden) {
        if (query.isBlank()) unhidden else index.search(query, limit = 40).map { it.entry }
    }

    LaunchedEffect(query) { if (query.isNotEmpty()) listState.scrollToItem(0) }

    // Back to a clean drawer once it is off screen. Coming back to yesterday's
    // search still on the field is worse than a fresh list, and the reset is
    // invisible because nothing is being drawn by then.
    LaunchedEffect(visible) {
        if (!visible) {
            query = ""
            selectedApp = null
            // A drawer that closed mid-scrub would come back still narrowed to
            // one letter, with no thumb on the strip to explain why.
            scrubLetter = null
            focusManager.clearFocus()
            if (initialLetter == null) listState.scrollToItem(0)
        }
    }

    // The drawer opens with nothing focused and no keyboard.
    //
    // It used to focus the field and raise the keyboard on every swipe, on the
    // grounds that opening the drawer is usually the start of looking for
    // something. It is not: most of the time the app wanted is one of the first
    // few in the list, and a keyboard covering two thirds of that list is in the
    // way of the thing it was trying to help with. Tapping the field still does
    // exactly what tapping a field should.

    // Pulling down when the list is already at the top brings the drawer back —
    // under the thumb, the whole way.
    //
    // This used to wait for 150 pixels of overscroll and then call `onClose`,
    // which is a spring, not a drag: the panel left the finger behind and the
    // rest of the gesture happened on its own. That was the difference the user
    // could feel between this and the settings sheet, which has no list at the
    // top to swallow the gesture and so reaches the plain drag handler below.
    //
    // So the connection drives the same drag the handler does. It takes the
    // overscroll the list could not use, feeds it in as movement, and — this is
    // the part that makes it feel attached — gives back exactly as much as it
    // took if the finger reverses, handing the gesture back to the list at the
    // moment the panel is whole again.
    val pullToClose = remember(onDragStart, onDrag, onDragEnd, focusManager, keyboard) {
        object : NestedScrollConnection {
            /** How much of the panel this gesture has pulled down so far. */
            private var pulled = 0f
            private var claimed = false

            /**
             * Reaching for the list means you have stopped typing.
             *
             * Hooked to the nested-scroll signal rather than to the list's
             * `isScrollInProgress`, because that flag is also raised by the
             * jump-to-top this screen performs on every keystroke — which
             * would take the keyboard away mid-word.
             */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f && searchFocused) {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
                if (!claimed || source != NestedScrollSource.UserInput) return Offset.Zero

                // Reversing upward: pay back what the panel borrowed, then let
                // go. Without this the list would stay frozen behind a panel
                // that has nothing left to give.
                if (available.y < 0f && pulled > 0f) {
                    val give = minOf(pulled, -available.y)
                    pulled -= give
                    onDrag(-give)
                    if (pulled <= 0f) release(Velocity.Zero)
                    return Offset(0f, -give)
                }
                // Still pulling down, and the panel already owns the gesture.
                if (available.y > 0f) {
                    pulled += available.y
                    onDrag(available.y)
                    return available
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                // The list has hit its top and there is movement left over.
                if (!claimed) {
                    claimed = true
                    keyboard?.hide()
                    onDragStart()
                }
                pulled += available.y
                onDrag(available.y)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!claimed) return Velocity.Zero
                release(available)
                return available
            }

            private fun release(velocity: Velocity) {
                claimed = false
                pulled = 0f
                onDragEnd(velocity.y)
            }
        }
    }

    // Read in the draw phase by every row's cascade, so a frame of the drag
    // redraws the rows instead of recomposing the list.
    val progressNow by androidx.compose.runtime.rememberUpdatedState(progress)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // A short lag behind the panel, gone by the time it is open.
                translationY = (1f - progress.coerceIn(0f, 1f)) * CONTENT_LAG_PX
            }
            // The cutout has to be in here, not just the system bars.
            //
            // With "hide the status bar" on, the status-bar inset is genuinely
            // zero — we asked for it to be — and `systemBarsPadding` alone then
            // has nothing to keep the search field clear of, so it slid up
            // behind the camera. The cutout is reported whether the bar is drawn
            // or not, which is exactly the guarantee needed here.
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .imePadding()
            .nestedScroll(pullToClose)
            // Swipe down pulls the drawer back, mirroring the swipe up that
            // brought it in — and following the thumb the whole way rather than
            // waiting for it to lift. The list has its own scrolling, so this
            // only ever sees drags that start outside it.
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> onDrag(delta) },
                onDragStarted = {
                    keyboard?.hide()
                    onDragStart()
                },
                onDragStopped = { velocity -> onDragEnd(velocity) },
            ),
    ) {
        Spacer(Modifier.height(14.dp))

        SearchBar(
            query = query,
            onQueryChange = { query = it },
            onClear = { query = "" },
            onSubmit = {
                val top = results.firstOrNull()
                if (top != null) {
                    scope.launch { app.settingsRepository.recordLaunch(top.key) }
                    app.appRepository.launchFromTouch(top)
                    keyboard?.hide()
                    onLaunched()
                } else {
                    // No app: hiding the keyboard is the whole of it.
                    keyboard?.hide()
                }
            },
            onSettings = onOpenSettings,
            onSystemSettings = {
                runCatching {
                    app.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onFocusChange = { searchFocused = it },
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        // The three apps most used, only while the field is empty.
        //
        // This row used to hold the last three apps reached through search,
        // which is a narrower thing than it looks: it is a record of what was
        // hard to find, not of what is used. An app opened by typing its name
        // once sat here for days; the one opened ten times a day from the list
        // never appeared at all. Ranking by use answers the question the row is
        // actually in front of — "the ones I always want" — and the ranking
        // fades, so it follows the person rather than remembering them.
        if (query.isBlank() && recent.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            RecentRow(
                entries = recent,
                onPick = { entry ->
                    scope.launch { app.settingsRepository.recordLaunch(entry.key) }
                    app.appRepository.launchFromTouch(entry)
                    keyboard?.hide()
                    onLaunched()
                },
            )
        }

        Spacer(Modifier.height(10.dp))

        if (results.isEmpty()) {
            EmptyState(query = query, modifier = Modifier.fillMaxSize())
            return@Column
        }

        // Where each letter's section starts, so the scrubber can jump straight
        // to it instead of walking the list.
        val grouped = remember(results, query) {
            if (query.isBlank()) results.groupBy { it.bucket } else emptyMap()
        }
        val letterOffsets = remember(grouped) { sectionOffsets(grouped) }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 10.dp, end = 46.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (query.isBlank()) {
                    // While a thumb is on the strip the list narrows to the one
                    // letter it is pointing at. Scrolling a full A–Z list under
                    // a moving finger meant the thing you were aiming for swept
                    // past before you could read it; showing only that letter's
                    // apps makes the strip a picker rather than a fast scroll,
                    // and the handful of names left are legible at a glance.
                    //
                    // `scrubFraction` covers the other half of it: a drag that
                    // began on the home screen's edge never touches this strip,
                    // so without reading the handed-over position the list
                    // would stay full for the one gesture people use most.
                    val pointedAt = scrubLetter ?: initialLetter?.takeIf { scrubFraction != null }
                    val shown = pointedAt
                        ?.let { letter -> grouped.filterKeys { it == letter } }
                        ?: grouped

                    // No animateItem here, and that is a bug fix rather than a
                    // preference. A disappearing item keeps being composed and
                    // drawn while it animates out; narrowing to a letter
                    // replaces the entire list, so scrubbing quickly stacked
                    // one leaving set on top of the next arriving one and
                    // printed "Galeria" over "Gravador de voz". Toggling the
                    // modifier off mid-scrub was worse — it orphaned whatever
                    // was already in flight, which is where the name that
                    // stuck to the screen came from. A list that is replaced
                    // outright has nothing to animate between.
                    for ((letter, entries) in shown) {
                        item(key = "header-$letter") {
                            // The list exactly as it was in 1.125.0 — the
                            // Fraunces drop caps, the 7–13dp spacings and the
                            // Light names were all tried in between and the
                            // Miguel asked for the original back.
                            SectionLabel(
                                text = letter,
                                modifier = Modifier
                                    .cascade { progressNow }
                                    .padding(start = 18.dp, top = 16.dp, bottom = 6.dp),
                            )
                        }
                        items(entries, key = { it.key }) { entry ->
                            AppRowItem(
                                app, entry, scope, onLaunched,
                                modifier = Modifier.cascade { progressNow },
                            ) { selectedApp = entry }
                        }
                    }
                } else {
                    // Typing reorders the whole list on every keystroke. Without
                    // this the rows teleport; with it they slide past each other,
                    // which is also what makes it readable while it changes.
                    items(results, key = { it.key }) { entry ->
                        AppRowItem(
                            app, entry, scope, onLaunched,
                            hidden = entry.key in hidden,
                            modifier = Modifier.animateItem(),
                        ) { selectedApp = entry }
                    }
                }

                item(key = "tail-space") { Spacer(Modifier.height(40.dp)) }
            }

            // Jump before the first frame is drawn, so the drawer appears
            // already at the right letter rather than visibly scrolling to it.
            //
            // While the home-edge thumb is still down the list is narrowed to
            // that one letter, and `letterOffsets` counts positions in the
            // *full* list — using it there scrolled a short section to an
            // index past its end. That is the other half of the mess a drag
            // from the home screen made: a section left scrolled off its own
            // top, with the previous letter's rows still animating over it.
            LaunchedEffect(initialLetter, letterOffsets, scrubFraction != null) {
                if (initialLetter == null) return@LaunchedEffect
                if (scrubFraction != null) {
                    listState.scrollToItem(0)
                } else {
                    letterOffsets[initialLetter]?.let { listState.scrollToItem(it) }
                }
            }

            if (query.isBlank() && grouped.size > 1) {
                AlphabetIndex(
                    // Only the letters that lead somewhere. A strip of 27 with
                    // three dead ones in it is three places a thumb can land
                    // and be ignored.
                    letters = remember(grouped) { ALPHABET.filter { it in grouped.keys } },
                    activeLetters = grouped.keys,
                    // Nothing to scroll to while the list is narrowed: it holds
                    // that letter and nothing else, so the section is already
                    // the whole page. This used to jump to the letter's offset
                    // in the *full* list — index 40-odd for C — which on a
                    // list of eleven items landed at the far end of it. That
                    // is why a big section opened half way down instead of at
                    // the top.
                    onLetter = {},
                    onScrubLetter = { letter ->
                        scrubLetter = letter
                        // Back to the top on every letter, because the content
                        // underneath has been replaced wholesale.
                        if (letter != null) scope.launch { listState.scrollToItem(0) }
                    },
                    externalFraction = scrubFraction,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 2.dp, top = 4.dp, bottom = 16.dp),
                )
            }
        }
    }

    selectedApp?.let { entry ->
        AppActionsSheet(app = app, entry = entry, onDismiss = { selectedApp = null })
    }
}

@Composable
private fun AppRowItem(
    app: AuraApp,
    entry: AppEntry,
    scope: kotlinx.coroutines.CoroutineScope,
    onClose: () -> Unit,
    hidden: Boolean = false,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit,
) {
    AppRow(
        modifier = modifier,
        entry = entry,
        hidden = hidden,
        onClick = {
            scope.launch { app.settingsRepository.recordLaunch(entry.key) }
            app.appRepository.launchFromTouch(entry)
            onClose()
        },
        onLongClick = onLongPress,
    )
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    onSettings: () -> Unit,
    onSystemSettings: () -> Unit,
    onFocusChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = Aura.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(26.dp))
                .padding(horizontal = 18.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.inkFaint,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(13.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(com.aura.launcher.R.string.drawer_search_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.inkFaint,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { onFocusChange(it.isFocused) },
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = androidx.compose.ui.res.stringResource(com.aura.launcher.R.string.drawer_clear_cd),
                    tint = colors.inkFaint,
                    modifier = Modifier
                        .size(19.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onClear),
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        CircleButton(
            icon = Icons.Rounded.Settings,
            tint = colors.inkMuted,
            onClick = onSystemSettings,
            contentDescription = androidx.compose.ui.res.stringResource(
                com.aura.launcher.R.string.drawer_system_settings_cd,
            ),
        )
        Spacer(Modifier.width(6.dp))
        CircleButton(
            icon = Icons.Rounded.Tune,
            tint = colors.inkMuted,
            onClick = onSettings,
            contentDescription = androidx.compose.ui.res.stringResource(
                com.aura.launcher.R.string.settings_title,
            ),
        )
    }
}

/**
 * The last few apps this person opened, as pill-shaped chips.
 *
 * No leading label. The strip used to be introduced by the word "most used" in
 * the muted ink — a rare launcher affordance where the widest thing on the row
 * was a caption for the buttons beside it. The pills say what they are on
 * their own: three apps that would not be there if they had not been opened
 * recently, in front of a person who knows what they have been doing on their
 * phone.
 */
@Composable
private fun RecentRow(
    entries: List<AppEntry>,
    onPick: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Aura.colors
    // A carousel rather than a row: a row of four cut the last name in half
    // ("Bib") as soon as one of them was long. Every name is whole now, and
    // the list runs off the edge — the fade there says there is more.
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = 28.dp.toPx()
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0f to androidx.compose.ui.graphics.Color.Black,
                        1f to androidx.compose.ui.graphics.Color.Transparent,
                        startX = size.width - fade,
                        endX = size.width,
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - fade, 0f),
                    size = androidx.compose.ui.geometry.Size(fade, size.height),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            },
        contentPadding = PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(entries, key = { it.key }) { entry ->
            val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val press by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (pressed) 0.92f else 1f,
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 800f),
                label = "recentPress",
            )
            Row(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = press
                        scaleY = press
                    }
                    // Just the names — no pill, no border. The padding stays
                    // as the touch target and as the gap between them.
                    .clickable(interactionSource = interaction, indication = null) { onPick(entry) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.displayLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.ink,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    contentDescription: String,
) {
    val colors = Aura.colors
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(26.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}


@Composable
private fun EmptyState(query: String, modifier: Modifier = Modifier) {
    val colors = Aura.colors
    Column(
        modifier = modifier.padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (query.isBlank()) androidx.compose.ui.res.stringResource(com.aura.launcher.R.string.drawer_no_apps)
            else androidx.compose.ui.res.stringResource(com.aura.launcher.R.string.drawer_no_matches, query),
            style = MaterialTheme.typography.titleMedium,
            color = colors.inkMuted,
        )
    }
}

/**
 * How far the drawer's contents trail the panel as it opens.
 *
 * Small on purpose. Enough to read as depth, not enough to notice as a second
 * thing moving.
 */
private const val CONTENT_LAG_PX = 90f

/**
 * Extra trail for a row at the very bottom of the screen, on top of
 * [CONTENT_LAG_PX]. Rows in between trail in proportion to how far down they
 * sit, so opening the drawer fans the list in from the top — the names arrive
 * one after another, like cards being set down, instead of as one slab.
 */
private const val CASCADE_PX = 150f

/**
 * Trails the panel by how far down the screen this row sits. Pure draw-phase
 * work: the position comes from placement, the progress from a state read in
 * the layer, and nothing recomposes while the drawer moves.
 */
private fun Modifier.cascade(progress: () -> Float): Modifier = composed {
    var y by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val screen = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    this
        .onPlaced { y = it.positionInRoot().y }
        .graphicsLayer {
            val remaining = 1f - progress().coerceIn(0f, 1f)
            if (remaining <= 0f) return@graphicsLayer
            val depth = (y / screen).coerceIn(0f, 1f)
            // Squared, so the trail is gone well before the panel lands and
            // nothing is still settling when the thumb lets go.
            val lag = remaining * remaining
            translationY = lag * CASCADE_PX * depth
            alpha = 1f - (lag * depth * 0.8f).coerceIn(0f, 1f)
        }
}

/**
 * How many apps the row above the list holds.
 *
 * Three. It is a shortcut, not a second app list — a row long enough to read
 * as a list is one the eye has to search, which is what the list below is for.
 */
private const val RECENT_COUNT = 4
