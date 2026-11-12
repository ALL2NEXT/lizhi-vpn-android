# Android 与后端的 AES-GCM 通信

Android 的账号、配置、通知、托管订阅、代理应用名单及上报接口通过 `POST /api/transport` 传输。请求方法、路径、查询参数、Content-Type、正文和时间戳一起加密；服务端解密后交给原有路由。响应状态码、响应头和正文也一起加密，校验成功后恢复成原有 Retrofit/OkHttp 响应。

第三方订阅、图标、规则下载和测速仍使用各自的 HTTP 协议。应用和代理节点之间的 VPN 协议由原有核心负责。

## 首次配置服务端

在仓库的 `backend` 目录执行：

```powershell
node scripts/generate-transport-key.mjs
```

脚本生成 RSA-3072 密钥，将 Base64 PKCS#8 私钥写入 `backend/.secrets/client-transport.env`，不会覆盖现有文件或将私钥打印到终端。此目录和 Wrangler 的 `.dev.vars` 已加入 Git 忽略规则。

Cloudflare Workers：将文件内 `CLIENT_TRANSPORT_PRIVATE_KEY` 等号后的值保存为同名 Worker Secret；例如运行下面命令后在提示中输入密钥值。使用分层配置时，为命令添加 `--config wrangler.tiered.jsonc`。

```powershell
npx.cmd wrangler secret put CLIENT_TRANSPORT_PRIVATE_KEY
```

本地 Wrangler：把生成文件中的整行配置加入 `backend/.dev.vars`。Linux：把同一行加入服务的环境文件，例如 `/etc/chimera/backend.env`，重启服务。

需要部署包含 `src/http/client-transport.ts` 的新版后端，并绑定 `USERS_DB`。Android 不需要内置服务端私钥或配置共享 AES 密钥。新版应用要求加密通信；后端未配置密钥时公钥接口返回 503，应用不会回退到明文。已配置私钥的后端拒绝客户端业务接口的直接明文访问，返回 426；管理后台和健康检查保留现有协议。没有配置密钥的后端保留旧客户端接口，供后端先行升级，不能用于新版应用的正式通信。

## 密钥和报文

- `GET /api/transport/key` 仅返回 DER SPKI 公钥，类型为 `application/pkix-key`。应用通过现有 HTTPS 证书校验取得公钥，按后端地址缓存 30 分钟。仅本地回环开发允许 HTTP。公钥引导与密文请求都禁止重定向。
- 每个 native 会话从系统熵源生成两把独立的 256 位 AES 密钥，分别用于请求和响应，用 RSA-OAEP-SHA256 一起封装。密钥不经 JNI 返回 Kotlin，也不写入持久化存储；native 会话释放或响应校验后清零密钥。
- AES-256-GCM 使用随机 12 字节 IV 和 16 字节认证标签。请求和响应的 AAD 分别为 UTF-8 `chimera-transport-v1/request`、`chimera-transport-v1/response`；请求 AAD 还追加其完整报文头。
- 请求二进制格式：`CHM1 | RSA密文长度(u16大端) | RSA密文(64字节密钥材料) | IV(12) | 密文 | Tag(16)`。
- 响应二进制格式：`CHR1 | IV(12) | 密文 | Tag(16)`。外层成功状态固定为 200，类型为 `application/vnd.chimera.encrypted`，业务状态码在经过认证的响应内。
- 请求明文为 UTF-8 JSON：`{method,path,contentType,body,timestamp}`，`body` 为标准 Base64，`timestamp` 为 Unix 毫秒。响应明文为 `{status,headers,body}`，`headers` 是名称和值的二维数组，`body` 为标准 Base64。
- 客户端时间需与服务端相差不超过两分钟。服务端使用 `USERS_DB` 中的 `client_transport_nonces` 表和唯一约束拒绝并发或跨实例重放，自动建表并清理过期记录，不修改账号数据。加密入口不允许访问管理接口或再次进入传输入口。
- 请求明文上限 1 MiB；响应业务正文上限 16 MiB。客户端拒绝明文响应、认证失败和超限报文；单次 native 会话只允许加密一次、解密一次。公钥轮换使缓存失效时，当前请求会失败，下一次请求重新取公钥。

## native 实现与验证

`app/src/main/cpp/transport` 使用静态链接的 [Mbed TLS 3.6.7](https://github.com/Mbed-TLS/mbedtls/releases/tag/v3.6.7)。CMake 固定源码提交和 SHA-256，只编译所需密码模块。首次构建需要下载源码，之后复用 CMake 缓存；可用 `FETCHCONTENT_SOURCE_DIR_CHIMERA_MBEDTLS` 指向同版本的本地源码。相关 API 见 [GCM 文档](https://mbed-tls.readthedocs.io/projects/api/en/v3.6.4/api/file/gcm_8h/)。许可证副本位于 APK 的 `assets/licenses`。

Kotlin 负责 HTTP 拦截和报文编码，AES、随机数、RSA 和认证校验都在 C++ 中完成。服务端用 WebCrypto，实现由 Worker 和 Linux 共用。

服务端协议测试：

```powershell
cd backend
npm.cmd run build
node --test tests/client-transport.test.mjs
```

额外验证实际 C++ 和 WebCrypto 的互通，在仓库根目录构建宿主测试程序（Windows 需要 C++ 编译器；其他系统去掉 `Release/` 和 `.exe`）：

```powershell
cmake -S android/app/src/main/cpp/transport -B android/.cxx/transport-host -DCHIMERA_TRANSPORT_TESTS=ON
cmake --build android/.cxx/transport-host --config Release
$env:CHIMERA_NATIVE_TRANSPORT_TEST_EXE = (Resolve-Path android/.cxx/transport-host/Release/transport-interop.exe).Path
cd backend
node --test tests/client-transport.test.mjs
```

HTTP 拦截器测试为 `BackendTransportInterceptorTest`，覆盖加密包装、业务错误恢复、公钥缓存、切换后端、第三方请求和重定向拒绝。

HTTPS 继续承担服务端身份验证。公钥目前没有额外的固定指纹；修改系统信任库或控制应用进程的一方仍可能获取业务明文。应用层加密不替代账户鉴权。
