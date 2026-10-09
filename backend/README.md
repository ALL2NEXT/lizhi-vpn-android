# Chimera Cloudflare Worker 后端

这是 Chimera 的 Cloudflare Worker 后端。服务端业务实现使用 TypeScript 严格模式，部署入口保持为 `serverless.js`。迁移范围和选择依据见 [TypeScript 迁移分析](docs/typescript-migration.md)。

## 目录结构

- `serverless.js`：Cloudflare Worker 稳定入口，仅负责导出运行时。
- `src/worker-core.ts`：Worker 组合根，只负责依赖装配、入口分发和统一异常日志。
- `src/config.ts`：运行时常量、默认状态和 D1 初始化 schema。
- `src/domain/`：账号字段、时间周期和统计聚合等纯业务规则。
- `src/services/`：D1 用户仓储、订阅、KV 状态、导航图标、后台访问记录和蜜罐服务。
- `src/http/`、`src/lib/`：HTTP 响应、安全校验和通用值处理。
- `src/routes/client.ts`：客户端配置、订阅中转路由。
- `src/routes/account.ts`：注册、绑定、登录、账户维护和流量上报路由。
- `src/routes/admin.ts`：管理 API 组合器，具体路由按安全、统计、用户和状态拆在 `src/routes/admin/`。
- `src/admin-ui.ts`：管理页资源组合器；HTML、CSS 和浏览器脚本位于 `src/admin-ui/`，浏览器脚本继续使用 JavaScript。
- `src/types/`：运行环境、业务模型、D1 查询行和路由依赖类型。
- `tests/`：核心规则测试和使用本地 D1/KV 的 Worker 集成测试。
- `schema.sql`：D1 schema 的可读副本。

## 客户端应用名单端点

```http
GET /api/app/proxy-packages
```

Worker 固定从上游获取 Android 代理应用包名列表，并以 `text/plain` 返回；上游不可用或名单格式异常时返回 `502`。客户端只需访问当前配置的后台域名。

## Cloudflare 绑定

- KV 命名空间绑定：`CONFIG`
- D1 数据库绑定：`USERS_DB`
- Worker Secret：`ADMIN_PATH`、`ADMIN_TOKEN`。

管理后台地址：

```text
https://chimera.dwads.workers.dev/<ADMIN_PATH>
```

管理 API 需要携带：

```http
Authorization: Bearer <ADMIN_TOKEN>
```

## 数据库初始化

首次访问 `/health` 或任意账户接口时，Worker 会检查并初始化 D1 schema。当前用户数据库 schema 版本为 `13`。历史经验奖励表与字段已在 schema v11 移除，账号类型由邮箱绑定和封禁状态决定。

如果绑定的 D1 已有数据但缺少版本标记，Worker 会拒绝自动覆盖，并返回 `users_db_unversioned_schema_detected`，请先备份并完成迁移。

## 兼容性说明

TypeScript 迁移保持现有 API 路径、有效请求的响应字段、KV 状态格式和数据库 schema。JSON 请求字段先作为 `unknown` 读取，再由业务校验接受；非对象请求采用空对象进入原有字段校验，语法错误仍由现有异常处理返回。部署时仍指向 `backend/serverless.js`。

## 分组与智能导入

在“分组”页面创建分组，并为分组设置账号状态、账户类型和 App 版本区间。版本区间左右边界均包含，留空表示该侧不限制；可从已有版本下拉选择，也可直接输入。账号状态由封禁记录判断，账户类型根据是否绑定邮箱区分游客和正式用户。每个分组可包含多条订阅，点击「添加订阅」后可粘贴内容或从剪贴板、文件读取，再点击「识别并预览」。确认后添加到当前分组，可继续编辑订阅名称、缓存和自动重命名，点击「推送」保存并下发。

识别规则移植自 Android 私人源导入：支持节点 URI、递归/换行/URL-safe Base64、Clash YAML、sing-box/Xray JSON，以及混合输入和多文档。配置字段中的 URL 和 Base64 凭据保持原义。远程 URL 去重后分别创建线路并保留地址；本地节点去重后合并成「导入内容」。节点保留 TLS、Reality 与原生传输参数，其他路由、规则集和选择组配置丢弃。

