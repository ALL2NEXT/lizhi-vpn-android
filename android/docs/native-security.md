# 原生安全检测

native 安全代码直接放在 `app/src/main/cpp/security/`，不再包含 `detection/`、`handling/` 子目录。每个检测项在自己的文件内完成检测与处置。

| 文件 | 检测与默认处置 |
| --- | --- |
| `screenshot_capture_detector.cpp` | Activity 创建时设置 `FLAG_SECURE`，禁止应用窗口截图和普通录屏；不采集截图事件 |
| `blacklist_detector.cpp` | 命中软件包名单时上报风险数据 |
| `adb_detector.cpp` | USB 调试开关开启时上报风险数据 |
| `developer_mode_detector.cpp` | 开发者选项开启时上报风险数据 |
| `signature_detector.cpp` | 上报签名不匹配或签名信息不可读取 |
| `xposed_detector.cpp` | 检查核心类、ClassLoader/DEX 路径、方法缓存、Hook 回调、调用栈、映射与文件；命中后上报 |
| `lsposed_detector.cpp` | 检查 LSPosed 类、加载器、调用栈、LSPlant 映射及已知 Java 方法的异常 native 标志；命中后上报 |
| `frida_detector.cpp` | 检查映射、特征线程、管道/FD、server 文件、端口、D-Bus、跟踪者与 native 代码完整性；命中或关键进程信息不可读取时上报 |
| `root_detector.cpp` | 检查 su/PATH、Magisk/KernelSU/APatch 文件、专属挂载、SELinux 域、有效 capabilities 与 Magisk 服务；命中后上报 |
| `lspatch_detector.cpp` | 检查 ApplicationInfo 组件工厂/Application、metadata、加载器类、APK assets、私有缓存与映射；命中后上报 |
| `npatch_detector.cpp` | 检查 NPatch 组件、metadata、加载器类、APK 配置/原始包、私有目录结构与原生库映射；命中后上报 |
| `capture_proxy_detector.cpp` | 保持系统代理隔离；记录 HTTP/PAC 系统代理或代理检测不可用情况 |
| `security.cpp / security.h` | 调度检测、收集风险代码并写入日志 |
| `detection_utils.cpp / detection_utils.h` | 有界文件/目录读取、arm64 直接系统调用、代码字节对比、ClassLoader、ApplicationInfo、系统设置和软件包查询辅助方法 |

Kotlin 的 `security` 包负责 JNI 调用、Android 窗口、网络请求和待上报队列等平台能力。

## 执行时机

启动、Activity 回到前台和 VPN 建连前执行完整扫描；Activity 可见或 VPN 运行期间每 30 秒复检。系统代理变化时和每次业务请求前刷新代理策略。扫描结果由 Kotlin 写入本地待上报队列，上传失败时保留并在后续机会重试。风险命中本身不会终止进程或拦截请求；只有不符合 TLS、代理或明文传输策略的单个请求会被拒绝。

安全检测通过 `nativeScan` 自动执行。所有可上报的检测结果都通过同一队列上传。后台接受风控报告后，会将报告设备当前关联账号的 `identity_type` 自动设为 `bot`。截图只通过 `FLAG_SECURE` 禁止，不采集截图事件。侧栏和登录页不提供手动检测入口，应用不弹出安全检测 Toast。

原生库无法加载或扫描失败会作为风险代码上报，不会结束进程。普通网络超时、断网和请求取消不作为抓包命中。

## 风险数据上报

客户端上报检测代码、设备 ID 和服务端时间；同一设备与代码归并计数，不上传截图或原始请求内容。客户端进程内会对代码去重；本地最多保留 64 个代码，每批最多上传 20 个。断网、离线模式或上传失败时保留队列，在后续前台/账户同步等机会重试。

后台 `/api/account/security-report` 校验设备和代码后，在同一数据库批处理中保存报告并把当前关联账号身份设为 `bot`。报告仍保留在管理端供后续查看；自动分类不会封禁账号或设备。

## 抓包防护

系统代理隔离从首次扫描之前持续生效，不依赖检测是否命中。以下情况会拒绝对应的网络请求并上报风险代码，应用进程继续运行：

- TLS 证书、信任链或主机名验证失败。
- 业务请求试图通过不允许的外部代理路由发送。
- 业务请求试图向外部地址发送未加密 HTTP。
- HTTPS 连接尝试重定向降级到 HTTP。

API、订阅、图标、测速下载、公网 IP 查询、分应用规则及 Geo/规则资源下载接入统一网络策略。固定回环地址和显式的 HTTP 节点连通性探测保留原用途，不承载应用账户请求；HTTPS 节点探测同样禁止降级。VPN/TUN 接口本身不作为抓包证据。

抓包相关网络事件通过 JNI 回到 Kotlin 上报队列。系统 HTTP/PAC 代理仍会被隔离；这些网络安全措施只影响对应请求，不触发进程退出。

Frida 端口探测使用有界的本机 IPv4/IPv6 连接，不读取 Android 10+ 限制的 `/proc/net`。开放端口本身不能证明 Frida，因此命中只作为风险信号上报。普通 GLib 线程名 `gmain/gdbus` 不作为 Frida 特征。

## 注入与提权检查

参考并独立实现以下项目中的检测思路，固定阅读版本，未引入上游运行时代码、二进制或构建插件：

