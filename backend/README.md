# Cloudflare Worker 后端

本目录保存 FireflyVPN 的 Cloudflare Worker。完整 D1 schema 已内嵌在 Worker 中，
生产部署只需要 `serverless.js` 一个文件。

## 部署文件

- Worker 入口：[serverless.js](serverless.js)
- D1 建库脚本：[schema.sql](schema.sql)（仅作为审阅和本地测试副本）
- KV 绑定：`CONFIG`
- D1 绑定：`USERS_DB`
- Worker Secret：`ADMIN_TOKEN`

管理后台地址：

```text
https://你的域名/3e9a0eed-6a29-4d16-91b1-3adae4bd88e6
```

## 初始化 D1

创建一个全新空 D1 并绑定为 `USERS_DB`。部署 Worker 后第一次访问 `/health`
或任意账户接口时，Worker 会自动、幂等地创建全部表、索引、触发器和版本标记：

```text
https://你的域名/health
```

成功响应中的 `database` 应为：

```json
{"initialized":true,"schemaVersion":1}
```

不需要上传或手动执行 `schema.sql`。如果绑定的数据库不是空库，并且没有当前版本标记，
Worker 会返回 `users_db_unversioned_schema_detected`，防止意外覆盖旧库。

## 数据模型

- `users_mb`：账户、封禁状态、累计经验。
- `user_devices_mb`：持久设备身份及当前账户；账户删除或解绑时设备记录不会删除。
- `device_account_links_mb`：设备关联过的全部账户历史，用于封禁追溯。
- `device_bans_mb`：独立设备封禁账本；源账户删除后封禁仍然有效。
- `user_usage_mb`：保留幂等会话明细，重复上报只接受单调增长的流量计数。
- `user_usage_daily_mb`：由触发器原子维护的永久日汇总，后台图表无需扫描全部会话。
- `user_reward_claims_mb`：签到、TG、绑定、测速奖励的统一幂等事件表；同时按账户和设备去重，账户删除后设备级领取记录仍保留。
- `speed_share_results_mb`：按订阅组、节点、省份和运营商保存最新完整测速样本。

账户等级根据 `experience_total` 和后台经验门槛动态计算，因此修改门槛后不需要批量重写用户记录。Lv0 仅作为客户端展示的封禁状态，数据库使用独立的 `status` 字段。

账户被设为封禁时，数据库触发器会封禁其当前及历史关联过的全部设备。设备不能通过解绑、切换账户、删除账户或重新注册解除封禁；管理员恢复账户后，才会解除由该账户产生的设备封禁。如果同一设备关联过多个封禁账户，必须全部恢复后设备才可继续使用。

## 身份与接口

公开 UID 只用于展示和管理员定位，不再作为客户端认证凭据。订阅、签到、任务、测速和流量接口均传递当前安装的 `deviceId`，Worker 会验证它与账户的绑定关系。

管理后台接口必须携带：

```text
Authorization: Bearer <ADMIN_TOKEN>
```

管理员可通过 `GET /<ADMIN_UUID>/api/devices` 检查每台设备的当前账户、全部历史关联账户以及仍然生效的封禁来源。

不要把 `ADMIN_TOKEN` 写入 App 或提交到 Git。公开调试用户接口已经移除。
