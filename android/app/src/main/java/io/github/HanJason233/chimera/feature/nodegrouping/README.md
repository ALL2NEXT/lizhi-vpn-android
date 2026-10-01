# 节点分组模块

该目录集中维护两类节点组：仓库组（官方组与个人组）和地区组。地区组根据地区规则动态汇总所有仓库组中的节点。

- `model/`：分组模式和页面展示模型，不包含业务操作。
- `domain/`：纯分组及智能测速范围规则，保持无 Android UI 依赖，便于单元测试。
- `ui/`：统一页面入口，以及地区组和仓库组各自的 Compose 界面。

模块边界：

- Room 查询继续由 `data/local/NodeDao.kt` 负责。
- DataStore 持久化继续由 `data/repository/SettingsRepository.kt` 负责。
- `MainViewModel` 只负责组合数据流和调度策略，不再实现具体分组规则。
- 应用导航只打开 `NodeGroupingDestination`，由该入口切换地区组和仓库组页面。
