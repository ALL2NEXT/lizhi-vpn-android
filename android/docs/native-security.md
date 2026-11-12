# 原生安全检测

每种检测都放在独立的 native 文件中，位于 `app/src/main/cpp/security/detection/`：

- `capture_proxy_detector.cpp`：系统 HTTP 代理检测
- `xposed_detector.cpp`：Xposed 痕迹检测
- `lsposed_detector.cpp`：LSPosed 痕迹检测
- `frida_detector.cpp`：Frida、进程跟踪和监听端口检测
- `signature_detector.cpp`：APK 签名校验
- `blacklist_detector.cpp`：软件包黑名单检测
- `adb_detector.cpp`：ADB 调试开关检测
- `developer_mode_detector.cpp`：开发者选项开关检测
- `screenshot_capture_detector.cpp`：截图回调事件

`security_detector.cpp` 负责调度编译时开启的检测，`detection_utils.cpp` 提供共用方法。Kotlin JNI 入口和 Android 截图回调监听位于
`app/src/main/java/io/github/HanJason233/chimera/security/detection/`。

处理代码也在独立目录 `app/src/main/cpp/security/handling/`：

- `initial_findings_handler.cpp`：启动检测结果汇总提示
- `screenshot_capture_handler.cpp`：截图事件提示
- `crash_handler.cpp`：可配置的原生进程终止
- `screenshot_block_handler.cpp`：调用 Android 安全窗口标记阻止截图
- `http_proxy_block_handler.cpp`：检测到系统 HTTP 代理后绕过该代理
- `security_report_handler.cpp`：调用 Kotlin 网络桥上报风险代码
- `toast_notifier.cpp`：通过 JNI 调用 Android Toast

安全策略由 native 编译开关选择；Kotlin 仅桥接 `FLAG_SECURE` 和后台 HTTP 请求等 Android 平台能力。默认仍是提示，不会终止应用或拦截请求。

每个检测项默认开启，可通过 Gradle 属性单独控制：

| Gradle 属性 | 检测项 |
| --- | --- |
| `chimera.security.detectCapture` | 系统 HTTP 代理配置 |
| `chimera.security.detectXposed` | Xposed 痕迹 |
| `chimera.security.detectLsposed` | LSPosed 痕迹 |
| `chimera.security.detectFrida` | Frida 映射、线程名、端口和 tracer PID |
| `chimera.security.detectScreenshot` | Android 截图回调（API 34+） |
| `chimera.security.verifySignature` | 当前 APK 签名证书 SHA-256 |
| `chimera.security.detectBlacklist` | 已知抓包、Hook 和 Root 软件包名 |
| `chimera.security.detectAdb` | 系统 ADB 调试开关 |
| `chimera.security.detectDeveloperMode` | 系统开发者选项开关 |

处理方式也可分别在构建时开启，默认关闭：

| Gradle 属性 | 行为 |
| --- | --- |
| `chimera.security.crashOnFinding` | 有风险信号时通过原生 `abort()` 结束进程；和上报开关不能同时开启 |
| `chimera.security.blockScreenshots` | 截图检测开启时，给主窗口预先设置 `FLAG_SECURE`，阻止系统截图和普通录屏 |
| `chimera.security.blockHttpProxy` | 检测到系统 HTTP 代理后，让应用的 OkHttp 请求绕过该代理；Android VPN/TUN 路由保持可用 |
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
  '-Pchimera.security.detectDeveloperMode=false'
```

只需给要开启的检测设置 `true`；未指定的属性默认是 `true`。签名校验默认使用当前项目签名证书的 SHA-256。更换签名密钥时，可通过 `-Pchimera.security.expectedCertSha256=<64位十六进制SHA256>` 指定预期值。

抓包检测只读取系统 HTTP 代理配置和已知抓包软件包名，不检查 VPN/TUN 接口，也不把普通 VPN 应用作为抓包软件。代理配置和进程痕迹属于启发式信号，结果需要结合设备环境判断。

截图检测使用 Android 14（API 34）加入的截图回调，需要声明 `DETECT_SCREEN_CAPTURE` 安装时权限。该回调只覆盖系统支持的截图操作，不检测 ADB 或 instrumentation 截图及屏幕录制；旧版 Android 不提供此回调。
