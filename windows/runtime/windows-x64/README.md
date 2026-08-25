# Windows x64 runtime

这里的 `sing-box.exe`、`libcronet.dll` 和 `xray.exe` 分别由
`scripts/build-sing-box.ps1` 与 `scripts/build-xray.ps1` 从根目录中锁定的
官方源码构建/提取，不提交二进制产物。

执行 `./gradlew.bat buildSingBox buildXray`，或运行/打包 Windows 应用时会自动生成。
