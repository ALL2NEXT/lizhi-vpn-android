# 流萤加速器 (Firefly)
<p align="center">
  <img src="./images/firefly.jpg" width="100" alt="Logo">
</p>
<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-brightgreen" alt="Platform">
  <img src="https://img.shields.io/badge/Min%20SDK-24%20(Android%207.0)-blue" alt="Min SDK">
  <img src="https://img.shields.io/badge/Target%20SDK-35%20(Android%2015)-blue" alt="Target SDK">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple" alt="Language">
  <img src="https://img.shields.io/badge/Core-Sing--box-green" alt="Core">
  <img src="https://img.shields.io/badge/License-GPLv3-orange" alt="License">
</p>
<p align="center">
  流萤加速器是一款基于 sing-box 核心，支持多种代理协议和智能分流的 Android VPN 客户端。
</p>

<p align="center">
  <a href="#功能特性">功能特性</a> •
  <a href="#技术架构">技术架构</a> •
  <a href="#环境要求">环境要求</a> •
  <a href="#快速开始">快速开始</a> •
  <a href="#配置说明">配置说明</a> •
  <a href="#API 接口">API 接口</a> •
  <a href="#自定义">自定义</a> •
  <a href="#构建发布">构建发布</a> •
  <a href="#其他说明">其他说明</a>
</p>

---

**免责声明：** 本项目为本人开源作品，与米哈游 (HoYoverse) 无关。本项目不盈利、不接受捐赠。所有涉及的游戏角色名称及设计版权归米哈游所有。

**Disclaimers:** This project is my open-source creation and is not related to miHoYo (HoYoverse). This project is non-profit and not for sale. All game character names and design copyrights belong to miHoYo.

---

## 工程目录

```text
android/    # Android 客户端（独立 Gradle 工程）
windows/    # Windows 客户端（独立 Compose Desktop 工程）
backend/    # Cloudflare Worker 后台
```

仓库根目录仅保留三端共享的说明、许可证、图片和部署辅助文件。

---

## 功能特性

### 核心功能

- 🚀 **多协议支持**：VLESS、VMess、Trojan、Hysteria2、AnyTLS、TUIC、Naive、WireGuard、Shadowsocks、SOCKS4/5、HTTP/HTTPS 代理
- 🧭 **智能分流**：国内流量直连，国外流量代理，自动识别主流 CN 应用/CDN
- ⚡ **自动选择**：一键测速，自动选择最优节点
- 📦 **分应用代理**：精细控制哪些应用走代理或绕过 VPN
- 🌐 **绕过局域网**：局域网流量始终直连，不受 VPN 影响
- 🌍 **IPv6 路由**：支持 IPv6 网络访问，可选禁用/启用/优先/仅 IPv6 模式
- 🔄 **多订阅源**：先获取远程订阅列表，节点按后台线路名称分组展示
- ⭐ **收藏节点**：收藏节点与订阅节点独立存储，订阅刷新不会覆盖收藏内容
- ➕ **节点导入**：节点列表支持从剪切板导入和扫码导入，导入结果自动去重并写入收藏节点
- 🛡️ **核心配置校验**：收藏导入与 VPN 启动前会使用 sing-box `checkConfig` 校验节点配置，避免单个不兼容节点拖垮整组配置

### 测试与诊断

- 🔧 **三点工具菜单**：主界面右上角“三点”菜单提供中科大、南大、奈飞、Google 四个网页测速入口
- 🌐 **URL Test 测试**：通过启动临时无头 sing-box 实例进行 HTTP 握手延迟测试，无需连接 VPN 即可测试所有节点真实连通性
- 🧩 **测试模式系统**：内置 `日常模式`（URL Test 延迟优先）与 `下载模式`（下行带宽优先），支持自定义模式保存/删除
- 🤖 **智能连接**：依次执行 TCPing、204 URL Test 和带宽测速，按测试结果自动连接最佳节点
- 🌐 **网页测速入口**：支持中科大测速、南大测速、奈飞测速和 Google 测速，分别标注墙内/墙外线路
- 📡 **TCPing 测试**：直接 TCP 连接测试节点可达性和延迟
- 🗑️ **清理不合格节点**：一键隐藏超时/不可用/不达标节点（UI 过滤，不删除数据库数据）
- 🧾 **运行日志**：侧边栏可查看、刷新、分享、清空 APP 内部运行日志；日志仅记录脱敏后的关键 Info / Warn / Error，不导出节点链接和配置内容

### 界面与体验

- 🚩 **智能国旗**：自动识别节点名称中的国旗 Emoji（如 🇫🇮），优雅展示
- 🔔 **VPN 通知**：实时显示上传/下载速度、累计流量，支持断开/重置连接
- ⏳ **节流保护**：刷新订阅列表、检查更新等操作 5 秒内防重复触发
- 🔔 **公告系统**：支持远程推送公告通知
- ℹ️ **关于页面**：展示应用版本信息、开源协议、GitHub 仓库链接和免责声明
- ⚙️ **其他配置**：侧边栏可进入“其他配置”页面，集中管理 IPv6 路由、智能连接比例、超时时间、并发数、VPN MTU 等参数
- 💾 **记住上次选择节点**：默认开启，退出 APP 前选择的节点会在下次启动后恢复
- 🎨 **现代 UI**：基于 Jetpack Compose，Material Design 3 风格，页面使用 NavHost + NavController 路由管理
- 🔧 **开源可定制**：易于修改 API、品牌和配置

