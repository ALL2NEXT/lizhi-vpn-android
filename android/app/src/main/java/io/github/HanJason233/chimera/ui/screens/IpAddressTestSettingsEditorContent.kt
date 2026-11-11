package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.HanJason233.chimera.data.model.IpAddressProbeSource
import io.github.HanJason233.chimera.data.model.IpAddressTestSettings
import io.github.HanJason233.chimera.ui.components.SettingsListSectionHeader
import io.github.HanJason233.chimera.ui.components.SettingsListSwitchRow
import kotlin.math.roundToInt

@Composable
internal fun IpAddressTestSettingsEditorContent(
    settings: IpAddressTestSettings,
    modifier: Modifier = Modifier,
    onSettingsChange: (IpAddressTestSettings) -> Unit
) {
    var timeoutSeconds by remember(settings.timeoutSeconds) {
        mutableFloatStateOf(settings.timeoutSeconds.toFloat())
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SettingsListSectionHeader("地址类型")
        SettingsListSwitchRow(
            icon = Icons.Outlined.Public,
            title = "检测 IPv4",
            subtitle = "通过 IPv4 探针查询当前网络出口地址",
            checked = settings.ipv4Enabled,
            onCheckedChange = { enabled ->
                onSettingsChange(settings.withIpv4Enabled(enabled))
            }
        )
        SettingsListSwitchRow(
            icon = Icons.Outlined.Public,
            title = "检测 IPv6",
            subtitle = "通过 IPv6 探针查询当前网络出口地址",
            checked = settings.ipv6Enabled,
            onCheckedChange = { enabled ->
                onSettingsChange(settings.withIpv6Enabled(enabled))
            }
        )

        SettingsListSectionHeader("检测来源")
        IpAddressProbeSource.entries.forEach { source ->
            val description = when (source) {
                IpAddressProbeSource.IPIFY -> "ipify 公共地址查询服务"
                IpAddressProbeSource.IPLARK -> "IPLark 多线路出口查询服务"
                IpAddressProbeSource.ICANHAZIP -> "icanhazip 公共地址查询服务"
            }
            SettingsListSwitchRow(
                icon = Icons.Outlined.Public,
                title = source.displayName,
                subtitle = description,
                checked = source in settings.enabledProbeSources,
                onCheckedChange = { enabled ->
                    onSettingsChange(settings.withProbeSourceEnabled(source, enabled))
                }
            )
        }
        Text(
            text = "每个已启用的来源会分别查询所选地址类型；关闭最后一个来源或地址类型后会保留至少一个可用选项。",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SettingsListSectionHeader("超时")
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("单个探针超时：${timeoutSeconds.roundToInt()} 秒")
            Slider(
                value = timeoutSeconds,
                onValueChange = { timeoutSeconds = it },
                valueRange = IpAddressTestSettings.MIN_TIMEOUT_SECONDS.toFloat()..
                    IpAddressTestSettings.MAX_TIMEOUT_SECONDS.toFloat(),
                steps = IpAddressTestSettings.MAX_TIMEOUT_SECONDS -
                    IpAddressTestSettings.MIN_TIMEOUT_SECONDS - 1,
                onValueChangeFinished = {
                    onSettingsChange(
                        settings.copy(timeoutSeconds = timeoutSeconds.roundToInt()).normalized()
                    )
                }
            )
            Text(
                "范围 ${IpAddressTestSettings.MIN_TIMEOUT_SECONDS}–${IpAddressTestSettings.MAX_TIMEOUT_SECONDS} 秒；探针并发查询，超时不会延迟其他来源的结果。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
