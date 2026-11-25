# Chimera 后端

这是 Chimera 的后端，服务端业务实现使用 TypeScript 严格模式，可部署到 Cloudflare Workers 或独立 Linux/Node.js。Worker 部署入口保持为 `serverless.js`。迁移范围和选择依据见 [TypeScript 迁移分析](docs/typescript-migration.md)。

Android 的业务接口使用 native AES-256-GCM 双向加密。部署新版应用前，需要运行 `node scripts/generate-transport-key.mjs` 并配置 `CLIENT_TRANSPORT_PRIVATE_KEY` Secret / 环境变量；具体步骤和协议见 [加密通信说明](../android/docs/encrypted-transport.md)。

## 目录结构

- `serverless.js`、`src/worker/entry.ts`：Cloudflare Worker 稳定入口和绑定适配器。
- `src/worker-core.ts`：Worker 与 Linux 共用的路由组合、请求分发和统一异常日志。
- `src/storage/contracts.ts`：共享业务层使用的平台无关数据库和配置存储接口。
- `src/storage/cloudflare.ts`：Cloudflare D1/KV 绑定适配器。
- `src/linux/`：Node.js HTTP 入口和 SQLite 存储适配器；仅 Linux 构建会包含 Node.js 运行时模块。
- `src/config.ts`：运行时常量、默认状态和用户数据库初始化 schema。
- `src/domain/`：账号字段、时间周期和统计聚合等纯业务规则。
- `src/services/`：用户数据库仓储、订阅、配置状态、导航图标和后台访问记录。
- `src/http/`、`src/lib/`：HTTP 响应、安全校验和通用值处理。
- `src/routes/client.ts`：客户端配置、订阅中转路由。
- `src/routes/account.ts`：注册、绑定、登录、账户维护和流量上报路由。
- `src/routes/admin.ts`：管理 API 组合器，具体路由按安全、统计、用户和状态拆在 `src/routes/admin/`。
- `src/admin-ui.ts`：管理页资源组合器；HTML、CSS 和浏览器脚本位于 `src/admin-ui/`，浏览器脚本继续使用 JavaScript。
- `src/types/`：运行环境、业务模型、查询行和路由依赖类型。
- `tests/`：核心规则测试和使用本地 D1/KV 的 Worker 集成测试。
- `schema.sql`：D1 schema 的可读副本。

## 客户端应用名单端点

```http
GET /api/app/proxy-packages
```

Worker 固定从上游获取 Android 代理应用包名列表，业务正文为 `text/plain`；上游不可用或名单格式异常时返回 `502`。配置传输密钥后，此接口通过加密入口调用，客户端解密后读取原业务正文。

## Cloudflare 绑定

- KV 命名空间绑定：`CONFIG`
- D1 数据库绑定：`USERS_DB`
- Worker Secret：`ADMIN_PATH`、`ADMIN_TOKEN`、`CLIENT_TRANSPORT_PRIVATE_KEY`。

管理后台地址：

```text
https://chimera.dwads.workers.dev/<ADMIN_PATH>
```

管理 API 需要携带：

```http
Authorization: Bearer <ADMIN_TOKEN>
```

## 数据库初始化

首次访问 `/health` 或任意账户接口时，Worker 会检查并初始化 D1 schema。当前用户数据库 schema 版本为 `28`。升级至 v28 会删除邮箱为空的游客账号及其流量、IP 和账号关联记录，并保留设备记录和封禁信息；部署前应备份用户数据库。后台账号身份只有真人和机器人，新账号及历史未标记账号默认按真人处理，身份标记不会下发到客户端。

如果绑定的 D1 已有数据但缺少版本标记，Worker 会拒绝自动覆盖，并返回 `users_db_unversioned_schema_detected`，请先备份并完成迁移。

## 兼容性说明

`POST /api/account/register` 使用设备信息、邮箱、密码和可选注册码直接创建账号；`POST /api/account/sync` 只同步已登录设备，不创建账号。旧客户端以无凭据的 `/api/account/register` 同步现有正式账号仍受支持，但旧版游客注册和游客绑定注册流程已移除，新用户需要更新客户端。部署时仍指向 `backend/serverless.js`。

仍运行旧版 schema v25 的独立 Worker 可以在备份后使用 `migrations/users/0028_remove_guests_legacy.sql` 清理游客并禁止再创建空邮箱账号。该脚本保留 v25 版本号，后续升级到当前 Worker 时仍会按序执行正式迁移。

## 用户关联 IP

后台不再提供单独的“IP 与用户”菜单；用户总表新增“关联 IP”列，可搜索并查看每个用户近期使用过的 IP。多条 IP 默认折叠展示，展开后可查看完整列表，CSV 导出也包含关联 IP。

