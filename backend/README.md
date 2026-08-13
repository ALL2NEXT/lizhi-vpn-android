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

任务中心的每日签到记录保存在 D1 的 `daily_checkins_mb` 表中，按北京时间每天只能签到一次。签到经验可在管理后台“应用设置”的“每日签到经验奖励”中调整，默认 10 经验。

TG 加群任务使用后台设置的“TG 进群口令”自动校验。客户端输入正确口令后立即发放一次经验奖励，每个账户只能领取一次；口令不会下发到客户端。

智能测速结果共享使用 `POST /api/account/tasks/speed-share`，客户端可以通过 `GET /api/account/tasks/speed-share-results?uid=<UID>` 读取共享结果。智能共享只上报智能连接最终选中的一个最优节点，不额外执行独立测速流程；每个账户、每个北京时间小时、每个订阅组最多上传一次，因此三个订阅组每小时最多可上传三次。客户端和 Worker 都会执行该联合限制。Worker 同时记录请求所在省份和运营商（如电信、联通、移动），并在读取接口返回当前客户端省份和运营商，客户端会优先选择两者都匹配、其次省份匹配、再次运营商匹配的共享测速结果。成功上报会按后台“应用设置”中的智能测速共享经验奖励发放积分，默认 5 经验。D1 的 `speed_share_results_mb` 只保留每个节点更低的 TCPing/URL 延迟和更高的上下行带宽，管理员可以通过 `GET /<ADMIN_UUID>/api/speed-share-results` 查看当前保留结果。
