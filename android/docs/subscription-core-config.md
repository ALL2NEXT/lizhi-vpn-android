# 订阅 URI 到核心配置

订阅继续采用 `订阅内容 → 标准 URI → 所选核心配置` 的流程。2026-10-03 修复了以下转换问题：

- 单节点预检和测速配置缺少 `route.default_domain_resolver`。sing-box 1.14.1 会拒绝启动；版本号为 `unknown` 的本机测试此前仅输出警告。
- Clash 的 `ech-opts` 未保存在 URI 中，核心生成器也未处理 ECH。现在保留启用开关、配置和查询域名；sing-box 输出 PEM，Xray 输出 Base64 ECHConfigList。
- ECH 支持内联 Base64/PEM、DNS 自动查询，以及 `域名+DNS地址`。查询 ECH 的域名优先使用直连 DNS，避免代理握手依赖代理 DNS 的循环。
- Base64 查询参数中的 `+` 不再被 Android 当作空格；SS 密码中的 `@`、SOCKS/HTTP 密码中的 `:` 不再截断。
- Reality 未指定浏览器指纹时补充必需的 uTLS `chrome`。QUIC 协议不写入 TCP uTLS 配置。
- WireGuard 使用 `endpoints` 和 `peers` 格式；Hysteria2 数字跳跃间隔转换为秒数。
- 不支持的传输、无效 ECH、Reality 与 ECH 冲突，以及 AnyTLS `max_idle_session` 明确报错。

Clash `fingerprint` 是证书指纹，`client-fingerprint` 才是浏览器指纹。两者分别保存在 URI 中。当前固定的 sing-box 1.14.2 支持证书 PEM 和公钥 SHA-256，整张证书 SHA-256 固定明确提示使用 Xray；不会把它错误映射为公钥指纹。

更新 APK 后，需要刷新或重新导入带 ECH 的 YAML 订阅，以补回旧版本导入时丢失的字段。

## 验证

Android 的 `SingBoxGeneratedConfigTest` 使用 Robolectric 执行实际 Android URI/Base64 解析，并将预检、全局 VPN 和智能分流配置导出到忽略提交的 `app/build/singbox-config-fixtures`。设置 `CHIMERA_TEST_SUBSCRIPTION` 为本机订阅文件路径，可同时验证真实订阅；不要将节点凭据提交为测试资源。

```powershell
# 在 android 目录
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

# 在 android/native/singbox-runner 目录；测试会设置实际打包版本号
go test -count=1 -tags with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api -ldflags=-checklinkname=0 ./...

# 在 android/native/xray-runner 目录
go test -count=1 ./...
```

真实订阅当前的 141 个 SS 节点已逐一检查凭据转换，并通过本机内核的三类配置启动测试。额外用合成节点验证 ECH、Reality、VMess、Trojan、Hysteria2、AnyTLS、TUIC、WireGuard、SOCKS 和 HTTPS。Android JVM 测试共 122 项通过，包含 17 项 URI/配置转换测试；两个 ABI 的 Debug APK 已构建。当前未连接 Android 测试设备，手机 VPN 建立与节点实际连通性仍需实机测试。
