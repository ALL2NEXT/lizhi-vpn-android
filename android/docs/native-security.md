# 原生安全检测

每种检测都放在独立的 native 文件中，位于 `app/src/main/cpp/security/detection/`：

- `capture_proxy_detector.cpp`：系统 HTTP 代理检测
- `xposed_detector.cpp`：Xposed 痕迹检测
- `lsposed_detector.cpp`：LSPosed 痕迹检测
- `frida_detector.cpp`：Frida 映射、特征线程、进程跟踪和有界的本机端口探测
- `signature_detector.cpp`：APK 签名校验
- `blacklist_detector.cpp`：软件包黑名单检测
- `adb_detector.cpp`：ADB 调试开关检测
- `developer_mode_detector.cpp`：开发者选项开关检测
- `screenshot_capture_detector.cpp`：截图回调事件

`security_detector.cpp` 负责调度编译时开启的检测，`detection_utils.cpp` 提供共用方法。Kotlin JNI 入口和 Android 截图回调监听位于
`app/src/main/java/io/github/HanJason233/chimera/security/detection/`。

处理代码也在独立目录 `app/src/main/cpp/security/handling/`：

- `initial_findings_handler.cpp`：新增风险日志、黑名单上报及终止策略
- `screenshot_capture_handler.cpp`：截图事件日志
- `crash_handler.cpp`：可配置的原生进程终止
- `screenshot_block_handler.cpp`：调用 Android 安全窗口标记阻止截图
- `http_proxy_block_handler.cpp`：检测到系统 HTTP 代理后绕过该代理
- `security_report_handler.cpp`：调用 Kotlin 网络桥上报风险代码

检测和终止策略由 native 编译开关选择；Kotlin 桥接 Android 能力并在网络发送路径执行防护。默认开启所有检测和网络防护，关闭强制终止、防截图和黑名单上报。

启动扫描在 IO 线程执行；Activity 回到前台、VPN 连接前会完整复检，Activity 可见或 VPN 运行期间每 30 秒复检。安全检测不再自动弹出 Toast，结果集中在左侧菜单“安全自检”中查看。扫描结果按当前快照更新，截图和 TLS 错误作为独立事件保存。

“安全自检”进入页面后执行一次本机检查，也支持手动重新检测。页面展示检查时间、耗时、每项状态及可展开的命中详情，并区分异常、需关注、检测不可用、未启用和系统不支持。截图监听状态及本次运行的历史事件单独展示，不把未发生截图或旧的 TLS 错误当作当前环境通过/异常的依据。登录页也提供自检入口。

手动自检使用独立的只读 `nativeInspect` JNI 入口，本次操作不调用上报或终止 handler，也不会发送外部网络测试请求。后台检测、请求阻断及配置的黑名单上报继续按原策略运行。检测组件不可用或关键信息无法读取时，自检显示无法检测，不显示为通过。

每个检测项默认开启，可通过 Gradle 属性单独控制：

| Gradle 属性 | 检测项 |
| --- | --- |
| `chimera.security.detectCapture` | 系统 HTTP/PAC 代理配置 |
| `chimera.security.detectXposed` | Xposed 痕迹 |
| `chimera.security.detectLsposed` | LSPosed 痕迹 |
| `chimera.security.detectFrida` | Frida 映射、特征线程、本机端口和 tracer PID |
| `chimera.security.detectScreenshot` | Android 截图回调（API 34+） |
| `chimera.security.verifySignature` | 当前 APK 签名证书 SHA-256 |
| `chimera.security.detectBlacklist` | 已知抓包、Hook 和 Root 软件包名 |
| `chimera.security.detectAdb` | 系统 ADB 调试开关 |
| `chimera.security.detectDeveloperMode` | 系统开发者选项开关 |

处理方式可在构建时分别控制；网络防护默认开启，其余默认关闭：

| Gradle 属性 | 行为 |
| --- | --- |
| `chimera.security.crashOnFinding` | 签名不匹配、Frida 映射/特征线程或进程跟踪命中时通过原生 `abort()` 结束进程；ADB、开发者选项、开放端口、安装包和截图不单独触发终止；和上报开关不能同时开启 |
| `chimera.security.blockScreenshots` | 截图检测开启时，给主窗口预先设置 `FLAG_SECURE`，阻止系统截图和普通录屏 |
| `chimera.security.blockHttpProxy` | 默认 true：始终绕过系统 HTTP/PAC 代理，业务请求强制 HTTPS，拒绝 TLS 验证失败、HTTPS 降级和外部代理路由；原生检查不可用时停止业务请求和 VPN 连接 |
| `chimera.security.reportFindings` | 仅把软件黑名单命中（`blacklisted_package:<包名>`）上报至后台“安全风控”页，等待管理员审核；后台可选择拉黑设备或忽略 |

