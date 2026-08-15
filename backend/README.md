# FireflyVPN Cloudflare Worker 后端

这是 FireflyVPN 的 Cloudflare Worker 后端。部署入口保持为 `serverless.js`，业务实现已按职责拆分，便于维护和测试。

## 目录结构

- `serverless.js`：Cloudflare Worker 稳定入口，仅负责导出运行时。
- `src/worker-core.js`：Worker 组合根，只负责依赖装配、入口分发和统一异常日志。
- `src/config.js`：运行时常量、默认状态和 D1 初始化 schema。
- `src/domain/`：账号字段、时间周期和统计聚合等纯业务规则。
- `src/services/`：D1 用户仓储、奖励、测速、订阅和 KV 状态服务。
- `src/http/`、`src/lib/`：HTTP 响应、安全校验和通用值处理。
- `src/routes/client.js`：客户端配置、订阅中转路由。
- `src/routes/account.js`：注册、绑定、登录、账户和任务路由。
- `src/routes/admin.js`：管理 API 组合器，具体路由按安全、统计、AI、用户和状态拆在 `src/routes/admin/`。
- `src/admin-ui.js`：管理页资源组合器；HTML、CSS 和浏览器脚本位于 `src/admin-ui/`，脚本继续按业务模块拆分。
- `schema.sql`：D1 schema 的可读副本。
- `migrations/`：后续 D1 增量迁移目录。

## Cloudflare 绑定

- KV 命名空间绑定：`CONFIG`
- D1 数据库绑定：`USERS_DB`
- Worker Secret：`ADMIN_TOKEN`；启用 AI 看板分析时还需 `GEMINI_API_KEY`。

管理后台地址：

```text
https://你的域名/3e9a0eed-6a29-4d16-91b1-3adae4bd88e6
```

管理 API 需要携带：

```http
Authorization: Bearer <ADMIN_TOKEN>
```

## 数据库初始化

首次访问 `/health` 或任意账户接口时，Worker 会检查并初始化 D1 schema。当前用户数据库 schema 版本为 `4`。

如果绑定的 D1 已有数据但缺少版本标记，Worker 会拒绝自动覆盖，并返回 `users_db_unversioned_schema_detected`，请先备份并完成迁移。

## 兼容性说明

本次优化保持现有 API 路径、请求字段、响应字段和 KV 状态格式不变。流量统计改为从 `user_usage_mb` 查询时按日汇总，并通过 schema v4 移除旧的 `user_usage_daily_mb` 汇总表。部署时仍指向 `backend/serverless.js`。

## 本地验证

```powershell
npx.cmd wrangler deploy --dry-run
npx.cmd wrangler check startup
npx.cmd wrangler dev --local
```

`wrangler.jsonc` 使用 Text 模块规则导入管理页的 HTML、CSS 和 `*.client.js`，新增管理页资源时应沿用对应扩展名。
