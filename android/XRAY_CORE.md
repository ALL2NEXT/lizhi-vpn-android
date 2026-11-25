# Android 双核心架构

Android 使用 HEV 处理 TUN 流量，由官方 sing-box 或 Xray 提供代理协议：

```text
Android VpnService / TUN
        |
        v
HEV tun2socks
        |
        v
核心 loopback SOCKS5
        |
        +-- 官方 sing-box -> 服务器
        `-- Xray -> 服务器
```

## 独立 PIE 子进程

sing-box 和 Xray 都编译为 Android PIE 可执行文件，在独立进程中运行。HEV 独占 Android TUN；核心只提供本地 SOCKS5 入站。核心进程崩溃时，应用服务会检测退出并回收会话。

VLESS、VMess 和 Cloudflare WARP MASQUE 默认使用 Xray，其他协议默认使用官方 sing-box。XHTTP 和 VLESS `encryption` 等 Xray 支持的扩展参数也会选择 Xray。AmneziaWG 参数、非 Cloudflare MASQUE profile，以及当前两个核心均不支持的 TLS 扩展会在连接前被拒绝。

Xray-core `v26.10.10` 支持 Cloudflare WARP MASQUE。应用将 `masque://` URI 转成 Xray outbound 配置，并从 URI 读取密钥、客户端地址、服务器、公钥、SNI 和传输选项。

Xray `v26.3.27` 已移除 `allowInsecure`。带自签名证书的 Hysteria2/HTTPS 节点如需使用 Xray，应由服务端链接提供 `pcs` 或 `pinSHA256`（SHA-256 证书指纹）；否则配置会在启动前被明确拒绝，而不会静默关闭证书校验。

## 构建

Android Gradle 构建为 `arm64-v8a` 生成 `libsingbox.so` 和 `libxray.so`，并随对应 split APK 打包。sing-box 与 Xray 源码分别由根目录的 Git submodule 固定版本。

首次拉取仓库后执行：

```bash
git submodule update --init --recursive
```

构建环境需要 JDK 17、Go 1.27+、Android SDK 和项目指定的 NDK。可单独构建 Xray：

```powershell
cd android
.\gradlew.bat :app:buildXrayCore
```

## 运行时保护

Xray 与应用共享 UID。VPN Builder 会将本应用排除在 TUN 捕获范围之外，防止 Xray 的上游连接再次进入 VPN 并形成路由环。
