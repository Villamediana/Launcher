package com.aura.launcher.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.palette.graphics.Palette
import com.aura.launcher.media.MediaHub
import com.aura.launcher.media.MediaWatcher
import com.aura.launcher.media.NowPlaying
import com.aura.launcher.ui.theme.Aura
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * What is playing, sitting directly on the wallpaper.
 *
 * There is no card. That is the point, and it took three tries to get to: a
 * surface, then a thinner surface, then glass — each one a box drawn around
 * content that did not need a box. The home screen already has a background,
 * and the artwork is the only thing here with an edge worth having. So the
 * container is gone entirely and what is left is a sleeve, two lines and three
 * glyphs, arranged like the media widgets this was measured against.
 *
 * The sleeve carries the weight. At 104dp it is the largest thing on the screen
 * after the clock, which is right — it is a picture, and everything else here is
 * a label for it. A record sleeve was never the branding problem app icons are;
 * it is the thing itself.
 *
 * The title scrolls when it does not fit. Truncating "Velha Infância -
 * Tribalistas Ao Vivo" to "Velha Infância - Tribalis…" loses the half that says
 * which recording it is, and on a surface with no other job the movement costs
 * nothing.
 *
 * ## The gestures
 *
 * A normal swipe changes track; a long throw past 55% of the width dismisses.
 * One axis for both is deliberate — the alternative was dismissing upward, and
 * up on the home screen already means "open the drawer". Long press dismisses
 * too.
 *
 * There is no progress line. It was here, draggable, and it went the same way
 * the card did: a home screen is not a player, and a bar that has to be looked
 * at to mean anything is a second thing asking for attention next to the one
 * that already earned it.
 */
@Composable
fun NowPlayingCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by MediaHub.nowPlaying.collectAsState()

    // The grant may have been given while the launcher was in the background,
    // and there is no callback for "a permission you asked for was just given".
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) MediaWatcher.start(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Held so it animates out with its content intact rather than emptying
    // first and then collapsing into a blank strip.
    var shown by remember { mutableStateOf<NowPlaying?>(null) }
    LaunchedEffect(state) { state?.let { shown = it } }

    AnimatedVisibility(
        visible = state != null,
        enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = 1600f)) + fadeIn(tween(220)),
        exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 1600f)) + fadeOut(tween(160)),
        modifier = modifier,
    ) {
        shown?.let { Player(it) }
    }
}

