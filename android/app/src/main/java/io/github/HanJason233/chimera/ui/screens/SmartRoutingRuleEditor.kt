package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import io.github.HanJason233.chimera.data.model.SmartRoutingRule
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleAction
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleType
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import java.util.UUID

@Composable
internal fun SmartRoutingRuleEditorDialog(
    initialRule: SmartRoutingRule?,
    onDismiss: () -> Unit,
    onSave: (SmartRoutingRule) -> Unit,
    onDelete: (String) -> Unit
) {
    var name by rememberSaveable(initialRule?.id) { mutableStateOf(initialRule?.name.orEmpty()) }
    var domainsText by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.effectiveDomains()?.joinToString("\n").orEmpty())
    }
    var ipsText by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.effectiveIps()?.joinToString("\n").orEmpty())
    }
    var processesText by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.processes?.joinToString("\n").orEmpty())
    }
    var ports by rememberSaveable(initialRule?.id) { mutableStateOf(initialRule?.ports.orEmpty()) }
    var network by rememberSaveable(initialRule?.id) { mutableStateOf(initialRule?.network.orEmpty()) }
    var protocolsText by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.protocols?.joinToString("\n").orEmpty())
    }
    var outboundTag by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.outboundTag ?: SmartRoutingRuleAction.PROXY.configTag)
    }
    var locked by rememberSaveable(initialRule?.id) { mutableStateOf(initialRule?.locked ?: false) }
    var outboundMenuExpanded by remember { mutableStateOf(false) }
    var networkMenuExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    fun buildRule(): SmartRoutingRule {
        val domains = parseRuleValues(domainsText)
        val ips = parseRuleValues(ipsText)
        return SmartRoutingRule(
            id = initialRule?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            type = SmartRoutingRuleType.CUSTOM_RULE,
            action = SmartRoutingRuleAction.fromOutboundTag(outboundTag.trim()),
            outboundTag = outboundTag.trim().ifBlank { SmartRoutingRuleAction.PROXY.configTag },
            values = domains.ifEmpty { ips },
            enabled = initialRule?.enabled ?: true,
            locked = locked,
            domains = domains,
            ips = ips,
            ports = ports.trim(),
            network = network.trim(),
            protocols = parseRuleValues(protocolsText),
            processes = parseRuleValues(processesText)
        )
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialRule == null) "添加路由规则" else "编辑路由规则") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("备注") },
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("锁定规则", modifier = Modifier.weight(1f))
                    Switch(checked = locked, onCheckedChange = { locked = it })
                }
                OutlinedTextField(
                    value = domainsText,
                    onValueChange = { domainsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("域名") },
                    supportingText = { Text("每行一项；支持 geosite:、domain:、full:、keyword:、regexp:") },
                    minLines = 2
                )
                OutlinedTextField(
                    value = ipsText,
                    onValueChange = { ipsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("IP / CIDR") },
                    supportingText = { Text("支持 geoip: 标签；每行一项，也可用逗号分隔") },
                    minLines = 2
                )
                OutlinedTextField(
                    value = processesText,
                    onValueChange = { processesText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("进程 / 应用包名") },
                    enabled = false,
                    supportingText = {
                        Text("HEV TUN 模式不支持按应用进程匹配；导入导出会保留此字段")
                    },
                    minLines = 1
                )
                OutlinedTextField(
                    value = ports,
                    onValueChange = { ports = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("端口") },
                    placeholder = { Text("80, 443, 1000-2000") },
                    singleLine = true
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("网络", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { networkMenuExpanded = true }) {
                            Text(network.ifBlank { "不限" })
                        }
                        DropdownMenu(
                            expanded = networkMenuExpanded,
                            onDismissRequest = { networkMenuExpanded = false }
                        ) {
                            listOf("", "tcp", "udp", "tcp,udp").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.ifBlank { "不限" }) },
                                    onClick = {
                                        network = option
                                        networkMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = outboundTag,
                        onValueChange = { outboundTag = it },
                        modifier = Modifier.weight(2f),
                        label = { Text("出口标签") },
                        supportingText = { Text("未知标签会按 v2rayNG 规则回退到 proxy") },
                        trailingIcon = {
                            IconButton(onClick = { outboundMenuExpanded = true }) {
                                Icon(Icons.Outlined.ArrowDropDown, contentDescription = "出口标签建议")
                            }
                            DropdownMenu(
                                expanded = outboundMenuExpanded,
                                onDismissRequest = { outboundMenuExpanded = false }
                            ) {
                                (listOf("proxy", "direct", "block") + listOfNotNull(initialRule?.outboundTag))
                                    .distinct().forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option) },
                                            onClick = {
                                                outboundTag = option
                                                outboundMenuExpanded = false
                                            }
                                        )
                                    }
                            }
                        },
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = protocolsText,
                    onValueChange = { protocolsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("协议") },
                    placeholder = { Text("bittorrent、http、tls") },
                    minLines = 2
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(buildRule()) },
                enabled = name.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (initialRule != null) {
                    TextButton(onClick = { onDelete(initialRule.id) }) { Text("删除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

private fun parseRuleValues(value: String): List<String> = value
    .split(',', '\n')
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()

private fun SmartRoutingRule.effectiveDomains(): List<String> = domains.ifEmpty {
    if (type == SmartRoutingRuleType.CUSTOM_DOMAIN) values else emptyList()
}

private fun SmartRoutingRule.effectiveIps(): List<String> = ips.ifEmpty {
    if (type == SmartRoutingRuleType.CUSTOM_IP) values else emptyList()
}
