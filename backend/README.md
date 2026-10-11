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

首次访问 `/health` 或任意账户接口时，Worker 会检查并初始化 D1 schema。当前用户数据库 schema 版本为 `20`。历史经验奖励表与字段已在 schema v11 移除，账号类型由邮箱绑定和封禁状态决定；后台账号身份只有真人和机器人，新账号及历史未标记账号默认按真人处理，身份标记不会下发到客户端。

如果绑定的 D1 已有数据但缺少版本标记，Worker 会拒绝自动覆盖，并返回 `users_db_unversioned_schema_detected`，请先备份并完成迁移。

## 兼容性说明

TypeScript 迁移保持现有 API 路径、有效请求的响应字段、KV 状态格式和数据库 schema。JSON 请求字段先作为 `unknown` 读取，再由业务校验接受；非对象请求采用空对象进入原有字段校验，语法错误仍由现有异常处理返回。部署时仍指向 `backend/serverless.js`。

## IP 与用户关联

后台设置中的“IP 与用户”支持按完整 IP 查询关联用户、按完整用户 UID 查询使用过的 IP，点击表格中的 IP 或 UID 可切换方向。每组关系显示首次访问、最近访问、累计成功请求、订阅下载和密码登录次数，支持分页，顶部统计与查询条件一致。

关系保存于 `user_ip_links_mb`，由成功的注册、登录、账户操作、配置和订阅请求更新，采集不受系统日志分类开关或记录时段影响。IP 只使用可信入口的 `CF-Connecting-IP`，IPv6 等价写法与 IPv4 映射地址会规范化；缺失或无效地址不建立关联。身份在业务处理时保存，设备切换账户不会改变历史记录，失败登录不建立目标账户关联。设备映射和密码登录分别标记，不把设备映射视为独立的会话认证。

管理 API 均需要管理员鉴权：

```http
GET /<ADMIN_PATH>/api/ip-relations?ip=192.0.2.65
GET /<ADMIN_PATH>/api/ip-relations?uid=<USER_UID>
GET /<ADMIN_PATH>/api/ip-relations?limit=50&offset=0
```

`ip` 和 `uid` 可组合筛选；无效参数返回 `400`。计数从此功能启用后累计，旧日志不做推测性回填。删除用户后保留 UID 与 IP 的历史关联并显示“已删除”。系统日志新增用户 UID、设备 ID 和身份来源，可按 UID 或设备 ID 搜索，详情中可查看这些字段。

## AI 风控分析

后台设置中的“AI 风控”可配置 Google Gemini 模型与最多 20 个 API Key，并手动运行 24 小时、3 天或 7 天分析。多个 Key 用英文逗号或分号分隔，每个 Worker 运行实例内按轮询顺序分配；认证失败、配额限制、服务端错误或某个 Key 无权访问选定模型时，会继续尝试其他 Key。模型目录会分页读取所有 Key 的模型并合并，仅保留 Gemini 对话模型，过滤 TTS、长任务和实时双向模型；后台显示每个模型可用 Key 数、输入/输出 token 上限与思考能力。当前接入使用 Google Gemini REST API；默认模型为 `gemini-3.8-flash`。思考强度可选模型默认、低、中、高；Gemini 3 使用 `thinkingLevel`，Gemini 2.5 映射为 `thinkingBudget`，旧模型使用其默认行为。分析结果会回传 Gemini 的输入、输出和思考 token 用量（若 API 提供）。Key 保存在服务端 `CONFIG` KV，设置读取接口只返回配置数量和是否已配置，不会回传密钥；保存密钥的请求不会进入系统日志完整请求捕获。留空保存会保留当前 Key 列表，替换或撤销 Key 后请在后台更新配置。

分析请求从请求日志和 `user_ip_links_mb` 汇总订阅请求、逐小时活动、错误状态、访问端点与单 IP 账户数。发送给 Google 前，IP 与 UID 会使用本次分析临时密钥替换为伪名引用；不发送请求正文、密码或订阅内容。请求日志遵循系统日志开关、记录时段和 30 天保留期，IP 关联汇总从启用采集后累计。分析只在管理员手动触发时执行，结果需要人工复核，不会自动封禁。

管理员 API：

