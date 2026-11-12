package io.github.HanJason233.chimera.security.detection

import io.github.HanJason233.chimera.BuildConfig

data class SecurityInspection(
    val codes: Set<String>,
    val inspectedAtMillis: Long,
    val durationMillis: Long
)

enum class SecurityCheckStatus(val label: String) {
    PASS("未见异常"), NOTICE("需关注"), RISK("异常"), UNAVAILABLE("无法检测"),
    DISABLED("未启用"), UNSUPPORTED("不支持"), MONITORING("监听中"), INACTIVE("未监听")
}

data class SecurityCheckItem(
    val id: String,
    val title: String,
    val status: SecurityCheckStatus,
    val summary: String,
    val details: List<String> = emptyList()
)

data class SecurityCheckOptions(
    val signature: Boolean = true,
    val proxy: Boolean = true,
    val xposed: Boolean = true,
    val lsposed: Boolean = true,
    val frida: Boolean = true,
    val blacklist: Boolean = true,
    val adb: Boolean = true,
    val developerMode: Boolean = true,
    val screenshot: Boolean = true,
    val root: Boolean = true,
    val lspatch: Boolean = true,
    val npatch: Boolean = true
) {
    companion object {
        fun current() = SecurityCheckOptions(
            signature = BuildConfig.SECURITY_VERIFY_SIGNATURE,
            proxy = BuildConfig.SECURITY_DETECT_CAPTURE || BuildConfig.SECURITY_BLOCK_HTTP_PROXY,
            xposed = BuildConfig.SECURITY_DETECT_XPOSED,
            lsposed = BuildConfig.SECURITY_DETECT_LSPOSED,
            frida = BuildConfig.SECURITY_DETECT_FRIDA,
            blacklist = BuildConfig.SECURITY_DETECT_BLACKLIST,
            adb = BuildConfig.SECURITY_DETECT_ADB,
            developerMode = BuildConfig.SECURITY_DETECT_DEVELOPER_MODE,
            screenshot = BuildConfig.SECURITY_DETECT_SCREENSHOT,
            root = BuildConfig.SECURITY_DETECT_ROOT,
            lspatch = BuildConfig.SECURITY_DETECT_LSPATCH,
            npatch = BuildConfig.SECURITY_DETECT_NPATCH
        )
    }
}

data class SecuritySelfCheckReport(val items: List<SecurityCheckItem>) {
    val environmentItems get() = items.filter { it.id !in setOf("component", "screenshot") }
    val riskCount get() = items.count { it.status == SecurityCheckStatus.RISK }
    val noticeCount get() = items.count { it.status == SecurityCheckStatus.NOTICE }
    val unavailableCount get() = items.count { it.status == SecurityCheckStatus.UNAVAILABLE }
    val completedCount get() = environmentItems.count {
        it.status in setOf(SecurityCheckStatus.PASS, SecurityCheckStatus.NOTICE, SecurityCheckStatus.RISK)
    }
    val title: String get() = when {
        riskCount > 0 -> "发现环境异常信号"
        unavailableCount > 0 -> "部分检查未能完成"
        noticeCount > 0 -> "有项目需要关注"
        environmentItems.all { it.status == SecurityCheckStatus.DISABLED } -> "环境检测未启用"
        else -> "已检查项目未见异常"
    }
}

