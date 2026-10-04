# Chimera Android

Android 客户端的独立 Gradle 工程。

测速方案编辑页按执行顺序直接展示五个测试项目，每项的开关和参数都在各自项目卡片中。前三项为连接前测试：
第一项从主页当前选中的节点组选择候选节点，后续测试从前一项的可用结果中按质量择优选取；百分比和数量以候选结果为基数。
后两项为连接后测试，检测当前 VPN 会话对应的节点。编辑页只配置测试开关、筛选方式和测试参数，旧方案仍可读取。
分组内手动单项测试仍使用当前分组。
完整顺序为 TCPing、204 URL、带宽测速、链路类型检测、流媒体与 AI 解锁检测。
第四项只有开关，连接后通过当前 VPN 会话获取落地点 IP，与节点服务器地址解析出的 IPv4/IPv6
比较：匹配任一地址显示直连，否则显示中转点和落地点，并查询中转 IP 所属的已知 CDN 地址段。
只开启第四、第五项时复用当前连接；尚未连接时连接已选节点或当前组第一个节点。

CDN 地址库打包在 `app/src/main/resources/cdn-ip-ranges.json`，包括 Cloudflare、CloudFront、
Fastly、Gcore、百度云加速等。数据来自 [cdncheck](https://github.com/projectdiscovery/cdncheck)
与 [Cloudflare 官方 IP API](https://developers.cloudflare.com/api/resources/ips/methods/list/)，
排除普通云主机地址段与 Google 通用地址段。未命中时显示“普通中转 / 未命中已知 CDN 地址段”；
这不保证该中转点未使用尚未收录的 CDN。执行 `python scripts/refresh-cdn-ranges.py` 可刷新地址库。
域名有多个地址时展示所有候选中转 IP；本检测按入口与出口 IP 比较，不测量实际跳数。

## 常用命令

在本目录执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Linux 或 macOS 使用：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

应用模块位于 `app/`。本地 Android SDK 配置、签名配置与密钥文件也应放在本目录，具体配置和功能说明见仓库根目录的 `README.md`。

### sing-box 源码构建

Android 构建会自动调用根目录下锁定的 `core/sing-box` 子模块，并将 sing-box CLI 编译为独立的 Android PIE 运行器 `libsingbox.so`。它和 `libxray.so` 一样由 `ProcessBuilder` 启动，不嵌入 Android 应用进程。首次构建需要 Go 1.26、JDK 17、Android SDK 和 NDK 28.0.13004108。

首次获取源码后执行：

```bash
git submodule update --init --recursive
```

以后要添加或修改协议，直接修改根目录 `core/sing-box` 中的 sing-box 源码，然后重新执行 Gradle 构建即可；Android 运行器入口位于 `native/singbox-runner/`。
