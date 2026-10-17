# 后台 TypeScript 迁移分析

后台由 Cloudflare Workers 入口、业务规则、D1/KV 服务、路由工厂以及管理页面资源组成。原有 23 个服务端 JavaScript 模块约 4,100 行，模块职责已经分离，适合直接引入 TypeScript。迁移重点是约束跨模块数据和依赖关系。

| 范围 | 适合程度及原因 | 本次处理 |
| --- | --- | --- |
| `domain/account`、`time`、`analytics` | 高：账号类型、日期周期、流量单位和统计行结构稳定，纯函数容易验证 | 转为 TS，使用账号类型、统计周期联合类型和查询行类型 |
| `services/user-store` | 高：数据库列、设备身份、账号模型和可空查询结果跨模块传递 | 转为 TS，为 D1 查询指定行类型，明确 `null` 和流量结构 |
| `services/state`、`subscriptions` | 高：KV 配置存在历史字段别名，缓存和订阅协议有明确结构 | 转为 TS，定义归一化状态、设置和订阅类型；外部输入保留校验 |
| 其余服务、HTTP、通用工具 | 高：请求、响应、二进制缓冲区和服务参数可明确表达 | 转为 TS，使用 Workers 的平台类型和 `unknown` 输入 |
| `routes/account`、`client`、`admin/*` | 高：依赖注入对象有大量函数，原先缺少签名约束 | 转为 TS，各工厂只接受所需依赖的 `Pick`，请求字段先校验 |
| `worker-core`、`config`、`admin-ui` 资源组合器 | 高：可校验绑定、默认状态、依赖装配和 Worker 导出 | 转为 TS，使用 `satisfies RouteDependencies`、`ExportedHandler<Env>` |
| `src/admin-ui/scripts/*.client.js` | 需要单独评估：这些文件作为 Text 资源拼接到 HTML，并直接在浏览器执行 | 保留 JS；改为 TS 需要先建立浏览器脚本编译、资源导入和跨脚本类型方案 |
| `serverless.js` | 收益较低：只有一个导出，多个现有部署配置引用它 | 保留部署入口，转而导出 `worker-core.ts` |
| HTML、CSS、`schema.sql` 和 Wrangler 配置 | 属于页面、样式、数据库和部署资源 | 保持相应格式 |

`src/types/models.ts` 定义业务对象；`database.ts` 描述各个 SELECT 的投影；`routes.ts` 直接从实现函数取得签名，避免手工重复函数参数；`env.ts` 补充管理 Secret，并保留原有缺失绑定检查所需的可选性。

运行时类型由 `wrangler types` 按现有兼容日期和 `nodejs_compat` 标记生成，采用 [Cloudflare 官方建议的生成方式](https://developers.cloudflare.com/workers/languages/typescript/#generate-types)。生成文件和 dry-run 产物放在已忽略的 `.wrangler/` 中。`tsconfig.json` 启用 `strict`、`noEmit` 和 Bundler 模块解析，源码之间使用显式 `.ts` 导入，由 Wrangler 转译并打包。

TypeScript 类型在运行时会被擦除，不能替代 JSON、KV 和上游内容校验。请求 JSON 字段保持 `unknown`，由既有的账号、时间、订阅和状态归一化函数处理。D1 的泛型声明描述 SQL 返回结构，不验证真实数据库；安全读路由中的异构 `batch` 在查询边界使用元组断言，与两个相邻 SELECT 的投影对应。

本次保留数据库 schema、有效 API 的字段和旧 KV 键兼容逻辑。非对象 JSON 请求采用空对象进入字段校验，`publicAccount` 对空查询结果显式抛错。浏览器资源没有引入编译流程。新增依赖均为开发依赖；Miniflare 固定为当前 Wrangler 所用版本，以复用同一个本地 Workers 运行时。

验证命令在 `backend/` 中运行：

```powershell
npm.cmd ci
npm.cmd run typecheck
npm.cmd test
npm.cmd run build:tiered
```

`npm test` 包含主 Worker 类型检查、dry-run 和一组本地测试。验证账号注册、密码绑定与登录、一次性注册码、会话流量去重、管理查询与编辑、封禁关联设备、导航输入校验、订阅解码及重命名、旧 KV 状态双写、调试地址过滤、蜜罐和 CORS。Miniflare 使用临时本地数据库和命名空间；测试与 build 命令不会发布 Worker。