fun buildSecuritySelfCheckReport(
    inspection: SecurityInspection,
    options: SecurityCheckOptions,
    screenshotStatus: ScreenshotCaptureStatus
): SecuritySelfCheckReport {
    val codes = inspection.codes
    val componentFailed = codes.any { it in setOf("native_library_unavailable", "native_scan_error") }
    val knownCodes = mutableSetOf("native_library_unavailable", "native_scan_error")
    val items = mutableListOf<SecurityCheckItem>()
    items += SecurityCheckItem(
        "component", "检测组件",
        if (componentFailed) SecurityCheckStatus.UNAVAILABLE else SecurityCheckStatus.PASS,
        if (componentFailed) "安全检测组件不可用，不能据此判断环境正常。"
        else "检测组件已就绪，本次环境读取已完成。"
    )

    fun check(
        id: String,
        title: String,
        enabled: Boolean,
        normalSummary: String,
        signals: Map<String, String>,
        unavailable: Set<String> = emptySet(),
        critical: Set<String> = emptySet(),
        unavailableMessages: Map<String, String> = emptyMap(),
        advice: String
    ) {
        knownCodes += signals.keys
        knownCodes += unavailable
        val matched = signals.filterKeys { it in codes }
        val incomplete = codes.any { it in unavailable }
        val status = when {
            !enabled -> SecurityCheckStatus.DISABLED
            componentFailed -> SecurityCheckStatus.UNAVAILABLE
            matched.isNotEmpty() && codes.any { it in critical } -> SecurityCheckStatus.RISK
            matched.isNotEmpty() -> SecurityCheckStatus.NOTICE
            incomplete -> SecurityCheckStatus.UNAVAILABLE
            else -> SecurityCheckStatus.PASS
        }
        val summary = when (status) {
            SecurityCheckStatus.DISABLED -> "此版本未启用这项检测。"
            SecurityCheckStatus.UNAVAILABLE -> "无法完成这项检查，不能视为检测通过。"
            SecurityCheckStatus.RISK, SecurityCheckStatus.NOTICE -> advice
            else -> normalSummary
        }
        items += SecurityCheckItem(
            id, title, status, summary,
            matched.values.toList() + unavailable.filter { it in codes }.map {
                unavailableMessages[it] ?: "部分检测信息无法读取。"
            }.distinct()
        )
    }

    check(
        "signature", "应用签名", options.signature, "当前签名与预期证书一致。",
        mapOf("signature_mismatch" to "应用签名与预期证书不匹配。"),
        unavailable = setOf("signature_unavailable"), critical = setOf("signature_mismatch"),
        advice = "请核对安装来源，并使用可信来源提供的安装包。"
    )
    check(
        "proxy", "系统代理", options.proxy, "未发现系统 HTTP 或 PAC 代理配置。",
        mapOf(
            "http_proxy_configured" to "系统配置了 HTTP 代理。",
            "http_proxy_pac_configured" to "系统配置了 PAC 自动代理。"
        ),
        unavailable = setOf("capture_detection_unavailable"),
        advice = "发现系统代理配置；代理配置本身不能证明正在抓包。"
    )
    check(
        "xposed", "Xposed 痕迹", options.xposed, "未发现已知 Xposed 类、加载器、缓存、堆栈或文件痕迹。",
        mapOf(
            "xposed_artifact" to "发现已知的 Xposed 文件或进程映射痕迹。",
            "xposed_class" to "应用可访问 Xposed 框架核心类。",
            "xposed_classloader" to "ClassLoader 链或 DEX 路径出现 Xposed 特征。",
            "xposed_method_cache" to "XposedHelpers 方法缓存非空。",
            "xposed_hook_callbacks" to "XposedBridge 存在已注册的 Hook 回调。",
            "xposed_stack" to "当前线程调用栈出现 Xposed 或 Substrate 框架。"
        ),
        unavailable = setOf("xposed_classloader_unavailable", "xposed_maps_unavailable"),
        critical = setOf("xposed_class", "xposed_classloader", "xposed_method_cache", "xposed_hook_callbacks", "xposed_stack"),
        advice = "发现框架痕迹，请核对设备上的模块和应用作用域。"
    )
    check(
        "lsposed", "LSPosed 与 ART Hook", options.lsposed, "未发现已知 LSPosed 类、Hook 堆栈或 ART 方法标志异常。",
        mapOf(
            "lsposed_artifact" to "发现已知的 LSPosed 路径或进程映射痕迹。",
            "lsposed_class" to "应用可访问 LSPosed 框架核心类。",
            "lsposed_classloader" to "ClassLoader 链或 DEX 路径出现 LSPosed 特征。",
            "lsposed_stack" to "当前线程调用栈出现 LSPosed/LSPlant。",
            "art_method_native" to "已知 Java 方法被异常标记为 native；可能存在 ART Hook。"
        ),
        unavailable = setOf("lsposed_classloader_unavailable", "lsposed_maps_unavailable"),
        critical = setOf("lsposed_class", "lsposed_classloader", "lsposed_stack", "art_method_native"),
        advice = "发现框架痕迹，请核对设备上的模块和应用作用域。"
    )
    check(
        "frida", "Frida 与 native 完整性", options.frida, "未发现已知映射、线程、管道、端口、跟踪或 native 代码修改信号。",
        mapOf(
            "frida_maps" to "进程映射中存在已知 Frida 特征。",
            "frida_thread" to "进程中存在已知 Frida 特征线程。",
            "frida_named_pipe" to "进程文件描述符指向 Frida/linjector 特征管道或文件。",
            "frida_server_artifact" to "发现已知 frida-server 文件。",
            "frida_dbus" to "本机常见 Frida 端口返回了 D-Bus AUTH 协议响应。",
            "native_code_modified" to "本应用 native 库或 libc 的可执行代码与磁盘文件不一致。",
            "native_code_writable" to "本应用 native 库或 libc 出现可写的可执行映射。",
            "instrumentation_tracer" to "当前进程存在跟踪者。",
            "instrumentation_thread_tracer" to "当前进程的工作线程存在跟踪者。",
            "frida_listener" to "本机常见调试端口开放；端口本身不能确认 Frida。"
        ),
        unavailable = setOf("process_maps_unavailable", "frida_threads_unavailable", "frida_fds_unavailable", "tracer_status_unavailable", "frida_integrity_unavailable"),
        critical = setOf("frida_maps", "frida_thread", "frida_named_pipe", "frida_server_artifact", "frida_dbus", "native_code_modified", "native_code_writable", "instrumentation_tracer", "instrumentation_thread_tracer"),
        unavailableMessages = mapOf("frida_integrity_unavailable" to "当前系统限制代码段或 /proc/self/mem 读取，native 完整性校验未全部完成；其余 Frida 检查仍执行。"),
        advice = "发现调试或注入相关信号，请展开查看具体命中项。"
    )
    val rootSignals = mapOf(
        "root_uid" to "应用进程以 Root 身份运行。",
        "root_su_binary" to "发现可访问的 su 或 Magisk 可执行文件。",
        "root_framework_artifact" to "发现 Magisk、KernelSU/APatch 或模块目录。",
        "root_mount" to "进程挂载信息出现 Root 框架专属路径或名称。",
        "root_selinux_context" to "进程 SELinux 上下文出现 Root 框架域。",
        "root_capabilities" to "应用进程持有非零的有效 Linux capabilities。",
        "root_magisk_service" to "系统属性显示 Magisk 服务正在运行。"
    )
    check(
        "root", "Root 与系统提权", options.root, "未发现可见的 su、Root 框架、特征挂载或进程提权信号。",
        rootSignals, unavailable = setOf("root_mounts_unavailable", "root_status_unavailable"),
        critical = rootSignals.keys,
        advice = "发现 Root 环境或提权痕迹；Root 管理器的安装记录仍仅在软件名单中上报。"
    )
    val lspatchSignals = mapOf(
        "lspatch_component" to "ApplicationInfo 的组件工厂或 Application 类出现 LSPatch 注入特征。",
        "lspatch_metadata" to "安装包 metadata 存在 LSPatch 标记。",
        "lspatch_class" to "应用可访问 LSPatch 加载器或 Stub 类。",
        "lspatch_apk_asset" to "APK assets 内存在 LSPatch 配置、加载器或原始 APK。",
        "lspatch_private_files" to "应用私有目录中存在 LSPatch 缓存结构。",
        "lspatch_native" to "进程加载了 LSPatch 原生库或特征文件。"
    )
    check(
        "lspatch", "LSPatch 组件注入", options.lspatch, "未发现已知 LSPatch 组件、类、配置、缓存或原生库。",
        lspatchSignals, unavailable = setOf("lspatch_detection_unavailable"), critical = lspatchSignals.keys,
        advice = "发现 LSPatch 重打包或加载痕迹，请使用原始签名的安装包。"
    )
    val npatchSignals = mapOf(
        "npatch_component" to "ApplicationInfo 的组件工厂或 Application 类出现 NPatch 注入特征。",
        "npatch_metadata" to "安装包 metadata 存在 NPatch 标记。",
        "npatch_class" to "应用可访问 NPatch 加载器或 Stub 类。",
        "npatch_apk_asset" to "APK assets 内存在 NPatch 配置、加载器或原始 APK。",
        "npatch_private_files" to "应用私有目录中存在 npatch/npatch_origin 缓存结构。",
        "npatch_native" to "进程加载了 NPatch 原生库或特征文件。"
    )
    check(
        "npatch", "NPatch 文件与原生库", options.npatch, "未发现已知 NPatch 组件、文件结构或原生库。",
        npatchSignals, unavailable = setOf("npatch_detection_unavailable"), critical = npatchSignals.keys,
        advice = "发现 NPatch 重打包或加载痕迹，请使用原始签名的安装包。"
    )
    val packages = codes.filter { it.startsWith("blacklisted_package:") }
        .map { it.removePrefix("blacklisted_package:") }.sorted()
    items += SecurityCheckItem(
        "blacklist", "风险软件名单",
        when {
            !options.blacklist -> SecurityCheckStatus.DISABLED
            componentFailed -> SecurityCheckStatus.UNAVAILABLE
            packages.isNotEmpty() -> SecurityCheckStatus.NOTICE
            else -> SecurityCheckStatus.PASS
        },
        when {
            !options.blacklist -> "此版本未启用这项检测。"
            componentFailed -> "无法读取检测结果，不能视为检测通过。"
            packages.isNotEmpty() -> "发现 " + packages.size + " 个名单内软件；安装记录不代表正在抓包或注入。"
            else -> "本次未发现名单内的已安装软件。"
        },
        packages
    )
    check(
        "adb", "USB 调试", options.adb, "USB 调试开关未开启。",
        mapOf("adb_enabled" to "USB 调试开关已开启，不代表设备正在被调试。"),
        advice = "USB 调试已开启；不需要调试时可在系统设置中关闭。"
    )
    check(
        "developer", "开发者选项", options.developerMode, "开发者选项未开启。",
        mapOf("developer_options_enabled" to "系统开发者选项已开启。"),
        advice = "开发者选项已开启；此设置本身不代表设备遭到入侵。"
    )
    items += SecurityCheckItem(
        "screenshot", "截图事件监测",
        when {
            !options.screenshot || screenshotStatus == ScreenshotCaptureStatus.DISABLED -> SecurityCheckStatus.DISABLED
            screenshotStatus == ScreenshotCaptureStatus.UNSUPPORTED -> SecurityCheckStatus.UNSUPPORTED
            componentFailed || screenshotStatus == ScreenshotCaptureStatus.UNAVAILABLE -> SecurityCheckStatus.UNAVAILABLE
            screenshotStatus == ScreenshotCaptureStatus.ACTIVE -> SecurityCheckStatus.MONITORING
            else -> SecurityCheckStatus.INACTIVE
        },
        when {
            !options.screenshot || screenshotStatus == ScreenshotCaptureStatus.DISABLED -> "此版本未启用截图事件监测。"
            screenshotStatus == ScreenshotCaptureStatus.UNSUPPORTED -> "此系统低于 Android 14，不支持截图回调；截图防护状态单独列出。"
            componentFailed || screenshotStatus == ScreenshotCaptureStatus.UNAVAILABLE -> "截图监测尚未就绪，无法记录截图事件。"
            screenshotStatus == ScreenshotCaptureStatus.ACTIVE -> "正在监听系统支持的截图操作；截图属于事件，不计入环境通过项。"
            else -> "当前未注册截图监听，回到前台后会重新注册。"
        }
    )
    val unknown = codes.filterNot { it in knownCodes || it.startsWith("blacklisted_package:") }
    if (unknown.isNotEmpty()) {
        items += SecurityCheckItem(
            "other", "其他检测信号", SecurityCheckStatus.NOTICE,
            "存在尚未归类的检测结果，请核对应用版本。", unknown.sorted()
        )
    }
    return SecuritySelfCheckReport(items)
}

fun securityEventDescription(code: String): Pair<String, String>? = when (code) {
    "tls_validation_failed" -> "证书验证失败" to "本次运行曾拒绝无法验证的连接；不代表当前仍在被抓包。"
    "cleartext_traffic_blocked" -> "已阻止明文请求" to "本次运行曾阻止向外部地址发送未加密的业务请求。"
    "http_proxy_route_blocked" -> "已阻止外部代理请求" to "本次运行曾阻止通过不允许的代理路由发送业务请求。"
    "https_downgrade_blocked" -> "已阻止加密连接降级" to "本次运行曾阻止 HTTPS 重定向到未加密的地址。"
    "screenshot_capture" -> "收到截图事件" to "本次运行曾收到系统截图回调。"
    else -> null
}