- [Catched](https://github.com/cnfatal/Catched/tree/c0ecaac9ea7dfd160ad16792f223be3e65977ff4)：ClassLoader、方法缓存、ART 方法标志、D-Bus、Root、LSPatch/NPatch。
- [DetectFrida](https://github.com/darvincisec/DetectFrida/tree/86a88feb9988b0d279c648dc8144c39cb128736d)：特征线程、命名管道、内存与磁盘中的 native 可执行代码对比、直接系统调用。

ClassLoader 检查覆盖应用、系统、当前扫描线程的 context loader 及其父链，查找已知框架核心类与明确的框架路径；不会仅因应用使用自定义加载器而判定异常。类探测使用 `Class.forName(name, false, loader)`，避免主动执行待查类的初始化；读取已存在的 Xposed 静态缓存可能触发该类初始化。未启用 VMDebug 全堆实例枚举。

ART 检查通过 JNI 反射读取 `Runtime.exec`、`ClassLoader.loadClass`、`Activity.onCreate`、`ContextWrapper.attachBaseContext` 等已知 Java 方法的 modifiers，检查异常 native 位。不会把 `jmethodID` 当指针、猜测 ArtMethod 偏移，也不会仅凭编译控制标志判定 Hook。反射不可访问的可选字段跳过，已支持的文件/映射检查继续执行。

Root 检查不执行 su、不发起提权请求。管理器安装记录仍属于软件名单；su、Root 专属挂载或实际进程提权痕迹作为风险信号上报。`test-keys`、普通 `overlay/tmpfs`、开发者选项本身不作为 Root 证据。受系统权限隐藏的 Root 文件不可见时，不能据此保证设备未 Root。

LSPatch/NPatch 只检查本应用的组件、metadata、资产、私有目录和已加载映射，不会仅因安装了对应管理器而上报风险。普通自定义 AppComponentFactory、仅 sourceDir 位于 cache、只读 profile 或通用 Seccomp/SIGSYS 状态均不单独作为重打包证据。

## Frida 检查细节

- maps、task、fd、readlink 和磁盘/内存读取在 arm64 上通过 SVC 直接调用 `openat/read/pread64/getdents64/readlinkat/faccessat/close`，减少对可被 Hook 的 libc 文件 API 的依赖。文本读取上限 4 MiB，目录最多 16,384 项，检测线程/FD 在枚举后退出或关闭属于正常竞态。
- 扫描 `frida`、`gum-js-loop`、`linjector` 特征线程及 Frida/linjector 文件描述符链接；读取各线程 status 的 TracerPid，覆盖只跟踪工作线程的情况。未读取所有进程，也不依赖外部进程或 `/proc/net` 可见性。
- 27042/27043 同时探测 IPv4/IPv6 回环；每个连接最多等待 40 ms，连接成功后共享 80 ms 收发预算，发送包含首字节 NUL 的 D-Bus `AUTH`，校验完整响应行。该握手覆盖使用 D-Bus 的实现；协议不同、改端口或改名不能保证检出。
- 使用 `dl_iterate_phdr` 获取本应用 native 库和 libc 的 `PT_LOAD/PF_X` 范围，依据 maps 的真实文件偏移逐字节比较磁盘文件与 `/proc/self/mem`，兼容独立 SO 与 APK 映射。每轮最多比较 16 MiB，按 8 KiB 分块读取；不使用可通过求和碰撞绕过的加法校验，也不调用 libc `memcmp` 比较代码。
- 代码不一致返回 `native_code_modified`，可写的可执行代码映射返回 `native_code_writable`，作为风险数据上报。此信号也能发现其他 inline Hook，不限定为 Frida。
- XOM 或系统禁止读取 `/proc/self/mem` 时返回能力状态 `frida_integrity_unavailable`，通过日志记录检测能力受限，其余检查继续；该状态不作为风险、不上传。maps、线程列表、FD 列表、TracerPid 等关键检查不可用时上报对应能力代码。

代码完整性检查在现有完整扫描周期内执行，没有另建无限循环线程。未照搬上游 O-LLVM 工具链或替换系统 libc，不扫描任意匿名内存以防自匹配和越界。

## 构建开关

Release 默认开启检测、报告和相关防护。Debug 默认关闭检测、截图和代理防护，但保留上报通道，便于显式启用检测时验证报告链路。

| Gradle 属性 | 默认值 | 行为 |
| --- | --- | --- |
| `chimera.security.blockScreenshots` | true | 预先禁止窗口截图，不依赖截图回调开关 |
| `chimera.security.blockHttpProxy` | true | 保持代理绕过及网络发送路径防护 |
| `chimera.security.reportFindings` | true | 将检测结果写入本地队列并上报，后台把关联账号身份标记为机器人 |

检测开关为 `detectCapture`、`detectXposed`、`detectLsposed`、`detectFrida`、`detectRoot`、`detectLspatch`、`detectNpatch`、`verifySignature`、`detectBlacklist`、`detectAdb`、`detectDeveloperMode`，均使用 `chimera.security.` 前缀。代理防护开启时，即使关闭 `detectCapture`，仍会检查系统代理。

`expectedCertSha256` 使用预期签名证书的 64 位十六进制 SHA-256；更换签名密钥时需通过 `-Pchimera.security.expectedCertSha256=<值>` 同步配置。

默认构建：

```powershell
.\gradlew.bat :app:assembleDebug
```

窗口截图保护通过 `FLAG_SECURE` 独立启用；应用不采集 Android 截图回调事件。Android 自身的系统提示由系统控制。

客户端无法识别所有被动抓包，对方仍可记录加密报文、目标 IP 和流量时间。当前没有绑定后端 SPKI 公钥；系统信任库遭特权修改或进程遭 Hook 时，不能保证业务明文始终不可获取。
