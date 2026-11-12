# 原生安全检测

每种检测都放在独立的 native 文件中，位于 `app/src/main/cpp/security/detection/`：

- `capture_proxy_detector.cpp`：系统 HTTP 代理检测
- `xposed_detector.cpp`：Xposed 痕迹检测
- `lsposed_detector.cpp`：LSPosed 痕迹检测
- `frida_detector.cpp`：Frida、进程跟踪和监听端口检测
- `signature_detector.cpp`：APK 签名校验
- `blacklist_detector.cpp`：软件包黑名单检测
- `screenshot_capture_detector.cpp`：截图回调事件

`security_detector.cpp` 负责调度编译时开启的检测，`detection_utils.cpp` 提供共用方法。Kotlin JNI 入口和 Android 截图回调监听位于
`app/src/main/java/io/github/HanJason233/chimera/security/detection/`。

处理代码也在独立目录 `app/src/main/cpp/security/handling/`：

- `initial_findings_handler.cpp`：启动检测结果汇总提示
- `screenshot_capture_handler.cpp`：截图事件提示
- `toast_notifier.cpp`：通过 JNI 调用 Android Toast

Kotlin 只负责注册 Android 截图回调并调用 JNI。检测结果会写入 `NativeSecurity` 日志，也会通过 `NativeSecurityDetector.findings` 暴露给上层。处理方式目前只提示，不会拦截操作或终止应用。

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

PowerShell 中可这样关闭所有检测并构建 Debug 包：

```powershell
.\gradlew.bat :app:assembleDebug `
  '-Pchimera.security.detectCapture=false' `
  '-Pchimera.security.detectXposed=false' `
  '-Pchimera.security.detectLsposed=false' `
  '-Pchimera.security.detectFrida=false' `
  '-Pchimera.security.detectScreenshot=false' `
  '-Pchimera.security.verifySignature=false' `
  '-Pchimera.security.detectBlacklist=false'
```

只需给要开启的检测设置 `true`；未指定的属性默认是 `true`。签名校验默认使用当前项目签名证书的 SHA-256。更换签名密钥时，可通过 `-Pchimera.security.expectedCertSha256=<64位十六进制SHA256>` 指定预期值。

抓包检测只读取系统 HTTP 代理配置和已知抓包软件包名，不检查 VPN/TUN 接口，也不把普通 VPN 应用作为抓包软件。代理配置和进程痕迹属于启发式信号，结果需要结合设备环境判断。

截图检测使用 Android 14（API 34）加入的截图回调，需要声明 `DETECT_SCREEN_CAPTURE` 安装时权限。该回调只覆盖系统支持的截图操作，不检测 ADB 或 instrumentation 截图及屏幕录制；旧版 Android 不提供此回调。
