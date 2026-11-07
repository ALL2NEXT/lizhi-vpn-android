package io.github.HanJason233.chimera.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.ui.theme.MonetThemeOverlay

@Composable
fun SegmentedToggle(
    firstLabel: String,
    secondLabel: String,
    selectedFirst: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
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
            modifier = Modifier
                .padding(2.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SegmentedToggleOption(
                label = firstLabel,
                selected = selectedFirst,
                onClick = { onSelectionChange(true) }
            )
            SegmentedToggleOption(
                label = secondLabel,
                selected = !selectedFirst,
                onClick = { onSelectionChange(false) }
            )
        }
    }
}

@Composable
private fun SegmentedToggleOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            ),
        color = if (selected) MonetThemeOverlay.selectedControl else MonetThemeOverlay.controlSurface,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
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
