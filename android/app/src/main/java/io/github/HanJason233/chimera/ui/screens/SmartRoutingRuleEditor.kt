package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleAction

@Composable
internal fun SmartRoutingCategory(
    title: String,
    onSet: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        TextButton(onClick = onSet) {
            Text("设置")
        }
    }
}

@Composable
internal fun SmartRoutingDomainSettingsDialog(
    action: SmartRoutingRuleAction,
    initialDomains: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var domainsText by remember(action, initialDomains) {
        mutableStateOf(initialDomains.joinToString("\n"))
    }
    val domains = remember(domainsText) { parseSmartRuleValues(domainsText) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置${action.displayName()}域") },
        text = {
            OutlinedTextField(
                value = domainsText,
                onValueChange = { domainsText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("域名列表") },
                supportingText = { Text("每行一个域名，也支持使用逗号分隔；留空可清除") },
                minLines = 5
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(domains) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun parseSmartRuleValues(value: String): List<String> = value
    .split(',', '\n')
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()

private fun SmartRoutingRuleAction.displayName(): String = when (this) {
    SmartRoutingRuleAction.DIRECT -> "直连"
    SmartRoutingRuleAction.PROXY -> "代理"
    SmartRoutingRuleAction.BLOCK -> "阻断"
}
