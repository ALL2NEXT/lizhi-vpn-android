# Firefly Android

Android 客户端的独立 Gradle 工程。

## 常用命令

在本目录执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Linux 或 macOS 使用：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

应用模块位于 `app/`。本地 Android SDK 配置、签名配置与密钥文件也应放在本目录，具体配置和功能说明见仓库根目录的 `README.md`。

### sing-box 源码构建

Android 构建会自动调用根目录下锁定的 `sing-box` 子模块，生成本次构建使用的 `libbox.aar`。首次构建需要 Go 1.24.7+、JDK 17、Android SDK 和 NDK 28.0.13004108；Go 工具链会按 sing-box 要求自动安装 `github.com/sagernet/gomobile` v0.1.11。

首次获取源码后执行：

```bash
git submodule update --init --recursive
```

以后要添加或修改协议，直接修改根目录 `sing-box` 中的 sing-box 源码，然后重新执行 Gradle 构建即可。
