# Android 双核心架构

Android 端采用 HEV 统一 TUN 数据面 + sing-box/Xray 协议核心的架构：

```text
Android VpnService / TUN
        |
        v
HEV tun2socks
        |
        v
核心 loopback SOCKS5
        |
        +-- sing-box -> 服务器
        `-- Xray -> 服务器
```

## 为什么两个核心都采用独立 PIE 子进程

sing-box 与 Xray 都编译为 Android PIE，并以独立进程运行。这样 Kotlin 控制层
只负责配置、启动/停止和故障回收，两个 Go runtime 不会进入 Android 应用进程：

- HEV 是唯一的 TUN 所有者，不会发生双核心抢占 VPN fd；
- 任一核心崩溃都与 UI、VPN 控制层隔离，服务会检测退出并安全断开；
- 两个核心可在同一节点列表中同时使用；
- Xray 配置和 sing-box 配置在启动前分别由各自核心校验；
- 配置读取完成后会从应用私有目录删除，减少凭据落盘时间。

## 支持范围

“核心路由 -> 协议核心”目前可配置：

- VLESS
- VMess
- Trojan
- Hysteria2（含 salamander 混淆、端口跳跃和带宽参数）
- WireGuard
- Shadowsocks（不含 SIP003 插件节点）
- SOCKS5（SOCKS4/4a 仍由 sing-box 处理）
- HTTP/HTTPS

AnyTLS、TUIC、Naive 固定由 sing-box 处理。首次安装或尚未保存核心设置时，全部协议
默认使用 sing-box；用户后续选择会覆盖默认值。修改核心选择后，正在运行的 VPN
会自动重连。

Xray `v26.3.27` 已移除 `allowInsecure`。带自签名证书的 Hysteria2/HTTPS 节点如需
使用 Xray，应由服务端链接提供 `pcs` 或 `pinSHA256`（SHA-256 证书指纹）；否则
配置会在启动前被明确拒绝，而不会静默关闭证书校验。

## 构建

Xray-core 以 Git submodule 固定在官方稳定版 `v26.3.27`。Android Gradle 构建会
自动为 `armeabi-v7a` 和 `arm64-v8a` 生成 `libxray.so` 可执行 PIE，并随对应的
split APK 打包。

首次拉取仓库后执行：

```bash
git submodule update --init --recursive
```

构建环境需要 JDK 17、Go 1.26+ 和项目指定的 Android NDK。可单独
验证 Xray 构建：

```powershell
cd android
.\gradlew.bat :app:buildXrayCore
```

## 运行时保护

Xray 与应用共享 UID。VPN Builder 会始终把本应用排除在 TUN 捕获范围之外，防止
Xray 的上游连接再次进入 sing-box 并形成路由环。启用分应用白名单时，即使历史
设置中包含本应用包名，运行时也会主动移除。
