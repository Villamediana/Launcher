package com.aura.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aura.launcher.ui.theme.Aura

@Composable
fun ActionButton(
    text: String,
    filled: Boolean = true,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Aura.colors
    val alpha = if (enabled) 1f else 0.4f
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                (if (filled) colors.accent else colors.surfaceRaised).copy(alpha = alpha)
            )
            .border(
                1.dp,
                if (filled) androidx.compose.ui.graphics.Color.Transparent else colors.hairline,
                RoundedCornerShape(24.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = (if (filled) colors.surfaceRaised else colors.ink).copy(alpha = alpha),
        )
    }
}