关联关系独立写入 `user_ip_links_mb`，不依赖网络日志开关、日志记录时段或日志保留期。对成功响应（2xx）且已识别出用户身份的请求，Worker 会读取可信入口的 `CF-Connecting-IP` 并自动记录；同一用户和 IP 聚合保存首次/最近访问时间及请求、订阅和密码登录次数。IPv6 等价写法与 IPv4 映射地址会规范化，不信任 `X-Forwarded-For`。用户列表按最近访问时间排列关联 IP。

历史 IP 关系查询 API 仍保留以兼容已有调用，查询数据仍从 `system_logs_mb` 聚合；用户总表展示的关联 IP 使用上述独立汇总表。

管理 API 均需要管理员鉴权：

```http
GET /<ADMIN_PATH>/api/ip-relations?ip=192.0.2.65
GET /<ADMIN_PATH>/api/ip-relations?uid=<USER_UID>
GET /<ADMIN_PATH>/api/ip-relations?limit=50&offset=0
```

`ip` 和 `uid` 可组合筛选；无效参数返回 `400`。统计范围仅覆盖当前保留的日志，不从旧关联表或已过期数据补齐。删除用户后，保留期内日志中的 UID 与 IP 关联仍可见，账户状态显示“已删除”。系统日志新增用户 UID、设备 ID 和身份来源，可按 UID 或设备 ID 搜索，详情中可查看这些字段。

## 环境异常与行为异常

后台菜单拆分为“环境异常”和“行为异常”。“环境异常”展示客户端检测上报；“行为异常”展示 Gemini 辅助分析。Gemini 分析可读取系统请求日志，并只在管理员手动触发时运行。

“行为异常”中的 Gemini 辅助分析可配置 Google Gemini 模型与最多 20 个 API Key，并手动运行 24 小时、3 天或 7 天分析。多个 Key 用英文逗号或分号分隔，每个 Worker 运行实例内按轮询顺序分配；认证失败、配额限制、服务端错误或某个 Key 无权访问选定模型时，会继续尝试其他 Key。模型目录会分页读取所有 Key 的模型并合并，仅保留 Gemini 对话模型，过滤 TTS、长任务和实时双向模型；后台显示每个模型可用 Key 数、输入/输出 token 上限与思考能力。当前接入使用 Google Gemini REST API；默认模型为 `gemini-3.8-flash`。思考强度可选模型默认、低、中、高；Gemini 3 使用 `thinkingLevel`，Gemini 2.5 映射为 `thinkingBudget`，旧模型使用其默认行为。分析结果会回传 Gemini 的输入、输出和思考 token 用量（若 API 提供）。Key 保存在服务端 `CONFIG` KV，设置读取接口会返回模型、提示词、Key 数量及配置状态，不会回传密钥；保存密钥的请求不会进入系统日志完整请求捕获。留空保存会保留当前 Key 列表，替换或撤销 Key 后请在后台更新配置。

分析请求从 `system_logs_mb` 汇总订阅请求、逐小时活动、错误状态、访问端点与单 IP 账户数。另提供系统逐小时请求基线，用于判断全站低峰时段是否仍有订阅请求；日志采样或覆盖不完整时应降低置信度。发送给 Google 前，IP 与 UID 会使用本次分析临时密钥替换为伪名引用；不发送请求正文、密码或订阅内容。分析数据遵循系统日志开关、记录时段和 30 天保留期。分析只在管理员手动触发时执行，结果需要人工复核，不会自动封禁。

“提示词模板”设置支持自定义 Gemini 风险分析规则，服务端最多保存 12,000 字符，并要求模板中恰好包含一次 `{{DATASET_JSON}}` 占位符；分析时会将其替换为本次脱敏遥测 JSON。默认模板覆盖 24 小时持续订阅、全站低峰期仍订阅、只访问订阅接口、单 IP 多账户和 4xx 参数异常，并要求证据不足时说明无法判断。系统级安全约束仍要求模型忽略遥测中的指令、仅给人工复核建议且不得自动封禁。

管理员 API：

```http
GET  /<ADMIN_PATH>/api/ai-risk/settings
PUT  /<ADMIN_PATH>/api/ai-risk/settings
GET  /<ADMIN_PATH>/api/ai-risk/models
POST /<ADMIN_PATH>/api/ai-risk/test
GET  /<ADMIN_PATH>/api/ai-risk/data?windowHours=24
GET  /<ADMIN_PATH>/api/ai-risk/dashboard-data?windowHours=24
POST /<ADMIN_PATH>/api/ai-risk/analyze
```

`windowHours` 支持 1 到 168 小时。数据接口返回按 IP/UID 伪名化的请求行为数据，可在接入 Gemini 前预览；分析接口在服务端调用 Gemini，并仅在本地响应中还原命中的 IP 与 UID，原始标识不会发给 Google。

