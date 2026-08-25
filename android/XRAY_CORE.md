# Android 双核心架构

Android 端采用 sing-box 主核心 + Xray 协议核心的串联架构：

```text
Android VpnService / TUN
        |
        v
sing-box（DNS、路由、分应用、流量统计、节点选择）
        |
        +-- sing-box 节点：直接连接服务器
        |
        `-- Xray 节点：127.0.0.1 SOCKS bridge -> Xray -> 服务器
```

## 为什么不加载第二个 GoMobile AAR

`libbox.aar` 已包含 GoMobile 的 Java 类、JNI 桥和 Go runtime。再加载一个
`libv2ray.aar` 会在同一 Android 进程中引入第二套同名运行时，容易出现类、JNI
符号及生命周期冲突。因此 Xray-core 被编译为 Android PIE，并以独立进程运行：

- sing-box 是唯一的 TUN 所有者，不会发生双核心抢占 VPN fd；
- Xray 崩溃与 libbox 隔离，服务会检测退出并安全断开；
- 两个核心可在同一节点列表中同时使用；
- Xray 配置和 sing-box 配置在启动前分别由各自核心校验；
- Xray 配置读取完成后会从应用私有目录删除，减少凭据落盘时间。

## 支持范围

“特色功能 -> 协议核心”目前可配置：

- VLESS
- VMess
- Trojan
- Shadowsocks（不含 SIP003 插件节点）

其余协议固定由 sing-box 处理。首次安装或尚未保存核心设置时，VLESS、VMess
默认使用 Xray，其他协议默认使用 sing-box；用户后续选择会覆盖默认值。修改核心
选择后，正在运行的 VPN 会自动重连。

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