远程订阅按客户端 AUTO 模式尝试 User-Agent，并用响应的 `Profile-Title`、`Subscription-Name`、`Content-Disposition` 或域名命名。每个来源最多等待 25 秒，单个来源失败不影响其他成功来源。输入、文件和单个上游响应最大 2 MB；一次最多导入 20 个远程地址。留空可创建待填写的空白线路。

管理 API `POST /<ADMIN_PATH>/api/subscriptions/import` 接收 `{ "input": "...", "minAccountType": "guest" }`，返回 `sources`、`nodeCount` 和 `failedSources`，只生成预览，不写 KV。现有外部订阅的缓存预热与更新也使用同一格式转换器。

## 明文调试接口

后台设置中的“调试接口”板块可以一次选择一个或多个订阅线路（包括停用线路），生成一个带随机 UUID 的聚合明文接口。接口地址为：

```http
GET /api/subscription/debug/<uuid>
```

该接口不要求 `deviceId`，会按选择顺序合并返回所选线路的明文订阅内容，仅用于调试。它与普通订阅接口共用上游缓存和自动重命名处理，但不会执行订阅内容加密；请妥善保管 UUID，并勿将调试地址用于生产客户端。

## 本地验证

使用 Node.js 22.18 或更新版本，推荐 Node.js 24。首次安装后生成与 Wrangler 配置匹配的 Workers 类型：

```powershell
cd backend
npm.cmd ci
npm.cmd run types
npm.cmd run typecheck
npm.cmd test
npm.cmd run build:tiered
npm.cmd run dev
```

`npm test` 先进行严格类型检查和主 Worker dry-run 打包，再运行规则测试及本地 Worker 集成测试。`npm run build` 单独检查并打包主 Worker，`npm run build:tiered` 检查并打包分级 Worker；两者均不发布。测试使用临时的本地 D1/KV 数据。

类型文件生成于被 Git 忽略的 `.wrangler/worker-configuration.d.ts`；修改 Wrangler 绑定、模块规则或兼容日期后，重新运行 `npm run types`。实际部署前请运行对应的 build 命令，因为直接调用 `wrangler deploy` 只转译 TypeScript，不执行 `tsc` 类型检查。

`wrangler.jsonc` 使用 Text 模块规则导入管理页的 HTML、CSS 和 `*.client.js`，新增管理页资源时应沿用对应扩展名。

## Chimera Worker 部署

`wrangler.jsonc` 中的 Worker 名称为 `chimera`，部署会创建独立的 `chimera` Worker，并继续绑定现有的 `CONFIG` KV 与 `USERS_DB` D1 数据库，以沿用后台配置和用户数据。首次部署前需为新 Worker 单独设置 `ADMIN_PATH` 和 `ADMIN_TOKEN`：

```powershell
npx.cmd wrangler secret put ADMIN_PATH --name chimera
npx.cmd wrangler secret put ADMIN_TOKEN --name chimera
npx.cmd wrangler deploy
```

新版本以 Chimera 的 KV 键保存后台数据，并兼容读取、同步旧 Firefly 键；加密订阅响应同时返回新旧客户端 IP 响应头，便于客户端逐步迁移。

## 独立配置 Worker

隔离部署配置位于 `wrangler.tiered.jsonc`，Worker 名称为 `chimera-tiered`，绑定独立的 `chimera-tiered-config` KV 与 `chimera-tiered-users` D1，不读取或覆盖现有 `chimera` Worker 的数据。后台按分组配置权限；客户端接口返回兼容的平铺订阅列表，并附带分组权限，订阅下载接口会再次校验账号状态、邮箱绑定类型和 App 版本。

部署此项目：

```powershell
npx.cmd wrangler deploy --config wrangler.tiered.jsonc
npx.cmd wrangler secret put ADMIN_PATH --config wrangler.tiered.jsonc
npx.cmd wrangler secret put ADMIN_TOKEN --config wrangler.tiered.jsonc
```