```http
GET  /<ADMIN_PATH>/api/ai-risk/settings
PUT  /<ADMIN_PATH>/api/ai-risk/settings
GET  /<ADMIN_PATH>/api/ai-risk/models
POST /<ADMIN_PATH>/api/ai-risk/test
GET  /<ADMIN_PATH>/api/ai-risk/data?windowHours=24
POST /<ADMIN_PATH>/api/ai-risk/analyze
```

`windowHours` 支持 1 到 168 小时。数据接口返回按 IP/UID 伪名化的请求行为数据，可在接入 Gemini 前预览；分析接口在服务端调用 Gemini，并仅在本地响应中还原命中的 IP 与 UID，原始标识不会发给 Google。

## 分组与智能导入

客户端先注册或同步设备信息，再请求 `GET /api/client?deviceId=<设备ID>`。后台使用数据库中的账号状态、邮箱绑定类型、真人/机器人身份、会员状态及设备上报的 App 版本筛选分组，只返回当前设备可访问的订阅名称和下载地址；权限规则及真人/机器人字段均不下发。没有可用订阅时正常返回 `subscriptions: []`，公告、更新和客户端设置仍返回。缺少或无效的设备 ID 返回 401，未注册设备返回 403。

客户端获取列表时每次查询后台，缓存按后台地址、设备、账号和权限状态隔离。实际下载仍使用 `/api/subscription/<订阅ID>?deviceId=<设备ID>` 或 `/api/subscription/lb/<分组ID>?deviceId=<设备ID>`，后台再次校验权限。此接口调整需要配套新版客户端，旧版未携带设备 ID 的列表请求会被拒绝。

在“分组”页面创建分组，并为分组设置账号状态、账户类型和 App 版本区间。版本区间左右边界均包含，留空表示该侧不限制；可从已有版本下拉选择，也可直接输入。账号状态由封禁记录判断，账户类型根据是否绑定邮箱区分游客和正式用户。每个分组可包含多条订阅，点击「添加订阅」后可粘贴内容或从剪贴板、文件读取，再点击「识别并预览」。确认后添加到当前分组，可继续编辑订阅名称、缓存和自动重命名，点击「推送」保存并下发。

识别规则移植自 Android 私人源导入：支持节点 URI、递归/换行/URL-safe Base64、Clash YAML、sing-box/Xray JSON，以及混合输入和多文档。配置字段中的 URL 和 Base64 凭据保持原义。远程 URL 去重后分别创建线路并保留地址；本地节点去重后合并成「导入内容」。节点保留 TLS、Reality 与原生传输参数，其他路由、规则集和选择组配置丢弃。

远程订阅按客户端 AUTO 模式尝试 User-Agent，并用响应的 `Profile-Title`、`Subscription-Name`、`Content-Disposition` 或域名命名。每个来源最多等待 25 秒，单个来源失败不影响其他成功来源。输入、文件和单个上游响应最大 2 MB；一次最多导入 20 个远程地址。留空可创建待填写的空白线路。

管理 API `POST /<ADMIN_PATH>/api/subscriptions/import` 接收 `{ "input": "...", "minAccountType": "guest" }`，返回 `sources`、`nodeCount` 和 `failedSources`，只生成预览，不写 KV。现有外部订阅的缓存预热与更新也使用同一格式转换器。

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

隔离部署配置位于 `wrangler.tiered.jsonc`，Worker 名称为 `chimera-tiered`，绑定独立的 `chimera-tiered-config` KV 与 `chimera-tiered-users` D1，不读取或覆盖现有 `chimera` Worker 的数据。后台按分组配置权限；客户端接口按设备 ID 返回已筛选的平铺订阅列表，订阅下载接口会再次校验完整权限。账号身份及分组权限规则只在服务端使用，不会下发到客户端。

部署此项目：

```powershell
npx.cmd wrangler d1 migrations apply chimera-tiered-subscriptions --remote --config wrangler.tiered.jsonc
npx.cmd wrangler deploy --config wrangler.tiered.jsonc
npx.cmd wrangler secret put ADMIN_PATH --config wrangler.tiered.jsonc
npx.cmd wrangler secret put ADMIN_TOKEN --config wrangler.tiered.jsonc
```
