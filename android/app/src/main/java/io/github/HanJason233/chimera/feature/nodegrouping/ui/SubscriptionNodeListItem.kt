package io.github.HanJason233.chimera.feature.nodegrouping.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.data.local.HomeNodeTestResultsEntity
import io.github.HanJason233.chimera.data.model.ManualGroupTestStage
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeDisplaySettings
import io.github.HanJason233.chimera.data.model.ProtocolCoreSettings
import io.github.HanJason233.chimera.feature.nodetest.data.HomeTestResultCodec
import io.github.HanJason233.chimera.network.nodetest.linktype.LinkTypeTestState
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestState
import io.github.HanJason233.chimera.ui.components.unlockTestIconResource
import io.github.HanJason233.chimera.util.nodeListProtocolLabel
import java.util.Locale

private val nodeListProtocolColor = Color(0xFFF97910)
private val nodeListResultColor = Color(0xFF009966)
private val nodeListResultErrorColor = Color(0xFFFF0099)

private data class NodeTestResultDisplay(
    val text: String? = null,
    val isError: Boolean = false,
    val passedUnlockIcons: List<PassedUnlockIcon> = emptyList()
)

private data class PassedUnlockIcon(
    val id: String,
    val name: String
)

private fun nodeTestResultDisplay(
    node: Node,
    stored: HomeNodeTestResultsEntity?,
    stage: ManualGroupTestStage,
    resultUpdatedAfter: Long,
    isTestRunning: Boolean
): NodeTestResultDisplay? {
    if (resultUpdatedAfter > 0L && (stored?.updatedAt ?: 0L) < resultUpdatedAfter) {
        if (stage == ManualGroupTestStage.IP_QUALITY) return null
        return if (isTestRunning) {
            NodeTestResultDisplay("检测中", isError = false)
        } else {
            null
        }
    }

    return when (stage) {
        ManualGroupTestStage.TCPING -> {
            val latency = stored?.tcpingLatency?.takeIf { it != -1 }
                ?: if (stored == null) node.tcpingLatency.takeIf { it != -1 } else null
            latency?.let {
                NodeTestResultDisplay("${if (it < 0) -1 else it} ms", isError = it < 0)
            }
        }

        ManualGroupTestStage.URL_TEST -> {
            val latency = stored?.urlTestLatency?.takeIf { it != -1 }
                ?: if (stored == null) node.urlTestLatency.takeIf { it != -1 } else null
            latency?.let {
                NodeTestResultDisplay("${if (it < 0) -1 else it} ms", isError = it < 0)
            }
        }

        ManualGroupTestStage.BANDWIDTH -> {
            val speed = stored?.downloadMbps
                ?: node.downloadMbps.takeIf { it > 0f }
            speed?.let {
                if (it <= 0f) {
                    NodeTestResultDisplay("失败", isError = true)
                } else {
                    val value = if (it >= 100f) {
                        it.toInt().toString()
                    } else {
                        String.format(Locale.US, "%.1f", it)
                    }
                    NodeTestResultDisplay("$value Mbps", isError = false)
                }
            }
        }

        ManualGroupTestStage.LINK_TYPE -> {
            val json = stored?.linkTypeResultJson?.takeIf(String::isNotBlank)
                ?: return null
            val result = HomeTestResultCodec.decodeLinkType(json) ?: return null
            when (result.state) {
                LinkTypeTestState.IDLE -> null
                LinkTypeTestState.TESTING ->
                    NodeTestResultDisplay("检测中", isError = false)
                LinkTypeTestState.DIRECT ->
                    NodeTestResultDisplay("直连", isError = false)
                LinkTypeTestState.RELAY ->
                    NodeTestResultDisplay("中转", isError = false)
                LinkTypeTestState.CDN_RELAY ->
                    NodeTestResultDisplay("CDN中转", isError = false)
                LinkTypeTestState.NOT_CONNECTED ->
                    NodeTestResultDisplay("未连接", isError = true)
                LinkTypeTestState.ERROR ->
                    NodeTestResultDisplay("失败", isError = true)
            }
        }

        ManualGroupTestStage.IP_QUALITY -> {
            val json = stored?.unlockTestResultJson?.takeIf(String::isNotBlank)
                ?: return null
            val results = HomeTestResultCodec.decodeUnlockTests(json)?.items.orEmpty()
            val passedIcons = results
                .filter { it.state == UnlockTestState.UNLOCKED }
                .map { PassedUnlockIcon(it.definition.id, it.definition.name) }
            passedIcons.takeIf { it.isNotEmpty() }
                ?.let { NodeTestResultDisplay(passedUnlockIcons = it) }
        }
    }
}

