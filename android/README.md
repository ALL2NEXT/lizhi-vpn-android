# FireflyVPN Android

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
