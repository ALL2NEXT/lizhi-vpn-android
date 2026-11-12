# 原生安全检测

native 安全代码直接放在 `app/src/main/cpp/security/`，不再包含 `detection/`、`handling/` 子目录。每个检测项在自己的文件内完成检测与处置。

| 文件 | 检测与默认处置 |
| --- | --- |
| `screenshot_capture_detector.cpp` | Activity 创建时设置 `FLAG_SECURE`，默认禁止截图和普通录屏；截图回调作为事件记录 |
| `blacklist_detector.cpp` | 命中软件包名单时仅上报，不限制本机使用 |
| `adb_detector.cpp` | USB 调试开关开启时仅上报 |
| `developer_mode_detector.cpp` | 开发者选项开启时仅上报 |
| `signature_detector.cpp` | 签名不匹配或不可读取，立即终止进程 |
| `xposed_detector.cpp` | 命中 Xposed 痕迹，立即终止进程 |
| `lsposed_detector.cpp` | 命中 LSPosed 痕迹，立即终止进程 |
| `frida_detector.cpp` | 命中映射、特征线程、监听端口或进程跟踪，立即终止；关键进程信息不可读取时也终止 |
| `capture_proxy_detector.cpp` | 先保持代理绕过和请求防护；命中 HTTP/PAC 代理、抓包相关网络事件或代理检测不可用，立即终止 |
| `security.cpp / security.h` | 调度、日志、通用上报桥接及原生终止辅助方法 |
| `detection_utils.cpp / detection_utils.h` | 文件、进程映射、系统设置和软件包查询辅助方法 |

Kotlin 的 `security` 包负责 JNI 调用、Android 窗口、网络请求和待上报队列等平台能力。

## 执行时机

启动、Activity 回到前台和 VPN 建连前执行完整扫描；Activity 可见或 VPN 运行期间每 30 秒复检。系统代理变化时和每次业务请求前检查代理。命中终止项后通过原生 `abort()` 立即结束应用进程，不再仅阻止后续请求。

手动“安全自检”与自动扫描使用同一个 `nativeScan` 入口，各检测文件会直接执行处置，不再有只读检查绕过策略。命中终止项时应用会直接退出；仅上报项仍可在自检页面查看。应用不弹出安全检测 Toast。

原生库无法加载、扫描失败或初始化超时也按终止策略处理；原生终止入口不可用时由 Kotlin 结束本进程。普通网络超时、断网和请求取消不作为抓包命中。

## 仅上报项

仅允许以下代码进入上报队列：

- `blacklisted_package:<包名>`
- `adb_enabled`
- `developer_options_enabled`

原生调度先执行这三类检查，同步写入本地待上报队列，再执行可能终止进程的检查。同一进程内已入队的代码去重；本地最多保留 64 个代码，每批最多上传 20 个。断网、离线模式或上传失败时保留队列，在后续前台/账户同步等机会重试。

如果同一轮同时出现上报项和终止项，先保存队列再立即终止，不等待网络上传；尚未上传的记录需等应用后续能够正常运行时重试。因此上报与终止开关可以同时开启。

后台 `/api/account/security-report` 已支持上述代码。上报事件按设备和代码归并，进入待审核状态，不因上报自动封禁设备。

## 抓包防护

系统代理绕过从首次扫描之前持续生效，不依赖检测是否命中。以下情况先拒绝该请求，再立即终止进程：

- TLS 证书、信任链或主机名验证失败。
- 业务请求试图通过不允许的外部代理路由发送。
- 业务请求试图向外部地址发送未加密 HTTP。
- HTTPS 连接尝试重定向降级到 HTTP。

API、订阅、图标、测速下载、公网 IP 查询、分应用规则及 Geo/规则资源下载接入统一网络策略。固定回环地址和显式的 HTTP 节点连通性探测保留原用途，不承载应用账户请求；HTTPS 节点探测同样禁止降级。VPN/TUN 接口本身不作为抓包证据。

抓包相关网络事件通过 JNI 回到 `capture_proxy_detector.cpp`，与代理检测在同一文件执行终止策略。系统 HTTP/PAC 代理命中也会立即终止。

Frida 端口探测使用有界的本机 IPv4/IPv6 连接，不读取 Android 10+ 限制的 `/proc/net`。开放端口本身不能证明 Frida，但按当前严格策略也会终止。普通 GLib 线程名 `gmain/gdbus` 不作为 Frida 特征。

## 构建开关

所有检测与下列处理开关默认开启；Debug 与 Release 使用同一策略。

| Gradle 属性 | 默认值 | 行为 |
| --- | --- | --- |
| `chimera.security.crashOnFinding` | true | 除三类仅上报项和截图事件外，风险命中直接终止 |
| `chimera.security.blockScreenshots` | true | 预先禁止窗口截图，不依赖截图回调开关 |
| `chimera.security.blockHttpProxy` | true | 保持代理绕过及网络发送路径防护 |
| `chimera.security.reportFindings` | true | 上报软件名单、USB 调试和开发者选项 |

检测开关为 `detectCapture`、`detectXposed`、`detectLsposed`、`detectFrida`、`detectScreenshot`、`verifySignature`、`detectBlacklist`、`detectAdb`、`detectDeveloperMode`，均使用 `chimera.security.` 前缀。代理防护开启时，即使关闭 `detectCapture`，仍会检查系统代理。

`expectedCertSha256` 使用预期签名证书的 64 位十六进制 SHA-256；更换签名密钥时需通过 `-Pchimera.security.expectedCertSha256=<值>` 同步配置。

默认构建：

```powershell
.\gradlew.bat :app:assembleDebug
```

截图回调仅在 Android 14+ 支持，不覆盖 ADB、instrumentation 截图或屏幕录制；窗口截图保护独立预先启用。Android 自身的系统提示由系统控制。

客户端无法识别所有被动抓包，对方仍可记录加密报文、目标 IP 和流量时间。当前没有绑定后端 SPKI 公钥；系统信任库遭特权修改或进程遭 Hook 时，不能保证业务明文始终不可获取。
