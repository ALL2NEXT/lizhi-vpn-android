package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleAction
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleType
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.MainViewModel

/** 展示智能分流规则设置。 */
@Composable
fun CoreRoutingScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val smartRoutingSettings by viewModel.smartRoutingSettings.collectAsStateWithLifecycle()
    var showSmartRuleSettings by remember { mutableStateOf(false) }
    var smartRuleSettingsAction by remember { mutableStateOf(SmartRoutingRuleAction.DIRECT) }
    var smartRuleSettingsDomains by remember { mutableStateOf(emptyList<String>()) }
    fun customDomainsFor(action: SmartRoutingRuleAction): List<String> =
        smartRoutingSettings.rules
            .filter {
                it.type == SmartRoutingRuleType.CUSTOM_DOMAIN &&
                    it.action == action &&
                    it.enabled
            }
            .flatMap { it.values }
            .distinct()

    AppScreenScaffold(title = "路由规则", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            RoutingSection(title = "智能分流规则") {
                SmartRoutingCategory(
                    title = "直连域设置",
                    onSet = {
                        smartRuleSettingsAction = SmartRoutingRuleAction.DIRECT
                        smartRuleSettingsDomains = customDomainsFor(SmartRoutingRuleAction.DIRECT)
                        showSmartRuleSettings = true
                    }
                )
                SmartRoutingCategory(
                    title = "代理域设置",
                    onSet = {
                        smartRuleSettingsAction = SmartRoutingRuleAction.PROXY
                        smartRuleSettingsDomains = customDomainsFor(SmartRoutingRuleAction.PROXY)
                        showSmartRuleSettings = true
                    }
                )
                SmartRoutingCategory(
                    title = "阻断域设置",
                    onSet = {
                        smartRuleSettingsAction = SmartRoutingRuleAction.BLOCK
                        smartRuleSettingsDomains = customDomainsFor(SmartRoutingRuleAction.BLOCK)
                        showSmartRuleSettings = true
                    }
                )
                if (showSmartRuleSettings) {
                    SmartRoutingDomainSettingsDialog(
                        action = smartRuleSettingsAction,
                        initialDomains = smartRuleSettingsDomains,
                        onDismiss = { showSmartRuleSettings = false },
                        onSave = { domains ->
                            viewModel.setSmartRoutingDomains(smartRuleSettingsAction, domains)
                            showSmartRuleSettings = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutingSection(
    title: String,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (collapsible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "折叠$title" else "展开$title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) content()
        } else {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