@Composable
private fun Player(now: NowPlaying) {
    val colors = Aura.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // With no surface to tint, the artwork's colour has one job left: warming
    // the accent, so the controls belong to the picture beside them.
    var tint by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(now.artwork) {
        tint = now.artwork?.let { art ->
            runCatching {
                val palette = Palette.from(art).clearFilters().maximumColorCount(16).generate()
                (palette.lightVibrantSwatch ?: palette.vibrantSwatch ?: palette.mutedSwatch)
                    ?.let { Color(it.rgb) }
            }.getOrNull()
        }
    }
    val targetAccent = tint?.let { lerp(colors.accent, it, 0.35f) } ?: colors.accent
    val accentR by animateFloatAsState(targetAccent.red, tween(420), label = "ar")
    val accentG by animateFloatAsState(targetAccent.green, tween(420), label = "ag")
    val accentB by animateFloatAsState(targetAccent.blue, tween(420), label = "ab")
    val accent = Color(accentR, accentG, accentB)

    val offsetX = remember { Animatable(0f) }
    var widthPx by remember { mutableStateOf(1f) }
    val skipThreshold = with(density) { 56.dp.toPx() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 4.dp)
            .graphicsLayer {
                translationX = offsetX.value
                alpha = 1f - (abs(offsetX.value) / widthPx).coerceIn(0f, 0.75f)
            }
            .pointerInput(now.packageName, now.isStale) {
                widthPx = size.width.toFloat().coerceAtLeast(1f)
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val thrown = offsetX.value
                        scope.launch {
                            when {
                                abs(thrown) > widthPx * DISMISS_FRACTION -> {
                                    offsetX.animateTo(if (thrown > 0) widthPx else -widthPx, tween(180))
                                    MediaWatcher.dismissCurrent()
                                    offsetX.snapTo(0f)
                                }
                                thrown <= -skipThreshold && !now.isStale -> {
                                    MediaWatcher.skipNext()
                                    offsetX.animateTo(0f, SETTLE)
                                }
                                thrown >= skipThreshold && !now.isStale -> {
                                    MediaWatcher.skipPrevious()
                                    offsetX.animateTo(0f, SETTLE)
                                }
                                else -> offsetX.animateTo(0f, SETTLE)
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, SETTLE) } },
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                }
            }
            .pointerInput(now.packageName) {
                detectTapGestures(
                    onTap = { MediaWatcher.openPlayer(context) },
                    onLongPress = { MediaWatcher.dismissCurrent() },
                )
            },
    ) {
        Crossfade(
            targetState = now.artwork to now.isVideo,
            animationSpec = tween(320),
            label = "art",
        ) { (art, isVideo) -> Artwork(art, isVideo) }

        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f)) {
            Crossfade(targetState = now.title, animationSpec = tween(280), label = "title") { title ->
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.ink,
                    maxLines = 1,
                    // Scrolls only when it overflows; a title that fits sits
                    // perfectly still.
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
            }
            if (now.subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Crossfade(targetState = now.subtitle, animationSpec = tween(280), label = "sub") { sub ->
                    Text(
                        text = sub,
                        fontSize = 12.sp,
                        color = colors.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Controls(now, accent)
        }
    }
}

@Composable
private fun Artwork(art: android.graphics.Bitmap?, isVideo: Boolean) {
    val colors = Aura.colors
    // Video keeps its own shape rather than being cropped square, which is the
    // one thing that makes this kind of widget look broken.
    val width = if (isVideo) 124.dp else 104.dp
    val height = if (isVideo) 70.dp else 104.dp

    Box(
        Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.paperDeep),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = width, height = height),
            )
        } else {
            Icon(
                imageVector = if (isVideo) Icons.Rounded.Videocam else Icons.Rounded.Headphones,
                contentDescription = null,
                tint = colors.inkFaint,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * Back, play/pause, forward.
 *
 * Play is a ring rather than a filled disc — on a surface with no background a
 * solid circle was the heaviest mark on the home screen, and an outline says
 * "primary" just as clearly at a fraction of the weight.
 *
 * None of the three consults the player's action mask. That mask is published
 * inconsistently — some players leave it empty and queue fine, some list only
 * what is possible at that instant, so "previous" disappears on the first track
 * of an album — and hiding a button on that evidence is what once left this
 * with nothing but play/pause.
 */
@Composable
private fun Controls(now: NowPlaying, accent: Color) {
    val colors = Aura.colors
    // A closed player leaves nothing to drive. The buttons stay in place so
    // nothing reflows, but they read as unavailable.
    val alpha = if (now.isStale) 0.35f else 1f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
    ) {
        PressIcon(Icons.Rounded.SkipPrevious, colors.inkMuted, accent, 22.dp, !now.isStale) {
            MediaWatcher.skipPrevious()
        }
        PressIcon(
            icon = if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            tint = accent,
            accent = accent,
            size = 19.dp,
            enabled = !now.isStale,
            ringed = true,
            crossfadeKey = now.isPlaying,
        ) { MediaWatcher.playPause() }
        PressIcon(Icons.Rounded.SkipNext, colors.inkMuted, accent, 22.dp, !now.isStale) {
            MediaWatcher.skipNext()
        }
    }
}

/**
 * A glyph that answers the finger.
 *
 * Three things happen on press, all of them cheap: it sinks to 84%, a soft
 * accent disc blooms behind it, and the phone ticks. Without them a tap either
 * changed the music or silently did not, with no moment in between that said
 * "heard you". The disc is drawn only while pressed, so nothing sits on the
 * home screen asking to be looked at.
 */
@Composable
private fun PressIcon(
    icon: ImageVector,
    tint: Color,
    accent: Color,
    size: Dp,
    enabled: Boolean,
    ringed: Boolean = false,
    crossfadeKey: Any? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 900f),
        label = "press",
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(if (pressed) 90 else 220),
        label = "glow",
    )

    val target = if (ringed) 44.dp else 40.dp
    Box(
        Modifier
            .size(target)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (glow > 0.01f) {
            Box(
                Modifier
                    .size(target)
                    .graphicsLayer { alpha = glow * 0.16f }
                    .clip(CircleShape)
                    .background(accent),
            )
        }
        if (ringed) {
            Box(Modifier.size(target).clip(CircleShape).border(1.5.dp, accent.copy(alpha = 0.55f), CircleShape))
        }
        if (crossfadeKey != null) {
            Crossfade(targetState = crossfadeKey, animationSpec = tween(180), label = "glyph") { state ->
                androidx.compose.runtime.key(state) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
                }
            }
        } else {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
        }
    }
}

/** How far across the row a throw has to travel before it means "dismiss". */
private const val DISMISS_FRACTION = 0.55f

private val SETTLE = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 700f)

/** Straight-line blend, so the artwork warms the accent instead of replacing it. */
private fun lerp(from: Color, to: Color, amount: Float): Color = Color(
    red = from.red + (to.red - from.red) * amount,
    green = from.green + (to.green - from.green) * amount,
    blue = from.blue + (to.blue - from.blue) * amount,
    alpha = from.alpha,
)
