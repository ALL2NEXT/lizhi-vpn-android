# Android 多内核中间层

实现日期：2026-09-05 至 2026-09-06。每次 VPN 连接运行一个内核，根据选中节点的协议及已保存的内核偏好选择。当前实现 sing-box、Xray；Mihomo 源码已拉取并固定版本，尚未集成 Android 二进制或适配器。

## 架构与选择理由

采用 Kotlin 控制层、独立原生适配器、当前会话独占 TUN。控制层统一连接配置、平台资源、启动停止、测速和流量接口；网络数据包由选定内核直接处理。

| 部件 | 职责 | 入口 |
| --- | --- | --- |
| Android 服务 | 前台通知、设置、节点选择、断线恢复、流量持久化 | [BoxVpnService](../app/src/main/java/xyz/a202132/app/service/BoxVpnService.kt) |
| 会话控制器 | 串行替换、取消请求、回收迟到的启动结果、停止失败时保留所有权 | [CoreSessionController](../app/src/main/java/xyz/a202132/app/core/CoreSessionController.kt) |
| 后端契约与注册表 | `prepare/start/CoreSession`、协议与 TUN 能力、已安装后端发现 | [CoreBackend](../app/src/main/java/xyz/a202132/app/core/CoreBackend.kt)、[CoreBackends](../app/src/main/java/xyz/a202132/app/core/CoreBackends.kt) |
| Android 平台层 | VpnService.Builder、TUN FD、protect、分应用、物理网络监测 | [AndroidVpnHost](../app/src/main/java/xyz/a202132/app/platform/AndroidVpnHost.kt) |
| sing-box 适配器 | libbox 配置、CommandServer、原生流量统计 | [SingBoxBackend](../app/src/main/java/xyz/a202132/app/core/singbox/SingBoxBackend.kt) |
| Xray 适配器 | SOCKS5 配置、配置预检、FD 控制通道、子进程与统计 | [XrayBackend](../app/src/main/java/xyz/a202132/app/core/xray/XrayBackend.kt)、[运行器](../native/xray-runner/main.go) |
| 规则缓存 | 从共同的 SRS 源生成域名/IP 快照 | [CoreRuleRepository](../app/src/main/java/xyz/a202132/app/core/route/CoreRuleRepository.kt) |
| 测速 | 同一后端契约上的无 TUN 会话 | [NodeTestProxyManager](../app/src/main/java/xyz/a202132/app/network/NodeTestProxyManager.kt)、[UrlTestManager](../app/src/main/java/xyz/a202132/app/network/UrlTestManager.kt) |

实际数据路径：

```text
选中任意核心：Android TUN → HEV tun2socks → 核心 loopback SOCKS5 → 网络

控制关系：UI → BoxVpnService → CoreSessionController → 已注册 CoreBackend
平台资源：AndroidVpnHost → 当前会话的 TUN FD / protect / 底层网络
```

旧实现的 `TUN → sing-box → 本地 SOCKS → Xray` 已移除。连接只构建选中节点，不再因候选列表里存在 Xray 节点而启动 Xray。旧的桥接管理器和端口映射已删除。

原生会话仍提供一个回环 SOCKS 入口，供本应用的出口探测、下载测试及其他显式代理请求使用；普通 VPN 流量直接进入 TUN。中间层不传递业务数据包，不在每包路径引入 Kotlin、JNI 或控制 IPC 调用。

相较把所有内核编进一个 Go/AAR，这种方式允许各内核保留独立依赖和升级路径；相较自建 TCP/IP 层，它避免长期维护额外的传输栈和内核内部出站接口。它适合本项目已确认的“每次连接一个内核”要求。未来若要求同一连接同时混用多个内核，需要另行设计数据层。

## 固定版本与 Xray 原生接入

- sing-box：`58ccf82e0bfc5e724421d2ba03157fe7f0fee537`，`v1.13.0-rc.3`。
- Xray：`d2758a023cd7f4174a5a5fa4ff66e487d4342ba0`，`v1.260327.0`。
- 两者分别依赖 `github.com/sagernet/sing` 的 `v0.8.0-beta.16`、`v0.5.1`；维持独立 Go 构建。

`libxray.so` 是打包到 `nativeLibraryDir` 的 Android PIE 可执行文件，通过 ProcessBuilder 运行。它不是 JNI 动态库。Xray 子进程只提供 loopback SOCKS5 和 socket protect，不直接获得 Android TUN FD。

控制协议位于 [XrayNativeSession](../app/src/main/java/xyz/a202132/app/core/xray/XrayNativeSession.kt) 和 [run_unix.go](../native/xray-runner/run_unix.go)：

