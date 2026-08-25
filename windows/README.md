# FireflyVPN Windows

Windows 客户端的独立 Compose Desktop 工程。UI、后台协议、节点解析和 sing-box 配置均以当前 Android 3.0.0 为兼容基线。

当前 Windows 双核心版为 3.0.3；安装包运行时显式包含后台客户端所需的 `java.net.http` 模块，智能连接仅测试当前节点列表中的订阅组或收藏组。

## 运行

进入 Windows 工程目录执行：

```powershell
cd windows
.\gradlew.bat run
```

首次构建会从根目录 `sing-box` 与 `xray-core` 中锁定的源码编译 Windows 双核心，耗时会比后续启动长。应用启动后自动注册本机设备身份、同步账户和订阅节点。

TUN 连接需要管理员权限。开发运行时请先打开“管理员 PowerShell”，再执行上述命令；普通权限仍可浏览节点、修改设置及执行 TCPing、URL Test 和带宽测试，但无法建立整机 TUN。

执行测试：

```powershell
.\gradlew.bat test
```

生成当前系统的安装包：

```powershell
.\gradlew.bat packageDistributionForCurrentOS
```

## sing-box 核心

开发版按以下顺序寻找 `sing-box.exe`：

1. 环境变量 `FIREFLY_SING_BOX` 指定的文件或目录；
2. 打包后的应用资源目录；
3. `windows/runtime/windows-x64/sing-box.exe`；
4. 当前用户的 `PATH`。

核心由 Windows 客户端生成与 Android 1.13.0-rc.3 兼容的配置，并在连接前执行 `sing-box check`。当前开发版直接启动 TUN 进程，因此连接时要求应用本身具有管理员权限；独立高权限 Service 仍是后续安装版的加固项。

## Xray 双核心

Windows 端与 Android 使用相同的进程隔离方案：sing-box 继续统一管理 TUN、系统代理、DNS、路由和流量统计；Xray 作为独立进程，为选择 Xray 的节点提供仅监听 `127.0.0.1` 的 SOCKS 桥接端口。

首次运行或尚未保存核心设置时，VLESS、VMess 默认使用 Xray；Trojan、Shadowsocks 默认使用 sing-box，但可以在“特色功能 → 协议核心”中切换。Hysteria2、AnyTLS、TUIC、Naive、WireGuard、SOCKS 和 HTTP 会完整显示，但固定由 sing-box 处理。

开发版按以下顺序寻找 `xray.exe`：

1. 环境变量 `FIREFLY_XRAY` 指定的文件或目录；
2. 打包后的应用资源目录；
3. `windows/runtime/windows-x64/xray.exe`；
4. 当前用户的 `PATH`。

执行 `./gradlew.bat buildXray` 可从锁定的官方 Xray-core 源码单独构建核心。运行和打包任务会自动依赖该构建。

## 当前里程碑

- [x] 独立 Compose Desktop 构建
- [x] 原主题和连接按钮资源复用
- [x] 官方 sing-box 源码锁定、构建、探测与 TUN 启动
- [x] 官方 Xray-core 源码锁定、独立进程桥接与按协议选择核心
- [x] 后台账户、订阅发现、访问控制和加密订阅接入
- [x] TCPing、可自定义/预设地址的 204 URL Test、5MB 带宽测试和智能连接
- [x] Android 基础设定、Hy2 直连带宽自适应、特色功能和账户操作迁移
- [x] Android 同款标题连续点击五次切换后台
- [ ] Windows Service 与 Named Pipe
- [x] TUN、全局/智能分流和应用内断线恢复
- [ ] Windows Service 常驻断线恢复与流量上报
- [ ] 系统托盘、开机启动和签名更新