## 订阅分组

订阅按分组管理。管理员可以创建分组、调整订阅顺序，并设置分组权限。每条远程订阅可单独设置自动更新和缓存时间，自动更新默认关闭。

使用“新建订阅”在当前分组创建空订阅，再编辑名称、远程地址或本地节点内容。远程订阅仍由服务端在更新时拉取并转换为客户端 URI。

订阅数据库迁移 `0009_remove_account_type_access.sql` 移除不再使用的账户类型字段；之前禁止正式账号访问的分组会保留为关闭状态。为支持平滑升级，新版 Worker 可以在迁移前后读写订阅数据库。请先发布新版 Worker，确认运行正常并备份数据库，再执行该迁移。迁移后如需回滚至不支持该迁移的旧版 Worker，须先恢复迁移前的订阅数据库备份。

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

新版本以 Chimera 的 KV 键保存后台数据，并兼容读取、同步旧 Firefly 键。客户端接口的双向加密由统一传输入口处理。

## 独立配置 Worker

隔离部署配置位于 `wrangler.tiered.jsonc`，Worker 名称为 `chimera-tiered`，绑定独立的 `chimera-tiered-config` KV 与 `chimera-tiered-users` D1，不读取或覆盖现有 `chimera` Worker 的数据。后台按分组配置权限；客户端接口按设备 ID 返回已筛选的平铺订阅列表，订阅下载接口会再次校验完整权限。账号身份及分组权限规则只在服务端使用，不会下发到客户端。

部署此项目：

```powershell
npx.cmd wrangler d1 migrations apply chimera-tiered-subscriptions --remote --config wrangler.tiered.jsonc
npx.cmd wrangler deploy --config wrangler.tiered.jsonc
npx.cmd wrangler secret put ADMIN_PATH --config wrangler.tiered.jsonc
npx.cmd wrangler secret put ADMIN_TOKEN --config wrangler.tiered.jsonc
```

## Linux 独立部署

Linux 版使用 Node.js 运行同一套路由、管理后台和业务代码，数据保存在本机 SQLite，与 Cloudflare Worker 的 D1/KV 完全分开。首次启动会初始化用户 schema，并将 `migrations/subscriptions/` 中的订阅数据库迁移按序应用到本机数据库；它不会自动导入 Worker 里的用户、订阅或后台配置。

需要 Linux x64/ARM64 上可用的 Node.js `22.18.0` 或更新版本。Linux 构建会将服务入口和后台页面资源打包为 `dist/linux/server.mjs`：

```sh
cd backend
npm ci
npm run linux:build
```

部署时把完整的 `dist/linux/` 目录复制到服务器的 `/opt/chimera/backend/dist/linux/`；该目录同时包含运行入口和订阅迁移 SQL。

准备环境变量后可直接启动：

```sh
export ADMIN_PATH='替换成随机后台路径'
export ADMIN_TOKEN='替换成高强度管理令牌'
export HOST='127.0.0.1'
export PORT='8788'
export LINUX_DATA_DIR='/var/lib/chimera'
npm run linux:start
```

`ADMIN_PATH` 只允许 1 到 128 位字母、数字、下划线或连字符；未设置有效后台路径或 `ADMIN_TOKEN` 时服务会拒绝启动。SQLite 数据目录中会生成 `users.sqlite`、`subscriptions.sqlite` 和 `config.sqlite`。默认开发数据目录为 `backend/data/linux`，正式部署建议设置为专用持久化目录并纳入备份。

项目提供了 systemd 单元模板 `deploy/linux/chimera-backend.service`。把它安装为 `/etc/systemd/system/chimera-backend.service`，创建运行用户 `chimera`，将后端部署到 `/opt/chimera/backend`，并创建权限为 `600` 的 `/etc/chimera/backend.env`。环境文件至少包含 `ADMIN_PATH`、`ADMIN_TOKEN`、`HOST=127.0.0.1`、`PORT=8788` 和 `LINUX_DATA_DIR=/var/lib/chimera`；确认目录归运行用户所有后，运行 `systemctl enable --now chimera-backend`。

默认监听地址是 `127.0.0.1:8788`，适合由 Nginx 反向代理。若需要在管理后台保留客户端真实 IP，代理需设置可信的 `CF-Connecting-IP` 请求头。Linux 版会按 Worker 的每日 `19:00 UTC` 定时规则清理系统日志。

Linux 版使用 Node.js 内置的 `node:sqlite`，该 API 在 Node.js 文档中仍标记为实验性；升级 Node.js 前应先在备份数据上验证兼容性，参见 [Node.js SQLite 文档](https://nodejs.org/api/sqlite.html)。Cloudflare Worker 的 `serverless.js`、Wrangler 配置和部署方式继续用于原有 Worker 环境。
