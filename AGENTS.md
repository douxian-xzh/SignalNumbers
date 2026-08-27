# SignalNumbers 仓库协作约定

## 项目边界

- 这是一个 Android / Vector/Xposed SystemUI 模块，运行时替换蜂窝和 Wi-Fi 信号格为 dBm 数字。
- 不修改、不重签、不替换厂商 `SystemUI.apk`；厂商差异集中放在 `compatibility/` 包中。
- 小米/Redmi/POCO 的 HyperOS 3 适配是当前稳定目标；PJZ110/LineageOS 和其他系统仍处于适配与复测阶段。
- 模块作用域只能是 `com.android.systemui`。不要把真实设备日志、截图、ADB 地址、订阅标识或信号快照提交到仓库。

## 构建

Windows 本地构建：

```powershell
./gradlew.bat :app:assembleRelease
```

要求 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0。APK 输出在 `app/build/outputs/apk/release/`，该目录已被 Git 忽略。

## 修改适配逻辑

- 新增系统分支时，先在 `CompatibilityRegistry` 中增加明确的设备识别条件和 `CompatibilityMode`，再在对应适配器中声明差异开关。
- 小米模式与 PJZ110 模式必须保持隔离；不要用通用默认值覆盖已验证的 Xiaomi HyperOS 3 行为。
- 修改后至少执行 release 构建；涉及真实设备时，再检查 SystemUI 日志中是否出现崩溃。

## 提交内容

只提交源码、Gradle 配置、编译所需的 Xposed stub、文档和可复现构建所需的 wrapper。APK、签名文件、运行日志、截图、缓存、备份和真实配置不进入 Git。
