package io.github.HanJason233.chimera.feature.nodegrouping.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeDisplaySettings
import io.github.HanJason233.chimera.data.model.ProtocolCoreSettings
import io.github.HanJason233.chimera.ui.theme.LatencyBad
import io.github.HanJason233.chimera.ui.theme.LatencyGood
import io.github.HanJason233.chimera.ui.theme.LatencyMedium
import io.github.HanJason233.chimera.util.protocolDisplayLabel

internal enum class NodeTestResultTone {
    NEUTRAL,
    POSITIVE,
    WARNING,
    ERROR
}

internal data class NodeListTestResultSummary(
    val tcpingLatency: Int = -1,
    val urlTestLatency: Int = -1,
    val downloadMbps: Float = 0f,
    val linkType: String? = null,
    val linkTypeTone: NodeTestResultTone = NodeTestResultTone.NEUTRAL,
    val unlockRatio: String? = null,
    val unlockTone: NodeTestResultTone = NodeTestResultTone.NEUTRAL
)

@Composable
internal fun NodeListItem(
    modifier: Modifier = Modifier,
    node: Node,
    isSelected: Boolean,
    enabled: Boolean,
    displaySettings: NodeDisplaySettings,
    coreSettings: ProtocolCoreSettings,
    testResults: NodeListTestResultSummary,
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
            Text(
                text = node.getRegionMarker(),
                fontSize = 34.sp,
                lineHeight = 38.sp,
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

                Spacer(modifier = Modifier.height(6.dp))

                val tcpingLatency = testResults.tcpingLatency.takeIf { it != -1 }
                    ?: node.tcpingLatency
                val urlTestLatency = testResults.urlTestLatency.takeIf { it != -1 }
                    ?: node.urlTestLatency
                val downloadMbps = testResults.downloadMbps.takeIf { it > 0f }
                    ?: node.downloadMbps
                val testValues = listOf(
                    formatNodeLatency(tcpingLatency) to nodeMetricColor(tcpingLatency),
                    formatNodeLatency(urlTestLatency) to nodeMetricColor(urlTestLatency),
                    formatBandwidth(downloadMbps) to if (downloadMbps > 0f) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    (testResults.linkType ?: "—") to resultToneColor(testResults.linkTypeTone),
                    (testResults.unlockRatio ?: "—") to resultToneColor(testResults.unlockTone)
                )
                Text(
                    text = buildAnnotatedString {
                        testValues.forEachIndexed { index, (value, color) ->
                            if (index > 0) {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.outline)) {
                                    append("/")
                                }
                            }
                            withStyle(
                                SpanStyle(
                                    color = color,
                                    fontWeight = FontWeight.Medium
                                )
                            ) {
                                append(value)
                            }
                        }
                    },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
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

private fun formatNodeLatency(latency: Int): String = when {
    latency > 0 -> "${latency}ms"
    latency == -2 -> "×"
    else -> "—"
}

private fun formatBandwidth(downloadMbps: Float): String = if (downloadMbps > 0f) {
    String.format(Locale.US, "%.1fMbps", downloadMbps)
} else {
    "—"
}

@Composable
private fun nodeMetricColor(latency: Int): Color = when {
    latency == -2 -> LatencyBad
    latency < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
    latency <= 200 -> LatencyGood
    latency <= 400 -> LatencyMedium
    else -> LatencyBad
}

@Composable
private fun resultToneColor(tone: NodeTestResultTone): Color = when (tone) {
    NodeTestResultTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    NodeTestResultTone.POSITIVE -> LatencyGood
    NodeTestResultTone.WARNING -> LatencyMedium
    NodeTestResultTone.ERROR -> LatencyBad
}
