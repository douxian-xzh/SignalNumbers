# SignalNumbers（信号数字化）

适用于 Vector/Xposed 的 SystemUI 模块。它在运行时把状态栏蜂窝与 Wi-Fi 信号图标替换为实时 dBm 数字，不修改 `SystemUI.apk`，不使用悬浮窗、常驻通知或高频轮询。

English: [README.en.md](README.en.md)

## 当前兼容性结论

- **小米/Redmi/POCO + 澎湃 OS（HyperOS）3 / Android 16**：当前版本已完成目标设备实机验证，用户可以直接安装模块 APK，在 Vector/Xposed 中启用模块并勾选推荐的“系统界面（`com.android.systemui`）”后重载 SystemUI。
- **PJZ110 / LineageOS / Android 16**：已提供适配路径，但仍处于适配和持续复测阶段；其他系统的布局、颜色和双卡表现暂不保证。
- 小米适配与其他系统通过运行时设备识别隔离。即使误把新版本安装到小米设备，也不会启用 PJZ110 专用的隐藏和布局规则。

项目状态和维护边界见 [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md)，详细兼容性说明见 [docs/DEVICE_COMPATIBILITY.md](docs/DEVICE_COMPATIBILITY.md)。

## 目标设备与实机状态

- 一加 13（PJZ110）
- Android 16 / API 36
- ColorOS / OxygenOS / LineageOS SystemUI
- 模块作用域：仅 `com.android.systemui`
- Redmi 机型（23117RK66C / manet）
- HyperOS 3 / Android 16（SystemUI 16.03.251211.r）

## 主要版本变化

- `1.0.45`：为 `com.android.systemui` 增加 Vector/LSPosed 推荐作用域声明。
- `1.0.44`：将 SystemUI 初始化和信号查询移至后台处理，并增加独立的 Hook 开关，降低卡顿和 ANR 风险。
- `1.0.40–1.0.43`：PJZ110 改为单元素合并显示，并完善桌面、通知栏和锁屏适配。
- `1.0.15–1.0.37`：完成 HyperOS 3 小米适配，补充双卡、颜色及 LineageOS 适配，并隔离不同系统的规则。

设备分析结果见 [docs/DEVICE_COMPATIBILITY.md](docs/DEVICE_COMPATIBILITY.md)。厂商类名、资源名和 Hook 点全部集中在 `compatibility` 包中。仓库文档不保存真实设备地址、订阅标识、实时信号快照、日志或哈希。

## 功能

- NR → LTE → WCDMA → GSM 的 dBm 优先级；支持 4G、5G NSA、5G SA。
- 每个活动订阅分别注册 `TelephonyCallback`，用 `subscriptionId` 与 `slotIndex` 绑定状态栏中的对应 SIM View。
- Wi-Fi 优先读取 SystemUI 状态对象中的 RSSI；当前 PJZ110 SystemUI 模型不含 RSSI 字段，因此使用 `NetworkCapabilities/WifiInfo`、`WifiManager` 与系统 RSSI 广播作为事件驱动回退。
- 资源名识别、View 树识别和方法特征 Hook 三层适配；只在确认已添加数字 View 后才隐藏原图标。
- 深浅色跟随原 `ImageView` 的 tint，字体为 `sans-serif-condensed`，支持字号、粗体、负号和小号 `dBm`。
- 蜂窝与 Wi-Fi 均使用简洁纯数字样式；默认使用同一套 `sans-serif-condensed` 粗体字号，并共同继承 SystemUI tint。
- C 版标签层级：`5G -86`、`WiFi -44`；标签使用主字号的 62% 和常规字重，标签与数值之间只保留一个窄空格，负数值默认使用 14sp 粗体。
- 配置通过导出的只读式配置 Provider + `ContentObserver` 实时传递给 SystemUI，不依赖跨进程 SharedPreferences 文件权限。
- 连续 2 分钟内出现 5 次注入异常时进入 30 分钟安全模式并恢复原图标。
- 日志经模块 Provider 写入本应用的设备保护私有目录，限频且自动轮转；可在设置页导出。
- 熄屏时信号变化只缓存不刷新 View，亮屏后事件驱动刷新。
- 设置页可隐藏或恢复桌面图标；隐藏只禁用独立启动器入口，不影响模块和 SystemUI 注入，并保留 Vector/Xposed 模块设置入口。

## 项目结构

```text
app/src/main/java/com/xinsu/signalnumbers/
├─ compatibility/   # AOSP、一加/ColorOS 与小米/HyperOS 适配点
├─ config/          # 跨进程配置、日志、安全模式
├─ injection/       # 资源/View 识别、同容器 TextView 注入与恢复
├─ signal/          # 蜂窝、双卡、Wi-Fi 事件监听
├─ ui/              # 设置界面
└─ xposed/          # 模块入口、Hook 安装与运行时协调
```

`xposed-stubs` 是仅用于编译的 Xposed API 签名模块，不会打包进 APK；运行时由 Vector/Xposed 框架提供真实 API。

## 编译

要求：JDK 17、Android SDK Platform 36、Build Tools 36.0.0。

Windows：

```powershell
./gradlew.bat :app:assembleRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`。

本项目的 `release` 变体为便于直接侧载，使用 Gradle 默认调试证书签名。正式分发时请在本机配置自己的 release keystore，并替换 `app/build.gradle.kts` 中的签名配置。

## 安装与启用

1. 安装 APK：`adb install -r app-release.apk`。
2. 打开 Vector，启用“信号数字化”。
3. 作用域勾选推荐的“系统界面（`com.android.systemui`）”。如果管理器版本未显示推荐标记，仍只选择这个包名。
4. 重启 SystemUI 或重启手机。
5. 打开模块设置页按需调整；后续设置通常实时生效。
6. 如需隐藏应用图标，可在设置页“维护”中启用“隐藏桌面图标”；之后可从 Vector 的模块详情重新进入设置。

## 故障恢复

- 设置页点击“恢复系统原图标”会立即关闭替换。
- 如果目标 View 未被可靠识别，模块不会隐藏原图标。
- 连续异常会触发安全模式；可等待自动恢复，或在设置页清除安全模式。
- 设置页“导出调试日志”可生成文本日志用于适配分析。
- 若 SystemUI 反复重启，可先在 Vector 中取消本模块作用域；模块本身不会修改系统分区或 SystemUI APK。

## 已知边界

- 厂商系统升级可能更改 SystemUI 结构。资源名和 View 树回退能覆盖多数变化，但重大升级后仍建议重新采集 SystemUI 结构。
- 双卡代码路径已按 `subscriptionId + slotIndex` 实现；本次 Redmi 实机截图已观察到两路 `5G` 状态栏信号，但不同插卡、运营商和无服务状态仍建议继续复测。
- 当前实机已完成蜂窝与 Wi-Fi 同时显示验证；若厂商后续调整状态栏固定宽度，仍可能需要重新校准容器宽度。
