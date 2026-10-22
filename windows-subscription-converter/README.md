# Chimera 订阅转换器（Windows）

一个完全在本机运行的 Windows 桌面工具：读取远程订阅地址，由系统原生 HTTP 客户端拉取内容，再转换为标准节点 URI。项目只提供 Windows 桌面程序，不提供网页版或常驻本地 Web 服务。程序不连接 Chimera Worker，不需要管理员 Token，也不会上传订阅内容。

## 功能

- 支持一次输入多个 HTTP/HTTPS 订阅地址，并可设置 1–20 个来源的并发拉取（默认 3）。
- 使用 Rust `reqwest` 发起请求，不受 WebView 浏览器 CORS、CSP 或混合内容限制。
- 支持 Basic Auth（URL 中的 `user:pass@host`）、gzip、brotli 和 deflate。
- 自动尝试常见订阅客户端 User-Agent，也可手动选择或输入自定义值。
- 可选择跳过 TLS 证书验证，适用于证书异常或自签名的可信订阅源。
- 响应上限、最大重定向次数和拉取并发数可在顶部“设置”中调整。
- 支持 Base64 订阅、Clash YAML、sing-box/Xray JSON 以及直接节点 URI。
- 对节点 URI 去重，支持复制全部或保存为 UTF-8 `.txt` 文件。
- 每个来源独立显示状态、节点数、响应大小和错误信息。
- 请求和转换均在本机完成，不经过 Worker。

## 使用

1. 从 Release 安装并启动程序，或按下方说明自行构建。
2. 在“订阅 URL”中每行输入一个远程订阅地址。
3. 可在顶部“设置”中调整 User-Agent、响应上限、重定向次数、并发数量和证书验证。
4. 点击“开始转换”。
5. 转换完成后复制全部节点 URI，或保存为文本文件。

当前版本只处理远程订阅，不读取本地节点或本地配置文件。

## 技术栈

- Tauri 2
- Vanilla TypeScript + Vite
- Rust + `reqwest`
- `yaml` 解析器

前端只负责 Windows 程序内的界面和 URI 解析；网络请求通过 Tauri command 交给 Rust 完成，因此不会重新受到 WebView 跨域策略限制。安装后的程序直接加载内置资源，不依赖本地 HTTP 服务。

## 构建

环境要求：

- Windows 10/11
- Node.js 20 或更高版本
- Rust stable
- Microsoft WebView2 Runtime（Windows 11 通常已内置）
- 构建 NSIS 安装包所需的本机工具链

安装依赖：

```powershell
npm install
```

执行测试：

```powershell
npm test
cargo test --manifest-path src-tauri/Cargo.toml
```

构建 Windows 安装包：

```powershell
npm run tauri:build
```

产物默认位于：

```text
src-tauri/target/release/bundle/nsis/
```

如果当前网络无法直接访问 crates.io，可在构建前设置 HTTP 代理：

```powershell
$env:HTTP_PROXY = 'http://127.0.0.1:10808'
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'
$env:ALL_PROXY = 'http://127.0.0.1:10808'
```

## 安全与边界

- 仅允许 HTTP/HTTPS 订阅地址，重定向目标也必须是 HTTP/HTTPS。
- 连接超时 10 秒，请求总超时 30 秒；最大重定向默认 10 次，可在设置中调整为 0–50 次。
- 拉取并发数默认 3，可在设置中调整为 1–20；设置过高可能触发订阅服务限流。
- 单个订阅响应默认上限 2 MiB，可在设置中调整为 1–64 MiB；导出文件最大 64 MiB。
- 默认保持 TLS 证书校验；仅在手动开启“跳过证书验证”后关闭，请勿对不可信订阅源使用。
- URL 中的账号密码只用于 Basic Auth，并会从最终显示地址中移除。
- 保存功能只接受绝对路径的 `.txt` 文件。
- 程序不保存订阅地址历史，也不会把订阅内容发送到其他服务。

订阅服务本身仍可能根据 IP、User-Agent、鉴权信息或访问频率限制请求；这类限制由服务端决定，本工具无法绕过。TLS 证书错误、网络不可达和目标服务器拒绝访问也会显示在对应来源的错误信息中。

## 目录结构

```text
windows-subscription-converter/
├─ src/                         # 前端界面、解析与转换逻辑
│  └─ core/subscription-import/ # 订阅格式识别与 URI 转换
├─ src-tauri/                   # Tauri/Rust 原生层
│  └─ src/lib.rs                # HTTP 拉取、Basic Auth 与文件保存
├─ tests/                       # TypeScript 解析测试
├─ index.html
├─ package.json
└─ README.md
```
