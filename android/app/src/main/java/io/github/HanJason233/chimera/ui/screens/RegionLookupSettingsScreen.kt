package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.RegionLookupMode
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.MainViewModel

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
fun RegionLookupSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenRoutingAssets: () -> Unit
) {
    val regionLookupMode by viewModel.regionLookupMode.collectAsStateWithLifecycle()

    AppScreenScaffold(
        title = "地区组查询",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = regionLookupMode.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                RegionLookupMode.entries.forEach { mode ->
                    FilterChip(
                        selected = regionLookupMode == mode,
                        onClick = { viewModel.setRegionLookupMode(mode) },
                        label = { Text(mode.title) }
                    )
                }
            }

            if (regionLookupMode != RegionLookupMode.REMOTE) {
                Text(
                    text = "本地查询使用 geoip.dat。域名节点仍需由系统解析到 IP；本地库未命中时，只有混合模式会请求远程归属地服务。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                OutlinedButton(
                    onClick = onOpenRoutingAssets,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("管理路由资源文件")
                }
            }

            OutlinedButton(
                onClick = viewModel::refreshMissingNodeLocations,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重新查询未识别地区")
            }
        }
    }
}
