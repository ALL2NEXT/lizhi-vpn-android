package io.github.HanJason233.chimera.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.ui.theme.MonetThemeOverlay

@Composable
fun ProxyModeToggle(
    isGlobalMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Surface(
        modifier = modifier,
        color = MonetThemeOverlay.controlSurface,
        shape = RoundedCornerShape(50),
        border = BorderStroke(
            1.dp,
            MonetThemeOverlay.outline.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProxyModeToggleOption(
                label = "全局",
                selected = isGlobalMode,
                onClick = { onModeChange(true) },
                compact = compact
            )
            ProxyModeToggleOption(
                label = "智能",
                selected = !isGlobalMode,
                onClick = { onModeChange(false) },
                compact = compact
            )
        }
    }
}

@Composable
private fun ProxyModeToggleOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        color = if (selected) MonetThemeOverlay.selectedControl else MonetThemeOverlay.controlSurface,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = if (compact) 5.dp else 9.dp, vertical = 5.dp),
            color = if (selected) {
                MonetThemeOverlay.onSelectedControl
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
