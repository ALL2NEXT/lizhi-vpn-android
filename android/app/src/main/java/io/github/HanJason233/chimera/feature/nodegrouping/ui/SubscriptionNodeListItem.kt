package io.github.HanJason233.chimera.feature.nodegrouping.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeDisplaySettings
import io.github.HanJason233.chimera.data.model.ProtocolCoreSettings
import io.github.HanJason233.chimera.util.protocolDisplayLabel

@Composable
internal fun NodeListItem(
    modifier: Modifier = Modifier,
    node: Node,
    isSelected: Boolean,
    enabled: Boolean,
    displaySettings: NodeDisplaySettings,
    coreSettings: ProtocolCoreSettings,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val protocolDisplay = remember(node.type, node.rawLink, displaySettings, coreSettings) {
        node.protocolDisplayLabel(displaySettings, coreSettings)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() },
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val regionMarker = node.getRegionMarker()
            val isRegionCode = regionMarker.length == 2 && regionMarker.all { it in 'A'..'Z' }
            Text(
                text = regionMarker,
                fontSize = if (isRegionCode) 22.sp else 34.sp,
                lineHeight = if (isRegionCode) 28.sp else 38.sp,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .offset(x = (-8).dp)
                    .width(48.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = node.getDisplayName(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (node.isAvailable) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (protocolDisplay.isNotEmpty()) {
                        NodeProtocolDetailsBadge(
                            label = protocolDisplay,
                            modifier = Modifier.padding(start = 8.dp, end = 4.dp)
                        )
                    }

                }

            }
        }
    }
}

@Composable
private fun NodeProtocolDetailsBadge(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}
