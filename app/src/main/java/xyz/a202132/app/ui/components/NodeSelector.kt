package xyz.a202132.app.ui.components

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
import xyz.a202132.app.ui.theme.Primary

@Composable
fun ProxyModeSelector(
    isGlobalMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
    ) {
        ProxyModeButton(
            title = "🌐 全局代理",
            isSelected = isGlobalMode,
            onClick = { onModeChange(true) },
            modifier = Modifier.weight(1f)
        )

        ProxyModeButton(
            title = "📱 智能分流",
            isSelected = !isGlobalMode,
            onClick = { onModeChange(false) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun ProxyModeToggle(
    isGlobalMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProxyModeToggleOption(
                label = "全局",
                selected = isGlobalMode,
                onClick = { onModeChange(true) }
            )
            ProxyModeToggleOption(
                label = "智能",
                selected = !isGlobalMode,
                onClick = { onModeChange(false) }
            )
        }
    }
}

@Composable
private fun ProxyModeToggleOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        color = if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ProxyModeButton(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) Primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
        }
    }
}
