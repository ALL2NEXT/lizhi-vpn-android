package xyz.a202132.app.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import xyz.a202132.app.ui.theme.Primary

private const val AGREEMENT_WAIT_SECONDS = 10

@Composable
fun UserAgreementDialog(
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    val remainingSeconds = remember { mutableIntStateOf(AGREEMENT_WAIT_SECONDS) }
    val agreementChecked = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (remainingSeconds.intValue > 0) {
            delay(1_000L)
            remainingSeconds.intValue--
        }
    }

    AlertDialog(
        onDismissRequest = {}, // 不允许点击外部关闭
        title = {
            Text(
                text = "使用协议",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = """
                        重要提示
                        请在使用前完整阅读本协议，尤其注意“禁止用途”“服务及节点风险”“个人信息处理”“免责声明与责任限制”等与您有重大利害关系的内容。只有在您充分理解并勾选同意、且等待倒计时结束后，才能继续使用本应用。

                        1. 服务主体与服务性质
                        本应用（流萤加速器）提供 Android 客户端、VPN/TUN 配置、代理节点管理、订阅获取、网络连接及相关账户和流量统计功能。具体运营主体、联系方式及隐私政策入口以本应用“关于”页面公布的信息为准。运营方不承诺本应用构成任何特定通信、信息安全、匿名、翻墙、规避监管或其他特殊用途服务。

                        2. 合法使用与禁止行为
                        您应遵守所在国家或地区以及网络、服务器和目标网站所在地的法律法规、监管要求、平台规则和第三方服务条款。您不得利用本应用或任何节点实施、协助或诱导违法犯罪、侵入或攻击计算机系统、传播恶意软件、窃取或泄露数据、诈骗、赌博、洗钱、侵犯知识产权或他人隐私、传播违法或有害信息、绕过安全控制、干扰网络运行、批量抓取或滥用第三方服务，以及其他违反法律、公共秩序或善良风俗的行为。

                        3. 用户责任
                        您对使用本应用发起、传输、访问、下载、上传或发布的全部内容、数据、请求和网络行为负责，并应自行确认拥有相应权利和授权。您不得将账户、节点配置、订阅地址或本应用提供的能力转让、出租、共享给他人用于违法或未经授权的用途。因您的行为造成第三方索赔、行政调查、刑事风险、平台处置或其他损失的，您应自行处理；在法律允许范围内，您还应赔偿运营方因此遭受的实际损失及合理维权费用。

                        4. 节点、订阅及第三方服务
                        节点可能来自运营方、用户导入或独立第三方订阅源。运营方不控制第三方节点、网站、服务器、线路、内容、日志、隐私政策或持续可用性，也不保证节点来源合法、速度稳定、数据安全、未被监控或适合任何特定目的。第三方节点运营者、订阅提供方、网络接入商和目标网站可能独立收集或处理您的信息，并可能设置自己的条款。您应在使用前自行核验来源和风险；运营方可基于安全、合规、版权、容量、维护或其他合理原因下线、替换、限制或暂停节点和订阅。

                        5. 服务及网络风险
                        网络连接可能受到设备、系统、运营商、地区网络、DNS、路由、节点、目标网站、第三方平台、维护、攻击、拥塞或不可抗力影响。本应用不保证服务连续、及时、无错误、无中断、无丢包、无泄露或完全安全，也不保证能够访问任何特定网站或服务。VPN/代理不等于匿名工具或绝对安全工具；节点运营者和目标网站可能看到或记录连接元数据及经由其处理的流量。请勿通过不可信节点传输密码、支付信息、身份证件、商业秘密或其他高度敏感信息。

                        6. 账户与处置措施
                        您应妥善保管账户、邮箱、密码和设备，不得冒用他人身份。发现账户或设备异常时应及时联系运营方。为保护用户、网络和运营方，或应法律法规、监管机关、司法机关、权利人合法通知要求，运营方可对账户、设备、节点或相关功能采取警告、限流、下线、暂停、封禁、删除数据、保全必要记录或终止服务等措施；对明显违法、攻击、滥用或危及安全的行为，可先行处置并在合理范围内通知。除法律另有规定或运营方存在应承担的责任外，因前述合规处置造成的间接损失不由运营方承担。

                        7. 个人信息与网络日志说明
                        为注册和维护账户、权限控制、安全风控及服务统计，应用会向后端发送经过 SHA-256 处理的设备标识（原始 Android ID 或安装随机标识不直接上传）、设备品牌与型号、Android 版本、Android SDK、App 版本，以及账户昵称。绑定账户或使用相关功能时，可能处理邮箱、密码凭据（服务端以密码哈希形式保存）、VPN 会话时间、订阅分组和上传/下载流量统计。网络请求还可能产生来源 IP、请求时间、User-Agent、错误和安全审计等必要技术信息。

                        这些信息仅用于账户服务、权限和订阅控制、流量与测速统计、故障排查、安全防护、反滥用、履行法律义务及处理争议。运营方应按照适用法律采取必要安全措施，并在达到保存目的、保存期限届满或法律要求时删除或匿名化；具体处理者身份、处理目的和方式、信息种类、保存期限、个人权利行使方式及跨境或第三方提供情况，以可查阅、可保存的隐私政策为准。您可以通过“关于”页面公布的联系方式提出查询、更正、删除或其他个人信息请求。

                        8. 免责声明与责任限制
                        在适用法律允许的最大范围内，因以下原因导致的服务中断、数据丢失、内容错误、设备或软件故障、账户或节点被处置、第三方行为、网络攻击、运营商或平台政策、不可抗力以及您未按说明使用而产生的间接、附带、特殊、惩罚性损失或预期收益损失，运营方不承担责任。运营方不对第三方节点、订阅、网站或内容作明示或默示保证。

                        本条不排除或限制法律不得排除或限制的责任，包括因运营方故意或重大过失、侵犯您依法享有的人身权益、违反法定强制性义务或其他法律明确不得限制的责任。若运营方依法确需对您的直接实际损失承担责任，在法律允许范围内，责任范围以可证明的直接损失为限；如您未向运营方支付相关服务费用，除法律另有规定外，不以不存在费用为由扩大责任。

                        9. 知识产权与反馈
                        本应用及其代码、界面、商标、文案和服务资料的相应权利归权利人所有。未经授权不得复制、反向工程、出租、销售、再许可或用于开发竞争性服务，但法律明确允许或开源许可证另有规定的除外。您提交的意见、错误报告或改进建议可被运营方用于改进服务；如其中包含个人信息或第三方权利内容，请在提交前自行删除或取得必要授权。

                        10. 协议变更与终止
                        运营方可根据法律、业务、安全或技术变化更新本协议。涉及用户重大权益的变更，应通过弹窗、版本更新或其他显著方式提示；必要时重新取得您的同意。您不同意变更的，应停止使用并删除本应用。协议终止不影响终止前已经产生的权利义务、付款结算、责任承担、争议解决和依法需要继续保存的记录。

                        11. 适用法律与争议解决
                        本协议适用运营主体登记地的法律；法律另有强制性规定的，从其规定。争议应先通过本应用“关于”页面公布的联系方式协商解决；协商不成的，提交有管辖权的法院处理。

                        如您不同意本协议或无法遵守适用法律法规，请立即停止使用，并从设备中卸载本应用。
                    """.trimIndent(),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = agreementChecked.value,
                        onCheckedChange = { agreementChecked.value = it }
                    )
                    Text(
                        text = "我已阅读、理解并同意以上协议及风险提示",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAgree,
                enabled = remainingSeconds.intValue == 0 && agreementChecked.value,
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text(
                    text = if (remainingSeconds.intValue == 0) {
                        "同意协议"
                    } else {
                        "同意协议（${remainingSeconds.intValue}秒）"
                    },
                    color = Color.White
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDisagree
            ) {
                Text("不同意", color = Color.Red)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    )
}