例如，启用截图阻断和软件黑名单待审核上报：

```powershell
.\gradlew.bat :app:assembleDebug `
  '-Pchimera.security.blockScreenshots=true' `
  '-Pchimera.security.reportFindings=true'
```

`blockScreenshots` 会在截图检测启用时于 Activity 创建阶段提前设置安全窗口标志，因为 Android 截图回调发生在截图操作之后。`reportFindings` 只上报 `blacklisted_package:<包名>` 命中；Xposed、Frida、代理、ADB、签名和开发者模式等其他检测不会上报。上报事件按设备和风险代码归并，后台审核后才会写入设备黑名单。

PowerShell 中可这样关闭所有检测并构建 Debug 包：

```powershell
.\gradlew.bat :app:assembleDebug `
  '-Pchimera.security.detectCapture=false' `
  '-Pchimera.security.detectXposed=false' `
  '-Pchimera.security.detectLsposed=false' `
  '-Pchimera.security.detectFrida=false' `
  '-Pchimera.security.detectScreenshot=false' `
  '-Pchimera.security.verifySignature=false' `
  '-Pchimera.security.detectBlacklist=false' `
  '-Pchimera.security.detectAdb=false' `
  '-Pchimera.security.detectDeveloperMode=false' `
  '-Pchimera.security.blockHttpProxy=false'
```

只需给要开启的检测设置 `true`；未指定的属性默认是 `true`。签名校验默认使用当前项目签名证书的 SHA-256。更换签名密钥时，可通过 `-Pchimera.security.expectedCertSha256=<64位十六进制SHA256>` 指定预期值。

抓包防护与软件包黑名单独立。安装包命中仅用于提示和可选的后台审核，不作为当前正在抓包的证据。系统 HTTP/PAC 代理配置变化通过广播触发检测；每次业务请求也重新检查代理检测可用性。代理配置和进程痕迹属于启发式信号。

`SecurityNetworkPolicy` 接入 API、订阅、图标、测速下载、公网 IP 查询、分应用规则及 Geo/规则资源下载。防护开启后：

- 从首次扫描之前开始忽略系统 HTTP/PAC 代理，不依赖检测是否命中。
- 业务请求只允许 HTTPS，固定回环地址用于应用内控制；网络拦截器检查每个重定向和连接池复用后的实际路由，仅允许直连或显式的本地 SOCKS 出口。
- 保留 Android 的系统 CA 信任和主机名验证，不信任用户安装的 CA，不加入 trust-all 或忽略主机名的逻辑。证书验证失败时停止当前请求并记录 `tls_validation_failed`，不会退回 HTTP。
- 原生库未加载、扫描异常、签名或关键进程信息不可读取时停止业务请求；已确认的签名不匹配、Frida 映射/特征线程及进程跟踪也阻止业务请求。
- 已有的显式 HTTP 节点探测仍可用于连通性测试，不承载应用账户请求；HTTPS 探测禁止重定向降级，TLS 错误会记录。SOCKS/TUN 数据路径保持可用，不把 VPN 接口本身判为抓包。

`capture_detection_unavailable`、`process_maps_unavailable`、`frida_threads_unavailable`、`tracer_status_unavailable` 用于区分检查失败与未命中。Frida 端口探测不再读取 Android 10+ 禁止访问的 `/proc/net`；改为非阻塞连接本机 IPv4/IPv6 的 27042/27043 端口，每次最多等待 40 ms。开放端口仅为弱信号；普通 GLib 线程名 `gmain/gdbus` 已移出 Frida 特征。

Release 保留 Warn/Error 日志，native 也记录新增检测码。应用内的安全检测及截图事件 Toast 已移除，Android 自身的系统提示仍由系统控制。

客户端无法识别所有被动抓包：对方仍可看到加密报文、目标 IP 和流量时间。当前没有绑定后端 SPKI 公钥；系统根证书库遭特权修改或进程遭 Hook 时，不能保证业务明文始终不可获取。不要使用实时连接中自动学习证书的方式建立固定信任。

截图检测使用 Android 14（API 34）加入的截图回调，需要声明 `DETECT_SCREEN_CAPTURE` 安装时权限。该回调只覆盖系统支持的截图操作，不检测 ADB 或 instrumentation 截图及屏幕录制；旧版 Android 不提供此回调。
