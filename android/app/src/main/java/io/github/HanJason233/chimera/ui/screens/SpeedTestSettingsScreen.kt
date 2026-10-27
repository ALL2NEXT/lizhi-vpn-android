package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeedTestSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val homeSettings by viewModel.homeSpeedTestSettings.collectAsStateWithLifecycle()
    val manualSettings by viewModel.groupManualSpeedTestSettings.collectAsStateWithLifecycle()
    // 两个入口各自维护配置，不再显示方案选择或入口总开关。
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    AppScreenScaffold(
        title = "测速设置",
        onBack = onBack,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Home,
                            contentDescription = "主页图标测速设置"
                        )
                    }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text("手动测速", maxLines = 1) }
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) { page ->
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = if (page == 0) {
                            "长按主页连接图标时使用以下测试项。"
                        } else {
                            "分组详情中的手动测速使用以下设置。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                    SpeedTestSettingsEditorContent(
                        settings = if (page == 0) homeSettings else manualSettings,
                        isHomeTab = page == 0,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onSave = if (page == 0) {
                            { settings -> viewModel.setHomeSpeedTestSettings(settings) }
                        } else {
                            { settings -> viewModel.setGroupManualSpeedTestSettings(settings) }
                        }
                    )
                }
            }
        }
    }
}