1. 为会话创建随机抽象 Unix socket 和 token，检查连接方 UID 与 token。
2. HEV 通过自己的控制通道接收 Android TUN FD；Xray 控制通道只处理 `PROTECT` 和 `WATCH`。
3. `PROTECT` 将待连接/监听 socket 的 FD 副本交给 Android 调用 `VpnService.protect`，收到成功响应后才继续。固定版 Xray 会忽略普通 controller 返回错误，因此运行器在保护失败时直接退出。
4. `WATCH` 持有父进程存活通道；父进程退出或关闭通道后，运行器退出。
5. stdout 返回就绪、每秒累计流量和通用错误事件；配置文件保存在私有缓存目录，就绪或失败后删除，不向 UI 转发原始配置及敏感错误。

配置预检使用 `--check`，只进行 JSON/配置构建，不启动 Xray。即使 Xray 本身不再持有 Android TUN，也保留这个无副作用检查入口。

## DNS、IPv6、规则与统计

[XrayVpnConfig](../app/src/main/java/xyz/a202132/app/core/xray/XrayVpnConfig.kt) 只为 Xray 生成 SOCKS5、DNS、出站和路由。DNS 53 端口拦截排在私网直连前；节点域名使用独立直连 DNS bootstrap，远程 DoH 经代理出站，防止解析代理服务器时递归进入自身代理。Android TUN 地址与路由统一由 HEV 配置。

智能分流使用当前下载的 `geoip-cn.srs` 和 `geosite-cn.srs`，与 sing-box 共用数据源。规则转换仅在源内容 SHA-256 改变时运行，快照写入应用私有目录。下载替换使用同目录原子 rename，避免旧文件删除与新文件落地之间的空窗。

转换器接受 SRS 1–4 中的正向平面域名/IP 集合，拒绝逻辑、反选及其他不能无损转换的条件。快照 schema 1 的 `domains` 使用 `full:`、`domain:`、`keyword:`、`regexp:` 表达匹配类型，`ips` 使用 CIDR；以后 Mihomo 适配器可按匹配类型映射自身配置。以点开头的后缀保留“只匹配子域名”语义，不扩大到根域名。当前内置资源包含 8,528 条域名匹配、7,638 个 IP 前缀。

sing-box 和 Xray 都通过同一个 HEV tun2socks 数据平面运行，不再保留 core-owned TUN stack 选择。

HEV 不提供 ICMP 转发，不能用 ping 成败代替 TCP/UDP 连通判定。Xray DNS 的 A/AAAA 使用内置解析器；SRV/TXT/HTTPS 等其他类型通过所选代理转发到远端 TCP DNS，避免把非地址查询发回虚拟 TUN DNS 地址。

流量从各会话的原生计数器读取。Xray 通过 dispatcher 装饰器统计内部 SOCKS 的有效载荷，复用原 MultiBuffer；停止时尽量读取最终计数。sing-box 使用自己的状态流。两者是会话流量口径，不代表底层加密后线速字节；停止瞬间及进程异常死亡可能有采样尾差，不使用应用 UID 总流量代替内核流量。

## 生命周期与测速

新请求立即使旧请求过期。原生启动返回后，即使协程已取消，也必须接管并关闭其结果；停止旧会话后才允许新会话读取 TUN。Android 服务销毁后的异步清理与新服务实例启动共用锁及所有者检查，避免旧清理关闭新连接。

保留前台通知、分应用、断线恢复及系统 sticky/always-on 重新启动入口。重新建立 VPN 后同步实际节点，恢复和通知操作继续走同一个控制层。允许列表为空时明确拒绝建立 VPN，防止 Android 将空列表解释为全应用代理。

URL 测速不再依赖正在运行的 VPN 或 Clash API，每个节点通过自己选择的后端建立临时无 TUN 会话。计时从 HTTP 请求开始，不把运行器启动耗时算入节点延迟。原生 URL 探测同时最多运行 4 个会话，避免较高的网络探测并发设置同时创建大量 Go 运行器；用户设置低于 4 时仍按用户设置执行。

Xray 输出管道关闭、读取失败或事件解析异常会转换为会话失败，避免从 `SupervisorJob` 下的独立 `launch` 传播到 Android 未捕获异常处理器。读取线程不等待子进程退出，以便失败回调及时停止运行器。单节点启动及清理失败由测速层隔离；整批异常在界面层显示错误，协程取消仍正常向上传播。回归测试覆盖管道中途关闭、关闭流本身失败、事件处理异常，以及故障会话不触发未捕获异常或取消同级任务。尚无用户设备的崩溃日志，不能仅凭这些测试确认所报告闪退的实际堆栈。

## HEV 数据平面

纯 HEV 路径不再依赖 Xray 的 `proxy/tun` 实现。Android TUN 只由 HEV 打开和消费，核心统一接收 loopback SOCKS5 流量：

- HEV 负责 TUN FD、IPv4/IPv6 路由、UDP/QUIC 转发和 mapped DNS。
- 核心只需要提供 `internal-socks-in`，不再初始化或接收 Android TUN。
- HEV 和核心以组合会话关闭，先停止 HEV，再停止核心。