@Composable
internal fun NodeListItem(
    modifier: Modifier = Modifier,
    node: Node,
    isSelected: Boolean,
    enabled: Boolean,
    displaySettings: NodeDisplaySettings,
    coreSettings: ProtocolCoreSettings,
    testResult: HomeNodeTestResultsEntity?,
    testStage: ManualGroupTestStage,
    testRunStartedAt: Long,
    isTestRunning: Boolean,
    canStartTest: Boolean,
    onTest: ((ManualGroupTestStage) -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    var testMenuExpanded by remember(node.id) { mutableStateOf(false) }
    val protocolLabel = remember(node.type, node.rawLink, displaySettings, coreSettings) {
        node.nodeListProtocolLabel(displaySettings, coreSettings)
    }
    val displayedTestResult = remember(
        node,
        testResult,
        testStage,
        testRunStartedAt,
        isTestRunning
    ) {
        nodeTestResultDisplay(
            node = node,
            stored = testResult,
            stage = testStage,
            resultUpdatedAfter = testRunStartedAt,
            isTestRunning = isTestRunning
        )
    }
    val regionMarker = node.getRegionMarker().takeUnless { it == "🌐" }
    val serverAddress = if (node.server.contains(':') && !node.server.startsWith('[')) {
        "[${node.server}]"
    } else {
        node.server
    }
    val serverEndpointLabel = when {
        displaySettings.showAddress && displaySettings.showPort ->
            "$serverAddress : ${node.port}"
        displaySettings.showAddress -> serverAddress
        displaySettings.showPort -> node.port.toString()
        else -> null
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clickable(enabled = enabled) { onClick() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .fillMaxHeight()
            ) {
                if (isSelected) {
                    Row {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .fillMaxHeight()
                                .padding(vertical = 10.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (regionMarker != null) {
                        val isRegionCode = regionMarker.length == 2 &&
                            regionMarker.all { it in 'A'..'Z' }
                        Text(
                            text = regionMarker,
                            fontSize = if (isRegionCode) 14.sp else 20.sp,
                            lineHeight = 24.sp,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    Text(
                        text = node.getDisplayName(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (node.isAvailable) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (onTest != null || onShare != null || onEdit != null || onDelete != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            onTest?.let { test ->
                                Box {
                                    IconButton(
                                        onClick = { testMenuExpanded = true },
                                        enabled = canStartTest,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Speed,
                                            contentDescription = "测试节点",
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = testMenuExpanded,
                                        onDismissRequest = { testMenuExpanded = false }
                                    ) {
                                        ManualGroupTestStage.values().forEach { stage ->
                                            DropdownMenuItem(
                                                text = { Text(stage.displayName()) },
                                                onClick = {
                                                    testMenuExpanded = false
                                                    test(stage)
                                                },
                                                enabled = canStartTest
                                            )
                                        }
                                    }
                                }
                            }
                            onShare?.let { share ->
                                IconButton(
                                    onClick = share,
                                    enabled = enabled,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Share,
                                        contentDescription = "分享节点",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            onEdit?.let { edit ->
                                IconButton(
                                    onClick = edit,
                                    enabled = enabled,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = "编辑节点",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            onDelete?.let { delete ->
                                IconButton(
                                    onClick = delete,
                                    enabled = enabled,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteForever,
                                        contentDescription = "删除节点",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                serverEndpointLabel?.let { endpointLabel ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = endpointLabel,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (protocolLabel.isNotBlank()) {
                        Text(
                            text = protocolLabel,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodySmall,
                            color = nodeListProtocolColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    } else if (displayedTestResult != null) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    displayedTestResult?.let { result ->
                        if (result.passedUnlockIcons.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .widthIn(max = 132.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                result.passedUnlockIcons.forEach { icon ->
                                    Image(
                                        painter = painterResource(unlockTestIconResource(icon.id)),
                                        contentDescription = icon.name,
                                        modifier = Modifier.size(18.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        } else {
                            result.text?.let { text ->
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (result.isError) {
                                        nodeListResultErrorColor
                                    } else {
                                        nodeListResultColor
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ManualGroupTestStage.displayName(): String = when (this) {
    ManualGroupTestStage.TCPING -> "TCPing"
    ManualGroupTestStage.URL_TEST -> "URL Test"
    ManualGroupTestStage.BANDWIDTH -> "带宽测速"
    ManualGroupTestStage.LINK_TYPE -> "链路类型检测"
    ManualGroupTestStage.IP_QUALITY -> "流媒体与 AI 解锁"
}
