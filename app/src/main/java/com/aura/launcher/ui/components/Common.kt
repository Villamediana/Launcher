package com.aura.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.launcher.ui.theme.Aura

/** A small all-caps label; the closest thing Aura has to a section heading. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        color = Aura.colors.inkFaint,
        modifier = modifier,
    )
}

/**
 * A surface defined by a hairline rather than a shadow. Elevation shadows read
 * as plastic; a 1px warm border reads as an edge of paper.
 */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    corner: Dp = 24.dp,
    raised: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = Aura.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(if (raised) colors.surfaceRaised else colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(corner)),
    ) { content() }
}