## 后续接入 Mihomo

1. 在 `core/mihomo/` 实现 `CoreBackend`、配置编译及 `CoreSession`，提供本地 SOCKS5 inbound。
2. 复用 `HevSocksTunAdapter`，不再由新核心申请或持有 Android TUN。
3. 实现原生统计、就绪/异常事件及幂等停止；测速 `settings == null` 时不启动 HEV。
4. 增加固定版本源码与 ARM32/ARM64 构建，然后只在 `CoreBackends` 注册后端。UI 根据注册表展示可选内核，偏好使用可扩展的 `协议=内核ID` 存储；旧 Xray 协议偏好继续可读。
5. 补充实际配置校验、生命周期与设备连通验证。未提供可运行适配器前，不向用户展示可选 Mihomo。

## 验证与构建

Android 构建需要 JDK 17、Go 1.26、Android SDK 35 和 NDK `28.0.13004108`。sing-box 官方构建器检查 JDK 主版本，JDK 21 不能代替此处的 JDK 17。构建前初始化固定子模块。

进程管理经 [NativeProcess](../app/src/main/java/xyz/a202132/app/platform/NativeProcess.kt) 兼容 Android 7；`Process.isAlive`、超时 `waitFor` 和 `destroyForcibly` 从 API 26 才提供，不能无条件调用。参见 [Android Process API](https://developer.android.com/reference/java/lang/Process)。

```powershell
# 在 android/ 下，JAVA_HOME 指向 JDK 17
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

# 在 android/native/xray-runner/ 下
go test ./...
```

JVM 测试覆盖注册第三后端、旧设置兼容、会话取消/替换/停止失败、DNS 顺序及 HEV 配置；Go 测试验证 Xray runner 不提前启动、统计缓冲所有权、socket protect 和规则内容。规则测试的 JSON 基准由 sing-box CLI decompile 生成，仅存在于测试资源，不随 APK 打包。

最终本地验证：63 项 JVM 测试通过，Go 运行器和 Android UDP overlay 测试通过，Lint 为 0 errors / 29 warnings。另用真实 Xray Router 验证了“域名未命中 geosite、解析结果命中国内 IP”仍选择直连；配置依靠首个代理出站兜底，避免显式全匹配规则阻断 `IPIfNonMatch` 的二次匹配。

构建输出为 `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` 和 `app-armeabi-v7a-debug.apk`。本机已完成源码构建和本地测试；没有连接 Android 设备，因此 TUN FD/protect 的实际运行、UDP/QUIC 连通、IPv6、网络切换、always-on 恢复及吞吐/功耗仍需设备验证。

测速异常修复已通过 `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease`。签名修复包位于 `app/build/outputs/apk/release/app-arm64-v8a-release.apk` 和 `app-armeabi-v7a-release.apk`，均通过 APK v2 签名校验；两种 ABI 中的 `libbox.so`、`libxray.so` 均与本次构建的 stripped native 输出一致。

消除串联转发和无用内核实例是可由代码确认的结构改善，不能据此宣称实测“性能最强”。性能比较应固定节点、服务器与网络，分别记录两种内核的连接耗时、TCP/UDP 吞吐、P95 延迟、CPU/RSS 及耗电，并与旧版本对照。

## HEV tun2socks compatibility adapter

HEV is suitable as a compatibility data plane when a core exposes a local SOCKS5 inbound but does not provide a reliable Android TUN integration. The implemented path is:

```text
AndroidVpnHost TUN -> hev-runner (HEV 2.16.0) -> core loopback SOCKS5 -> selected core outbound
```

`TunAdapter` and `TunAdapterSession` keep this path independent from the core control contract. `CombinedCoreSession` owns both objects, reports the HEV TUN counters once, and closes HEV before the proxy core. Any future backend only needs to generate a local SOCKS5 inbound; all VPN backends use the same HEV data plane.

The HEV runner is a standalone Android PIE executable. It exchanges the TUN descriptor, liveness, and statistics through a private abstract Unix socket; packet data does not cross Kotlin or JNI. The build pins HEV 2.16.0, stages the source to resolve Git symlink headers on Windows, and packages only `libhev.so` for `armeabi-v7a` and `arm64-v8a`. URL Test never starts HEV.

Mapped DNS is enabled for domain preservation (`198.18.0.2` with the `198.18.0.0/16` fake-IP map). IPv6-only routing is migrated to dual-stack because the pure HEV path does not expose an IPv6-only DNS/data path. The HEV adapter is mandatory for VPN sessions; device-level TUN, socket protection, IPv6, UDP/QUIC, and teardown validation still require an Android device.

VPN settings no longer expose a per-core TUN selector. The Android TUN is always opened by HEV and each selected core only provides the loopback SOCKS5 inbound. Legacy native-TUN preferences are ignored, so upgrades converge on the single HEV data plane.
