# Chimera Cloudflare Worker 后端

这是 Chimera 的 Cloudflare Worker 后端。部署入口保持为 `serverless.js`，业务实现已按职责拆分，便于维护和测试。

## 目录结构

- `serverless.js`：Cloudflare Worker 稳定入口，仅负责导出运行时。
- `src/worker-core.js`：Worker 组合根，只负责依赖装配、入口分发和统一异常日志。
- `src/config.js`：运行时常量、默认状态和 D1 初始化 schema。
- `src/domain/`：账号字段、时间周期和统计聚合等纯业务规则。
- `src/services/`：D1 用户仓储、奖励、测速、订阅和 KV 状态服务。
- `src/http/`、`src/lib/`：HTTP 响应、安全校验和通用值处理。
- `src/routes/client.js`：客户端配置、订阅中转路由。
- `src/routes/account.js`：注册、绑定、登录、账户和任务路由。
- `src/routes/admin.js`：管理 API 组合器，具体路由按安全、统计、用户和状态拆在 `src/routes/admin/`。
- `src/admin-ui.js`：管理页资源组合器；HTML、CSS 和浏览器脚本位于 `src/admin-ui/`，脚本继续按业务模块拆分。
- `schema.sql`：D1 schema 的可读副本。
- `migrations/`：后续 D1 增量迁移目录。

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

首次访问 `/health` 或任意账户接口时，Worker 会检查并初始化 D1 schema。当前用户数据库 schema 版本为 `8`。

经验奖励仅发放给已绑定邮箱的正式用户；游客账户必须先绑定邮箱，邮箱绑定本身的首次奖励除外。数据库触发器也会拦截未绑定邮箱账户的经验增加。

如果绑定的 D1 已有数据但缺少版本标记，Worker 会拒绝自动覆盖，并返回 `users_db_unversioned_schema_detected`，请先备份并完成迁移。

## 兼容性说明

本次优化保持现有 API 路径、请求字段、响应字段和 KV 状态格式不变。流量统计改为从 `user_usage_mb` 查询时按日汇总，并通过 schema v4 移除旧的 `user_usage_daily_mb` 汇总表。部署时仍指向 `backend/serverless.js`。

## 明文调试接口

后台设置中的“调试接口”板块可以一次选择一个或多个订阅线路（包括停用线路），生成一个带随机 UUID 的聚合明文接口。接口地址为：

```http
GET /api/subscription/debug/<uuid>
```

该接口不要求 `deviceId`，会按选择顺序合并返回所选线路的明文订阅内容，仅用于调试。它与普通订阅接口共用上游缓存和自动重命名处理，但不会执行订阅内容加密；请妥善保管 UUID，并勿将调试地址用于生产客户端。

## 本地验证

```powershell
npx.cmd wrangler deploy --dry-run
npx.cmd wrangler check startup
npx.cmd wrangler dev --local
```

`wrangler.jsonc` 使用 Text 模块规则导入管理页的 HTML、CSS 和 `*.client.js`，新增管理页资源时应沿用对应扩展名。

## Chimera Worker 部署

`wrangler.jsonc` 中的 Worker 名称为 `chimera`，部署会创建独立的 `chimera` Worker，并继续绑定现有的 `CONFIG` KV 与 `USERS_DB` D1 数据库，以沿用后台配置和用户数据。首次部署前需为新 Worker 单独设置 `ADMIN_PATH` 和 `ADMIN_TOKEN`：

```powershell
npx.cmd wrangler secret put ADMIN_PATH --name chimera
npx.cmd wrangler secret put ADMIN_TOKEN --name chimera
npx.cmd wrangler deploy
```

新版本以 Chimera 的 KV 键保存后台数据，并兼容读取、同步旧 Firefly 键；加密订阅响应同时返回新旧客户端 IP 响应头，便于客户端逐步迁移。
