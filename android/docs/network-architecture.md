# 网络与订阅代码组织

应用源码根包为 `io.github.HanJason233.chimera`。网络代码按业务职责组织，公共 HTTP 客户端与后端接口位于 `network` 根包。

```text
network/
├── NetworkClient.kt
├── ApiService.kt
├── account/                 # 账号注册、登录、绑定和流量上报
├── location/                # 节点地址与地理位置查询
├── subscription/
│   ├── SubscriptionParser.kt
│   ├── SubscriptionUserInfo.kt
│   ├── SubscriptionListParser.kt
│   └── importer/
│       ├── SubscriptionInputDetector.kt
│       ├── SubscriptionTextNormalizer.kt
│       ├── SubscriptionConverter.kt
│       ├── ClashYamlParser.kt
│       ├── JsonSubscriptionParser.kt
│       └── NodeUriEncoder.kt
└── nodetest/
    ├── common/              # 测试代理会话与 SOCKS HTTP 探测
    ├── latency/             # TCPing、URL 测试及各内核批量会话
    ├── bandwidth/           # 节点带宽和上下行测速
    ├── linktype/            # 链路类型识别
    └── unlock/              # 解锁目标、分类与执行

feature/nodegrouping/data/icon/
├── SubscriptionFaviconLoader.kt
└── PersonalSourceIconStore.kt
```

## 订阅导入流程

1. 输入框接收手动输入、剪贴板、二维码或文件内容。
2. `SubscriptionInputDetector` 使用 `SubscriptionTextNormalizer` 递归解码并拆成明文片段，区分配置之外的远程订阅地址与本地配置。
3. 本地配置保存为 `LocalSubscriptionContent`，直接携带片段列表进入转换器。多份 YAML/JSON 的边界保留在列表中，不拼接后再次解码。
4. 远程响应使用 `SubscriptionConverter` 的字符串入口，先归一化，再复用同一套片段转换流程。
5. YAML/JSON 解析器读取节点字段，`NodeUriEncoder` 统一编码节点 URI，并补充连接参数。
6. `SubscriptionParser` 将 URI 解析成应用节点；`MainViewModel` 负责私人源分组、导入结果与入库。

## 依赖边界

- `SubscriptionTextNormalizer` 只处理文本与配置边界，不调用格式解析器。
- `SubscriptionConverter` 调度格式解析器；`NodeUriEncoder` 不反向依赖转换器或解析器。
- `JsonSubscriptionParser` 将 JSON 字段映射到编码器使用的连接参数。`ClashYamlParser` 的原生出站 YAML 分支可以复用 JSON 适配器。
- `SubscriptionParser` 提供本地内容与远程抓取入口，格式适配器不发起网络请求。
- 节点测试共用 `nodetest/common` 的会话和探测能力，各测试类型在自己的子包内维护。
- 私人源图标属于节点分组的数据能力。远程图标加载使用公共客户端，本地图标存储管理应用私有文件。

相关单元测试按生产代码的包结构组织于 `app/src/test/java`。
