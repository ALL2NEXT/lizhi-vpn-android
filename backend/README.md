# Cloudflare Worker 后端

本目录保存 FireflyVPN 的 Cloudflare Worker 单文件后端。

## 部署文件

- Worker 入口：[serverless.js](serverless.js)
- KV 绑定名称：`CONFIG`
- D1 绑定名称：`USERS_DB`
- Worker Secret：`ADMIN_TOKEN`

可以在 Cloudflare Workers 控制台中直接部署 `serverless.js`，也可以使用 Wrangler 指定该文件部署。部署后，管理后台地址为：

```text
https://你的域名/3e9a0eed-6a29-4d16-91b1-3adae4bd88e6
```

管理后台密码就是 `ADMIN_TOKEN` 的值。不要将 `ADMIN_TOKEN` 写入代码或提交到 Git。

## 重要说明

`serverless.js` 是后端部署源文件，不是构建产物。修改 Worker 后需要重新部署该文件；客户端 APK 的构建不会自动更新 Worker。

TG 加群任务提交记录保存在 D1 的 `telegram_task_submissions_mb` 表中。任务“驳回”允许用户重新提交，“永久拒绝”不允许再次提交。