### 更新与安全

- 📦 **稳健更新**：
  - 应用内下载，支持断点续传
  - 自动检测下载失败，连续失败 3 次及以上会引导跳转官网下载
  - 原子化更新机制，杜绝安装包损坏
  - 智能权限引导，适配 Android 8.0+ 安装权限

---

## 技术架构

| 组件 | 技术 |
|------|------|
| **UI 框架** | Jetpack Compose + Material 3 |
| **页面路由** | NavHost + NavController (Jetpack Navigation Compose) |
| **架构模式** | MVVM (ViewModel + StateFlow) |
| **网络请求** | Retrofit2 + OkHttp3 |
| **本地存储** | Room Database (SQLCipher 加密) + DataStore |
| **二维码扫描** | CameraX + ML Kit Barcode Scanning |
| **运行日志** | 应用内专用 RuntimeLog（私有文件 + FileProvider 分享） |
| **VPN 核心** | [sing-box](https://github.com/SagerNet/sing-box) 与 Xray 的独立 Android PIE 运行器 |
| **并发处理** | Kotlin Coroutines |

---

## 环境要求

### 开发环境

| 工具 | 版本要求 |
|------|---------| 
| **Android Studio** | Koala 2024.1.1 或更高 |
| **Go** | 1.24.7 或更高 |
| **JDK** | 17 |
| **Kotlin** | 1.9.21 |
| **Gradle** | 8.7+ |
| **Android Gradle Plugin** | 8.6.1 |
| **NDK** | 28.0.13004108 |

### 运行环境

| 要求 | 说明 |
|------|------|
| **Android 版本** | Android 7.0 (API 24) 及以上 |
| **目标版本** | Android 15 (API 35) |

### 核心依赖

Android 不再依赖 `libbox.aar`。项目通过根目录下的 `core/sing-box` 子模块锁定 sing-box 源码版本，Gradle 构建时将 sing-box CLI 编译为独立的 Android PIE 运行器 `libsingbox.so`，与 `libxray.so` 一样通过 `ProcessBuilder` 启动，生成文件位于 `android/app/build/generated/singbox/`，不会提交到版本库。

首次获取源码后执行：

```bash
git submodule update --init --recursive
```

源码构建需要 Go、JDK 17、Android SDK 和 NDK 28.0.13004108。协议扩展或核心修改直接在根目录 `core/sing-box` 中进行，重新构建 Android 或 Windows 项目即可生效。

项目还依赖 [SQLCipher for Android](https://github.com/niceyun/sqlcipher-android)（`net.zetetic:sqlcipher-android:4.13.0`）用于本地数据库加密，Gradle 会自动下载，无需手动配置。

---

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/Iskongkongyo/Firefly.git
cd Firefly
```

### 2. 配置 API 地址

编辑 `android/app/src/main/java/xyz/a202132/app/AppConfig.kt`，修改为你的后端地址：

```kotlin
object AppConfig {

    // Cloudflare Worker 统一后台入口
    const val BACKEND_URL = "https://your-worker.workers.dev"
    // 官网地址
    // 官网、反馈联系方式、GitHub、请求超时均在 Worker 管理后台配置

    // 延迟测试 (TCPing & URL Test)
    const val TCPING_TEST_TIMEOUT = 1000L // 超时默认1秒
    const val URL_TEST_URL = UrlTestTargets.DEFAULT_URL // 用户可在基础设定中修改
    const val URL_TEST_TIMEOUT = 1000L // 超时默认1秒
    const val URL_TEST_RETRY_COUNT = 1 // URL Test 自动重试次数，仅对503/504或异常生效
    
    // 延迟测试并发数
    const val TCPING_CONCURRENCY = 16
    const val URL_TEST_CONCURRENCY = 10

    // VPN 核心参数
    const val VPN_MTU = 9000
    const val VPN_DNS_PRIMARY = "8.8.8.8"
    const val VPN_DNS_SECONDARY = "8.8.4.4"
    const val VPN_DNS_CHINA = "223.5.5.5"        // 国内 DNS（智能分流模式使用）
    val HTTP_USER_AGENT: String  // 运行时 getter，自动跟随版本号
        get() = "Firefly/${BuildConfig.VERSION_NAME}"

    // 通知
    const val NOTIFICATION_CHANNEL_ID = "vpn_service"
    const val NOTIFICATION_ID = 1

    // 带宽测速默认地址 (Cloudflare)
    const val SPEED_TEST_DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=5000000"
    const val SPEED_TEST_UPLOAD_URL = "https://speed.cloudflare.com/__up"
    const val AUTO_TEST_BANDWIDTH_DURATION_MS = 3000L          // 单次带宽测试时长
}
```

同时修改 `android/app/src/main/java/xyz/a202132/app/network/NetworkClient.kt` 中的 baseUrl：

```kotlin
private val retrofit = Retrofit.Builder()
    .baseUrl("https://your-server.com/")  // 修改为你的域名
    // ...
```

同时修改 `android/app/src/main/res/xml/network_security_config.xml` ：

```xml
<domain includeSubdomains="true">example.com</domain> <!-- 修改为你的域名 -->
```

### 3. 构建运行

```bash
# 使用 Gradle 构建
cd android
./gradlew assembleDebug

# 或在 Android Studio 中直接运行
```

---

## 配置说明

### 项目结构

```
android/app/src/main/java/xyz/a202132/app/
├── AppConfig.kt                  # 全局配置常量（API地址、测试参数等）
├── MainActivity.kt               # 主 Activity
├── VpnApplication.kt             # Application 类
├── data/
│   ├── local/                    # 本地数据库
│   │   ├── AppDatabase.kt       # Room 数据库定义
│   │   └── NodeDao.kt           # 节点数据访问对象
│   ├── model/                    # 数据模型
│   │   ├── ApiModels.kt         # API 响应模型
│   │   ├── Node.kt              # 节点数据模型
│   │   ├── NodeType.kt          # 代理协议类型枚举
│   │   ├── PerAppProxyMode.kt   # 分应用代理模式枚举
│   │   └── IPv6RoutingMode.kt   # IPv6 路由模式枚举
│   └── repository/               # 数据仓库
│       └── SettingsRepository.kt # 设置存储（含分应用代理等）
├── network/
│   ├── ApiService.kt             # Retrofit API 接口定义
│   ├── DownloadManager.kt        # 应用内下载管理器（断点续传）
│   ├── LatencyTester.kt          # 节点延迟测试（TCPing + URL Test）
│   ├── NetworkClient.kt          # 网络客户端配置
│   ├── SpeedTestService.kt       # Cloudflare 网速测试服务
│   ├── SubscriptionParser.kt     # 订阅链接解析器
│   ├── NodeTestProxyManager.kt   # 节点带宽测试临时代理管理器
│   └── UrlTestManager.kt         # 双核心 URL Test 管理器
├── service/
│   ├── BoxVpnService.kt          # VPN 服务（sing-box/Xray 核心）
│   └── ServiceManager.kt         # VPN 服务管理器
├── ui/
│   ├── components/               # 可复用 UI 组件
│   │   ├── AppScreenScaffold.kt # 通用页面脚手架（统一 TopAppBar + 返回键）
│   │   ├── ConnectButton.kt     # 连接按钮
│   │   ├── DrawerContent.kt     # 侧边栏内容
│   │   ├── NodeListDialog.kt    # 节点列表页面（文件名沿用旧命名，主入口已改为 NodeListScreen）
│   │   ├── NodeSelector.kt      # 节点选择器
│   │   └── TrafficStatsRow.kt   # 流量统计展示
│   ├── dialogs/                  # 对话框
│   │   ├── AboutDialog.kt       # 关于页面弹窗
│   │   ├── Dialogs.kt           # 通用对话框（公告、更新等）
│   │   └── UserAgreementDialog.kt   # 用户协议弹窗
│   ├── navigation/               # 页面路由
│   │   └── AppRoute.kt          # 路由常量定义（NavHost 目的地）
│   ├── screens/                  # 页面（通过 NavHost + NavController 管理）
│   │   ├── MainScreen.kt        # 主界面
│   │   ├── RuntimeLogScreen.kt  # 运行日志页面
│   │   ├── OtherConfigScreen.kt # 基础设定页面（网络、测速地址与 VPN）
│   │   ├── PerAppProxyScreen.kt # 分应用代理设置界面
│   │   └── QrScannerScreen.kt   # 二维码扫描导入页面
│   └── theme/                    # 主题配置
│       ├── Color.kt             # 颜色定义
│       ├── Theme.kt             # 主题配置
│       └── Type.kt              # 字体排版
├── util/
│   ├── DatabasePassphraseManager.kt # SQLCipher 数据库密钥管理（Android Keystore）
│   ├── NetworkUtils.kt          # 网络状态检测工具
│   ├── RuleManager.kt           # 智能分流规则管理
│   ├── RuntimeLog.kt            # 应用内运行日志（脱敏、滚动保留、导出）
│   ├── SignatureVerifier.kt     # APK 签名验证（JNI 桥接）
│   └── SingBoxConfigGenerator.kt # sing-box 配置生成器
└── viewmodel/
    ├── GlobalTestExecution.kt    # 全局测试互斥锁
    ├── MainViewModel.kt          # 主界面 ViewModel
    └── PerAppProxyViewModel.kt   # 分应用代理 ViewModel
```

### 资源文件结构

```
core/sing-box                       # sing-box 源码子模块（Android/Windows 构建输入）
core/xray-core                      # Xray-core 源码子模块（Android/Windows 构建输入）
android/app/build/generated/      # Gradle 生成的 Android 核心运行器（不提交）
android/app/src/main/
├── assets/
│   └── rule-sets/                # 智能分流规则集
│       ├── geoip-cn.srs
│       └── geosite-cn.srs
├── cpp/
│   ├── CMakeLists.txt            # NDK 构建配置
│   └── native-lib.cpp            # Native 层签名校验
├── res/
│   ├── drawable/                 # 图标和图片资源
│   ├── mipmap-*/                 # 应用图标
│   ├── values/
│   │   ├── colors.xml            # 颜色资源
│   │   ├── strings.xml           # 字符串资源
│   │   └── themes.xml            # 主题定义
│   └── xml/
│       ├── file_paths.xml        # FileProvider 路径配置
│       └── network_security_config.xml  # 网络安全配置
└── AndroidManifest.xml           # 应用清单
```

---

## 高级功能

### 节点列表、收藏与导入

节点列表会为每条订阅线路生成独立视图，名称直接使用 Worker 下发的线路名称；另有独立的 `收藏节点` 视图。收藏节点不会在订阅刷新后被自动覆盖。

**功能特点**：

- ⭐ 节点卡片延迟标签前的星标可用于收藏或取消收藏
- 📌 APP 会记录当前订阅线路以及是否停留在收藏节点
- ➕ 节点列表搜索按钮左侧提供导入入口，支持从剪切板导入和扫描二维码
- 🔁 导入到收藏节点前会自动去重，成功后提示实际导入数量
- 🛡️ 导入收藏节点前会生成单节点 sing-box 测试配置并执行 `sing-box --check`，不兼容核心的节点不会写入收藏
- 🚦 VPN 启动时只会使用当前节点来源生成配置，并在启动前跳过核心不支持的非选中节点；如果所选节点本身不被核心支持，会直接提示错误

> 💡 收藏节点适合长期保留少量手动维护的节点；切换订阅线路后，列表和测速只针对当前线路。

---

### 工具箱按钮

主界面右上角“三点”菜单提供以下功能：

| 功能 | 说明 |
|------|------|
| **TCPing** | 直接 TCP 连接测试节点可达性和延迟 |
| **URL Test** | 通过 ClashAPI 发起 HTTP 握手延迟测试，结果更准确 (默认/推荐) |
| **网页测速入口** | 打开中科大、南大、奈飞或 Google 的测速页面 |
| **隐藏不合格节点** | 一键隐藏超时/不可用/不达标节点（UI 过滤） |

**URL Test 工作原理**：

- **VPN 已连接时**：直接使用现有 sing-box 实例的 ClashAPI (端口 9090)
- **VPN 未连接时**：自动启动临时无头 sing-box 实例 (端口 19090)，无需 VPN 权限即可测试

> 💡 无头实例不使用 TUN，仅创建本地 HTTP 代理和 ClashAPI，通过操作系统默认路由直接连接代理节点。

---

### 关于页面

显示应用版本信息、开源协议声明、项目链接等。

**配置位置**：  
- 版本号：`android/app/build.gradle.kts` → `versionName` / `versionCode`
- GitHub 链接：Worker 管理后台 → 应用基础设置（留空则隐藏相关按钮）
- 反馈链接：Worker 管理后台 → 应用基础设置

**设置位置**：侧边栏 → 关于

---

### 分应用代理

分应用代理允许用户精细控制哪些应用走代理或绕过 VPN。

**功能特点**：
- 🟢 **代理模式**：只有选中的应用走代理，其他应用直连
- 🔴 **绕过模式**：选中的应用直连，其他应用走代理
- 🔍 **搜索过滤**：支持按应用名称或包名搜索
- 📂 **系统应用筛选**：可选择显示/隐藏系统应用

**设置位置**：侧边栏 → 代理作用域

---

### 其他配置

“基础设定”页面集中管理网络开关、204 测速地址、带宽测速地址和 VPN 参数；智能连接比例、测试时间与并发数在“特色功能 → 智能连接”中按“类型 / 比例 / 时间 / 并发”表格统一管理，常规订阅与聚合订阅使用两套独立保存的参数。Hysteria2 带宽也位于“特色功能”。IPv6 路由和预设地址点击后立即生效，自定义地址校验并保存后生效。

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| IPv6 路由 | 禁用 | 可选仅 / 优先 / 启用 / 禁用，位于其他配置顶部 |
| 记住上次选择的节点 | 开启 | 退出 APP 前选择的节点会在下次启动后恢复 |
| 204 测速地址 | Google generate_204 | 可输入自定义 HTTP(S) 地址，或从 Cloudflare、Google、Google Static 三个预设中选择 |
| 带宽测速地址 | Cloudflare __down?bytes=5000000 | 可输入完整的自定义 HTTP(S) 地址，或选择 Cloudflare 5 MB、25 MB 预设，供智能连接带宽测速使用 |
| TCPing 节点比例 | 100% | 智能连接第一阶段候选比例，默认测试全部节点 |
| URL Test 候选比例 | 50% | 智能连接第二阶段从 TCPing 结果中选取的比例 |
| 带宽测速候选比例 | 50% | 智能连接第三阶段从 URL Test 结果中选取的比例 |
| Hysteria 最大带宽 (Up/Dw) | 1000/1000 Mbps | 仅对 Hysteria2 节点生效；输入 0 表示不设置，修改后下次连接生效 |
| TCPing 超时 | 1000ms | 最低 500ms |
| URL Test 超时 | 1000ms | 最低 500ms |
| 带宽测试时长 | 3000ms | 在固定时长内持续统计下载或上传流量，最低 1000ms |
| TCPing 并发数 | 16 | 范围 1~128 |
| URL Test 并发数 | 10 | 范围 1~128 |
| 带宽测试并发数 | 1 | 范围 1~3，默认保持串行；提高并发可加快粗筛但会增加测速竞争 |
| 聚合订阅判定阈值 | 100 | 节点数超过阈值且聚合订阅优化开启时，使用独立的聚合订阅测试参数 |
| 聚合订阅 TCPing / URL Test / 带宽并发数 | 256 / 32 / 1 | 与常规订阅参数独立保存；带宽并发范围同样为 1~3 |
| VPN MTU | 9000 | 范围 576~9000，修改后需断开重连 |

**设置位置**：侧边栏 → 基础设定 / 特色功能 → 智能连接

---

### 运行日志

运行日志用于用户反馈问题时导出必要诊断信息，入口位于侧边栏。

**功能特点**：

- 顶部提供刷新、分享、清空三个图标按钮
- 分享时生成 `Firefly-runtime-日期-hash.log`，通过 `FileProvider` 分享
- 日志来源为 APP 内部专用 `RuntimeLog`，不直接导出 Android logcat
- 默认最多保留最新 1000 条日志；单条消息会截断，旧日志会自动滚动清理
- 仅记录脱敏后的关键 Info / Warn / Error，例如 APP 启动、VPN 启停、核心兼容校验、自动测试摘要、导入结果、更新/公告请求失败、sing-box warn/error 等
- 不输出节点链接、生成的 sing-box …1542 tokens truncated…(sha256_bytes) == 32

kXor = 0x5D  # XOR 常量，可自定义
partB = list(os.urandom(32))  # 随机生成 partB

# 计算 partA: 运行时 expected[i] = partA[i] ^ partB[i] ^ kXor
partA = [sha256_bytes[i] ^ partB[i] ^ kXor for i in range(32)]

def fmt(arr, size=8):
    lines = []
    for i in range(0, len(arr), size):
        chunk = ", ".join(f"0x{v:02X}" for v in arr[i:i+size])
        lines.append(f"            {chunk}" + ("," if i + size < len(arr) else ""))
    return "\n".join(lines)

print(f"static const uint8_t partA[32] = {{\n{fmt(partA)}\n}};")
print(f"static const uint8_t partB[32] = {{\n{fmt(partB)}\n}};")
print(f"constexpr uint8_t kXor = 0x{kXor:02X};")
```

4. 将输出的 `partA`、`partB`、`kXor` 替换到 `native-lib.cpp` 中 `isExpectedSignature` 函数对应位置

5. 重新 Clean → Rebuild 项目

> ⚠️ **注意**：每次更换签名证书后，都需要更新此值，否则 APP 将无法启动。


---

### 5. Release 日志屏蔽

Release 版本默认移除所有 `android.util.Log` 调用（包括 `Log.d`、`Log.i`、`Log.w`、`Log.e`），通过 R8/ProGuard 在编译时优化。

**优点**：
- 减少 APK 体积
- 防止日志泄露敏感信息
- 提升运行性能

**配置位置**：`app/proguard-rules.pro`

```proguard
# 移除所有日志调用
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int i(...);
    public static int v(...);
    public static int w(...);
    public static int e(...);
}
```

#### 如何保留部分日志

如果需要在 Release 版本中保留部分日志（如用于崩溃分析），可以注释掉对应级别：

```proguard
# 保留 Warning 和 Error 级别日志
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int i(...);
    public static int v(...);
    # public static int w(...);  # 保留
    # public static int e(...);  # 保留
}
```

修改后需重新 Build Release 版本。

---

### 6. 网络安全配置

`network_security_config.xml` 控制应用的 HTTP 明文流量策略：

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">your-domain.com</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>  <!-- URL Test ClashAPI 本地通信 -->
    </domain-config>
</network-security-config>
```

> ⚠️ `127.0.0.1` 的明文放行是 URL Test 功能必需的（ClashAPI 使用 HTTP 协议通信），请勿移除。

---

### 7. 本地数据库加密 (SQLCipher)

本地节点数据库使用 [SQLCipher for Android](https://github.com/niceyun/sqlcipher-android) 进行加密存储，防止 Root 环境下节点数据被直接读取。

**工作原理**：

1. 首次启动时，`DatabasePassphraseManager` 生成随机 256-bit 数据库密钥
2. 密钥通过 Android Keystore 的 AES-GCM 加密后存储在 SharedPreferences 中
3. Room 通过 `SupportOpenHelperFactory` 使用该密钥打开加密数据库
4. 旧版明文数据库会被自动检测并删除重建（通过读取文件头判断是否为 SQLite 明文格式）

**核心文件**：
- `DatabasePassphraseManager.kt` — 密钥生成、加密存储、解密读取
- `AppDatabase.kt` — 加密数据库初始化 + 旧数据库迁移

> 💡 密钥由 Android Keystore 硬件保护，即使设备被 Root，攻击者也无法直接获取原始密钥。

---

## Cloudflare Worker 后端

`backend/serverless.js` 是可直接部署到 Cloudflare Workers 的单文件后台，后端说明见 [`backend/README.md`](backend/README.md)。

部署步骤：

1. 在 Cloudflare 创建一个 KV Namespace，并在 Worker 设置中绑定变量名 `CONFIG`。
2. 创建 D1 数据库，并在 Worker 设置中绑定变量名 `USERS_DB`。
3. 创建 Worker Secret `ADMIN_TOKEN`，不要把它写入 App 或提交到 Git。
4. 将 `backend/serverless.js` 粘贴并部署，打开 `https://你的域名/3e9a0eed-6a29-4d16-91b1-3adae4bd88e6`。
5. 打开管理后台，用表单添加订阅线路、网站、反馈联系方式、GitHub、公告和版本更新；不需要手写 JSON。
6. 将 `AppConfig.kt` 中唯一的后端配置改成 Worker 地址：`AppConfig.BACKEND_URL`。

App 使用的唯一公开接口是 `GET /api/client`：

```json
{
  "subscriptions": [
    {"id": "1", "name": "线路1", "enabled": true, "encrypted": false, "url": "https://game.11145149.xyz/"}
  ],
  "settings": {
    "websiteUrl": "https://example.com",
    "feedbackEmail": "support@example.com",
    "feedbackUrl": "https://github.com/example/issues",
    "githubUrl": "https://github.com/example/project",
    "nodeRequestTimeoutMs": 25000
  },
  "notice": null,
  "appUpdate": null
}
```


管理后台的每条线路也支持直接粘贴纯文本节点（每行一个）。填写纯文本后，该内容会随后台状态保存在 `CONFIG` KV 中，单条线路最多 2 MiB；Worker 会忽略该线路填写的外部 URL，并在 `/api/client` 中自动下发自身的 `/api/subscription/<id>` 地址及 `encrypted: true`。App 请求这个地址时，Worker 才按照当前请求 IP 动态编码返回，公开的 `/api/client` 不包含节点明文。清空纯文本后，线路恢复使用外部订阅地址和手动加密标记。

后台接口需要 `Authorization: Bearer ADMIN_TOKEN`：`GET /<ADMIN_UUID>/api/state` 读取，`PUT /<ADMIN_UUID>/api/state` 保存订阅名称、URL 和其他后台配置。线路序号由 Worker 自动生成，详细订阅内容由 APP 直接获取。

这样 App 只内置一个 Worker 地址；更换线路、公告、更新信息和上游订阅地址都在 Worker 管理后台完成。

## API 接口（历史格式说明，当前不使用）

> 以下旧版接口文档仅供迁移旧服务参考。当前 App 不会请求 `/api/subscriptions`、`/api/update` 或 `/api/notice`，统一使用上面的 Cloudflare Worker `/api/client`。

### 1. 订阅列表接口

**旧端点**: `GET /api/subscriptions`（已废弃）

**响应格式**: JSON。该接口只返回订阅线路，不直接返回节点。

```json
{
    "subscriptions": [
        {
            "id": "line1",
            "name": "线路1",
            "url": "https://your-server.com/api/line1",
            "enabled": true,
            "encrypted": false
        }
    ]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `subscriptions` | Array | ✅ | 订阅线路列表 |
| `subscriptions[].id` | String | ❌ | 线路唯一 ID；缺省时由 URL 计算 |
| `subscriptions[].name` | String | ❌ | 线路显示名称，缺省时使用“线路N” |
| `subscriptions[].url` | String | ✅ | 实际节点订阅地址 |
| `subscriptions[].enabled` | Boolean / Int | ❌ | 是否启用该线路，默认启用 |
| `subscriptions[].encrypted` | Boolean / Int | ❌ | 是否按 Native 私有格式解码，默认关闭 |

APP 会先过滤当前账户等级不足的线路，再并发获取其余启用线路，并按 `id` 保持每条线路独立；节点列表按 `name` 分组展示，切换线路后测速只作用于当前线路。`subscriptions` 也兼容 `sources`、`list`、`data` 字段名。

### 2. 节点订阅内容接口

每个 `subscriptions[].url` 返回节点链接列表，每行一个。响应可以是明文或 Base64 编码内容。

当线路的 `encrypted` 为 `true` 时，订阅接口须遵循以下约定：

1. 将节点正文 UTF-8 字节按客户端 Native 中的私有 Base64 表编码。
2. 取本次请求者 IP 的最后一个 IPv4 段（IPv6 取最后一个十六进制段），对私有表中的字符向前循环位移 `段值 % 64` 位。例如 `1.2.3.4` 位移 4 位；`=` 填充不位移。
3. 在同一响应中返回 `X-Firefly-Client-IP: <本次用于位移的请求者 IP>`。客户端不会自行猜测公网 IP，以避免 VPN、代理和 NAT 导致解码不一致。

客户端的自定义表、字符位移和双向变换均位于 Native 层，Kotlin 只负责读取标记和传递响应正文/IP；Worker 内置与 Native 一致的服务端编码实现。对于外部订阅地址，Worker 只保存及下发 `encrypted` 标记，不会改写外部响应；对于后台直接导入的纯文本节点，Worker 会作为订阅接口自动执行上述编码响应。

**支持的协议**:
- `vless://` - VLESS
- `vmess://` - VMess 
- `trojan://` - Trojan
- `hysteria2://` 或 `hy2://` - Hysteria2
- `anytls://` - AnyTLS
- `tuic://` - TUIC
- `naive://` 或 `naive+https://` - Naive
- `wireguard://` - WireGuard
- `ss://` - Shadowsocks
- `socks://` 或 `socks5://` - SOCKS5
- `socks4://` - SOCKS4
- `http://` - HTTP 代理
- `https://` - HTTPS 代理 (自动启用 TLS)

**示例响应** (Base64 解码后):
```
vless://uuid@server:443?security=reality&type=tcp&sni=example.com#节点名称
vmess://eyJ2IjoiMiIsInBzIjoi5ZCN56ewIiwiYWRkIjoic2VydmVyLmNvbSIsInBvcnQiOiI0NDMifQ==
trojan://password@server:443?sni=example.com#Trojan节点
ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@server:8388#SS节点
```

**当前配置位置**: `AppConfig.kt` → `BACKEND_URL`

---

### 3. 版本更新接口

**旧端点**: `GET /api/update`（已废弃）

**响应格式**: JSON

```json
{
    "version": "1.1.0",
    "versionCode": 2,
    "is_force":0,
    "downloadUrl": "https://your-server.com/download/app-v1.1.0.apk",
    "changelog": "1. 新增智能分流功能\n2. 修复已知问题"
}
```

**字段说明**:
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `version` | String | ❌ | 版本号（显示用） |
| `versionCode` | Int | ✅ | 版本代码（用于比较） |
| `is_force` | Int | ❌ | 是否强制更新（1为强制更新） |
| `downloadUrl` | String | ❌ | APK 下载地址 |
| `changelog` | String | ❌ | 更新日志 |

**当前配置位置**: Worker 管理后台的 `appUpdate`

---

### 4. 公告通知接口

**旧端点**: `GET /api/notice`（已废弃）

**响应格式**: JSON

```json
{
    "hasNotice": true,
    "title": "系统公告",
    "content": "服务器将于今晚 22:00 进行维护，届时可能无法连接。",
    "noticeId": "notice_20240117",
    "showOnce": true,
}
```

**字段说明**:
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `hasNotice` | Boolean | ✅ | 是否有公告 |
| `title` | String | ❌ | 公告标题 |
| `content` | String | ❌ | 公告内容，支持常见 HTML 标签（如 h1-h6、p、b、a、br、img 等），纯文本会自动按换行显示 |
| `noticeId` | String | ❌ | 公告唯一ID（用于去重） |
| `showOnce` | Boolean | ❌ | 是否只显示一次（默认 `true`） |

---

## 自定义

### 修改应用主题颜色

**文件**: `android/app/src/main/java/xyz/a202132/app/ui/theme/Color.kt`

默认皮肤在 Android 12 及以上启用莫奈动态取色；系统不支持或皮肤关闭莫奈取色时，使用 `Color.kt` 中的蓝灰色备用方案：

```kotlin
val Primary = Color(0xFF37474F)          // 蓝灰主色
val PrimaryVariant = Color(0xFF263238)   // 深蓝灰
val Secondary = Color(0xFF607D8B)        // 蓝灰辅助色
// ...
```

---

### 修改应用名称

**文件**: `android/app/src/main/res/values/strings.xml`

```xml
<string name="app_name">你的应用名称</string>
<string name="vpn_notification_title">你的应用名称运行中</string>
```

**文件**: `app/ui/screens/MainScreen.kt`

```
text = "流萤加速器"; 替换成你的应用名称
```

---

### 修改 VPN 连接按钮

当前连接按钮使用三张自定义图片表示不同状态：

| 图片文件 | 状态 | 说明 |
|---------|------|------|
| `btn_disconnected.png` | 未连接 | 默认待机状态 |
| `btn_connecting.png` | 连接中/断开中 | 带脉冲动画 |
| `btn_connected.png` | 已连接 | VPN 已开启 |

**图片位置**: `android/app/src/main/res/drawable/`

#### 替换按钮图片

将你的三张图片重命名为上述文件名，替换到 `drawable` 目录即可。

> 💡 推荐使用 **透明背景的 PNG 图片**，尺寸建议 512×512 像素以上以保证清晰度。

---

### 修改应用图标

**图标文件位置**:
```
android/app/src/main/res/
├── drawable/
│   ├── ic_launcher_background.xml  # 图标背景
│   └── ic_launcher_foreground.xml  # 图标前景
├── mipmap-anydpi-v26/
│   ├── ic_launcher.xml             # 自适应图标配置
│   └── ic_launcher_round.xml       # 圆形图标配置
└── mipmap-*/                        # 各分辨率位图（可选）
```

**推荐方式**: 使用 Android Studio 的 **Image Asset Studio**
1. 右键 `res` → New → Image Asset
2. 选择 Launcher Icons
3. 配置前景/背景图像
4. 自动生成所有尺寸

---

### 修改应用包名

需要修改以下位置：

1. **`android/app/build.gradle.kts`**:
```kotlin
android {
    namespace = "com.your.package"
    defaultConfig {
        applicationId = "com.your.package"
    }
}
```

2. **`AndroidManifest.xml`**: 确保 package 声明正确

3. **源代码目录**: 重构 `android/app/src/main/java/xyz/a202132/app/` 为新包名路径

4. **所有 Kotlin 文件**: 更新 `package` 声明

---

### 修改应用版本号

**文件**:**`android/app/build.gradle.kts`**:

```kotlin
versionCode = 15
versionName = "1.14.0"
```

版本号规范建议：
versionCode — 整数，每次发布必须递增，用于 Google Play 和系统判断新旧版本
versionName — 字符串，格式通常为 主版本.次版本.修订号（如 1.1.0）

---

### 修改主题颜色

**文件**: `android/app/src/main/res/values/themes.xml`

```xml
<style name="Theme.Firefly" parent="android:Theme.Material.Light.NoActionBar">
    <item name="android:colorPrimary">@color/your_primary</item>
    <item name="android:colorAccent">@color/your_accent</item>
</style>
```

或在 Compose 主题文件中修改 Material 3 颜色方案。

------

## 构建发布

### Debug 构建

Debug 模式已配置使用 Release 签名（防止 Native 签名验证失败）：

```bash
cd android
./gradlew assembleDebug
```

输出: `android/app/build/outputs/apk/debug/app-debug.apk`

> ⚠️ **注意**: 由于 Native 层有签名校验，Debug 和 Release 构建均需使用相同的签名密钥。`build.gradle.kts` 中已配置 `debug { signingConfig = signingConfigs.getByName("release") }`。

### Release 构建

> ⚠️ **注意**: `keystore.properties` 和 `*.jks` 签名文件已被 `.gitignore` 忽略，构建 Release 版本需要你配置自己的签名。

1. **准备签名文件**:
   生成一个新的 `.jks` 签名文件（或使用现有的），放在 `android/` 工程目录。

2. **创建配置文件**:
   在 `android/` 工程目录创建 `keystore.properties` 文件：

```properties
keyAlias=你的KeyAlias
keyPassword=你的KeyPassword
storeFile=你的签名文件.jks
storePassword=你的StorePassword
```

3. **执行构建**:
```bash
cd android
./gradlew assembleRelease
# Windows PowerShell:
.\gradlew.bat assembleRelease
```

**备选方法 (IDE 界面操作)**:
1. 菜单栏点击 **Build** -> **Generate Signed Bundle / APK**
2. 选择 **APK** -> **Next**
3. 选择密钥库并输入密码
4. 选择 **release** -> **Create**

输出: `android/app/build/outputs/apk/release/app-release.apk`

### 16 KB 页面对齐

自 2025 年 11 月起，Google Play 要求所有面向 Android 15+ 的应用支持 16 KB 页面大小。项目已在 `CMakeLists.txt` 中配置对齐：

```cmake
target_link_options(native-lib PRIVATE "-Wl,-z,max-page-size=16384")
```

> 💡 如果使用第三方 `.so` 库（如 `libsingbox.so`），也需确保其支持 16 KB 对齐，否则需更新上游库版本。

---

## 其他说明

### 智能分流实现

当前智能分流已基于 `rule_set` + `.srs` 文件：

- `SingBoxConfigGenerator` 在 SMART 模式引用 `geosite-cn` / `geoip-cn`
- `route.rule_set` 使用本地 `geosite-cn.srs` / `geoip-cn.srs`
- APP 启动后后台更新规则集：`VpnApplication` → `RuleManager.updateRuleSets()`
- VPN 启动/重启前确保规则集存在：`BoxVpnService` → `RuleManager.ensureRuleSets()`
- `ensureRuleSets()` 缺失时会从 assets 兜底拷贝

---

### 节点来源与核心配置校验

- 订阅节点与收藏节点按 `NodeSource` 独立存储，收藏节点不会被订阅刷新覆盖
- 节点列表当前视图由 `nodeListCategory` 记录，三点菜单测试和 VPN 启动都会按当前来源取节点
- 收藏导入时会先做解析、去重和 sing-box 单节点配置校验；不兼容核心的节点会被跳过
- VPN 启动/重启前会再次按当前来源过滤核心不支持的节点，并对最终完整配置执行对应核心的 `--check`
- 若当前选中的节点不被核心支持，APP 会提示错误并停止启动，不会自动切换到其他节点

---

### 测试与请求节点互斥

项目已实现"全局测试互斥 + 拉节点状态互斥"：

- 拉节点中禁止关键测试启动
- 测试进行中禁止拉节点
- 统一提示文案已细化为阶段型提示（如：TCPing/URL Test/解锁/测试择优进行中，请稍后；请求节点中，请稍后）

---

### 请求容错与自动重试

订阅列表、线路节点、公告通知、版本更新四类网络请求均具备统一的容错策略：

- 第一次请求失败/超时 → 自动重试一次（Toast 提示"请求失败，已自动重试..."）
- 第二次仍失败：
  - **订阅列表/线路请求**→ Toast 显示错误信息；已有缓存仍可继续使用
  - **公告/更新请求** → Toast进行提示，不影响 APP 使用

**URL Test 自动重试**：仅对 HTTP 503 / 504 或请求异常自动重试 1 次（`URL_TEST_RETRY_COUNT`），200 但 delay 非法不会重试。

超时时间统一在 `AppConfig.kt` 配置：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `URL_TEST_RETRY_COUNT` | 1 | URL Test 重试次数（仅 503/504/异常） |

---

### 稳定性与下载行为

- 请求节点等待超时路径已改为可恢复处理（超时提示 + 自动重试），避免因等待阶段超时直接崩溃
- `DownloadManager` 对 HTTP 416 采用"非递归一次自动恢复策略"：
  - 先清理临时文件并全量重试一次
  - 仍失败再提示用户重试
  - 避免递归导致潜在栈风险

---

### 安全说明（务必阅读）

- 订阅解析日志已减少敏感信息输出，不再记录完整节点链接原文
- 运行日志使用 APP 内部专用日志仓库，写入前会脱敏 URL、代理链接、IP、域名、UUID、密码、token、key 等敏感片段
- 分享的运行日志文件生成在缓存目录，仅用于用户主动分享问题诊断信息
- Native 层密钥与签名目标值已做运行时重组，降低"直接明文检索"命中概率
- 但这类客户端静态保护仅能提升逆向成本，不能替代真正的密钥托管与服务端校验策略

---

## 开源协议

本项目基于 [GNU General Public License v3.0](LICENSE) 开源，与核心依赖 sing-box 的协议保持一致。

**依赖项目**:

- [sing-box](https://github.com/SagerNet/sing-box) - GPLv3

---

## 致谢

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box) - VPN 核心
- [JetBrains/Kotlin](https://github.com/JetBrains/kotlin) - Kotlin 语言
- [Google/Jetpack Compose](https://developer.android.com/jetpack/compose) - UI 框架
