package com.aura.launcher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.apps.AppEntry
import com.aura.launcher.ui.theme.Aura

/**
 * A row in the drawer: the app's name, and nothing else.
 *
 * No icon, by design. A column of app icons is a column of other people's
 * branding — every one of them competing for attention with a different colour
 * and a different logo, which is the opposite of a quiet home screen. Names
 * alone also scan faster, because reading a word is quicker than recognising a
 * glyph you have to map back to a word anyway.
 *
 * It costs nothing, too: dropping icons removed the whole loading, caching and
 * drawable-to-Compose bridge that used to sit in the drawer's scroll path.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRow(
    entry: AppEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    /**
     * Whether this app is hidden from the drawer.
     *
     * It still turns up when searched for — hiding shortens the list, it does not
     * lock anything away — so the row says quietly that this is one of those,
     * rather than leaving the user wondering why something they hid is on screen.
     */
    hidden: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // Press feedback lives in the type itself: it dims and settles back rather
    // than flashing a highlight behind it.
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.42f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "rowAlpha",
    )
    val shift by animateFloatAsState(
        targetValue = if (pressed) 6f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "rowShift",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .graphicsLayer {
                this.alpha = alpha
                translationX = shift
            }
            // Roomier than it was. A list of names with no icons has nothing
            // else giving it rhythm, so the gap between rows is doing that job
            // on its own — and at the old spacing the drawer read as a dense
            // block of text rather than a list of things to aim at.
            .padding(horizontal = 18.dp, vertical = 17.dp),
    ) {
        Text(
            text = entry.displayLabel,
            // Set explicitly rather than by role. The drawer is a list of names
            // and nothing else, so the name is not a "title" sitting above
            // supporting text — it is the whole row, and it should be sized
            // like something you read from arm's length while scrolling past.
            fontSize = 19.sp,
            color = Aura.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (hidden) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = "hidden",
                style = MaterialTheme.typography.labelSmall,
                color = Aura.colors.inkFaint,
            )
        }
    }
}

/**
 * Favourites on the home screen, set larger than the drawer's rows so the two
 * lists never read as the same thing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesList(
    entries: List<AppEntry>,
    onClick: (AppEntry) -> Unit,
    onLongClick: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        for (entry in entries) {
            val interaction = remember(entry.key) { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val alpha by animateFloatAsState(
                targetValue = if (pressed) 0.45f else 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
                label = "favAlpha",
            )
            val shift by animateFloatAsState(
                targetValue = if (pressed) 8f else 0f,
                animationSpec = spring(dampingRatio = 0.55f, stiffness = 650f),
                label = "favShift",
            )

            Text(
                text = entry.displayLabel,
                style = FavoriteType,
                color = Aura.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // As wide as the name, not as wide as the screen.
                //
                // `fillMaxWidth` here made the whole line a tap target, so the
                // empty wallpaper to the right of "Claude" opened Claude — and
                // the double-tap that arms the drawing canvas could never be
                // made there, because the first tap had already launched
                // something. A favourite is its word.
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onClick(entry) },
                        onLongClick = { onLongClick(entry) },
                    )
                    .graphicsLayer {
                        this.alpha = alpha
                        translationX = shift
                    }
                    // Tighter than it was (9dp and a 30sp line): the list
                    // read as scattered words rather than as one column.
                    .padding(vertical = 5.dp, horizontal = 4.dp),
            )
        }
    }
}

/**
 * The favourites' own type, rather than a heading borrowed from Settings.
 *
 * They were set in `titleLarge` — Medium weight, tight tracking, the style the
 * settings screen uses for section headers. On the home screen that put a row
 * of dense UI labels directly under a 76-point Light clock, and the two read as
 * belonging to different applications. This is the clock's family at a
 * readable size: Light, a little larger, and with room around the letters, so
 * the favourites look like the home screen rather than like a menu that
 * happens to be on it.
 *
 * Not added to the app's Typography, because that is a shared scale and this is
 * one place's voice; putting it there would invite it into the next list that
 * wanted to look important.
 */
private val FavoriteType = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Light,
    fontSize = 23.sp,
    lineHeight = 27.sp,
    letterSpacing = (-0.6).sp,
)
