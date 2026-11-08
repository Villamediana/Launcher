package com.aura.launcher.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.launcher.ui.theme.Aura

/** A numbered thing to do, with the one button that does it. */
@Composable
internal fun StepLine(
    number: Int,
    title: String,
    action: String?,
    icon: ImageVector? = null,
    done: Boolean = false,
    onClick: () -> Unit = {},
) {
    val colors = Aura.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (done) colors.sage.copy(alpha = 0.08f) else colors.surface)
            .border(1.dp, if (done) colors.sage.copy(alpha = 0.22f) else colors.hairline, RoundedCornerShape(16.dp))
            .then(if (action != null && !done) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (done) colors.sage else colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (done) "✓" else number.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = if (done) colors.surface else colors.accent,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) colors.sage else colors.ink,
            modifier = Modifier.weight(1f),
        )
        if (action != null && !done) {
            Spacer(Modifier.width(8.dp))
            if (icon != null) {
                Icon(icon, null, tint = colors.accent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(action, style = MaterialTheme.typography.bodyMedium, color = colors.accent, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Aura.colors.inkFaint)
}
