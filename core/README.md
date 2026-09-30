# 内核源码

此目录集中维护流萤使用的代理内核子模块，并在父仓库中固定每个内核的版本：

- `sing-box/`：SagerNet sing-box 源码，供 Android 构建使用。
- `xray-core/`：XTLS Xray-core 源码，供原生 Xray 运行器使用。

`hev-socks5-tunnel/` 仍保留在仓库顶层，因为它是数据面 TUN 适配器，不属于代理内核。

初始化全部源码：

```bash
git submodule update --init --recursive
```

不要直接从会移动的分支构建。更新内核时先检出并审阅明确的标签或提交，确认后再把对应的 gitlink 记录到父仓库。
