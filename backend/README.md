# FireflyVPN Cloudflare Worker 后端

这是 FireflyVPN 的 Cloudflare Worker 后端。部署入口保持为 `serverless.js`，业务实现已按职责拆分，便于维护和测试。

## 目录结构

- `serverless.js`：Cloudflare Worker 稳定入口，仅负责导出运行时。
- `src/worker-core.js`：Worker 生命周期、统一异常处理、共享 D1/KV 辅助函数和路由组合。
- `src/config.js`：运行时常量、默认状态和 D1 初始化 schema。
- `src/routes/client.js`：客户端配置、订阅中转路由。
- `src/routes/account.js`：注册、绑定、登录、账户和任务路由。
- `src/routes/admin.js`：管理后台 API 路由。
- `src/admin-ui.js`：管理后台 HTML/CSS/JavaScript 页面。
- `schema.sql`：D1 schema 的可读副本。
- `migrations/`：后续 D1 增量迁移目录。

## Cloudflare 绑定

- KV 命名空间绑定：`CONFIG`
- D1 数据库绑定：`USERS_DB`
- Worker Secret：`ADMIN_TOKEN`

管理后台地址：

```text
https://你的域名/3e9a0eed-6a29-4d16-91b1-3adae4bd88e6
```

管理 API 需要携带：

```http
Authorization: Bearer <ADMIN_TOKEN>
```

## 数据库初始化

首次访问 `/health` 或任意账户接口时，Worker 会检查并初始化 D1 schema。返回结果中的 `database.schemaVersion` 应为 `1`。

如果绑定的 D1 已有数据但缺少版本标记，Worker 会拒绝自动覆盖，并返回 `users_db_unversioned_schema_detected`，请先备份并完成迁移。

## 兼容性说明

本次重构只调整代码组织，不改变现有 API 路径、请求字段、响应字段、D1 表名或 KV 状态格式。部署时仍指向 `backend/serverless.js`。
