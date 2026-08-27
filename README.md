# SignalNumbers（信号数字化）

适用于 Vector/Xposed 的 SystemUI 模块。它在运行时把状态栏蜂窝与 Wi-Fi 信号图标替换为实时 dBm 数字，不修改 `SystemUI.apk`，不使用悬浮窗、常驻通知或高频轮询。

## 当前兼容性结论

- **小米/Redmi/POCO + 澎湃 OS（HyperOS）3 / Android 16**：当前版本已完成目标设备实机验证，用户可以直接安装模块 APK，在 Vector/Xposed 中只勾选“系统界面（`com.android.systemui`）”后重载 SystemUI。
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

版本 `1.0.15` 在保留上述 C 版信息层级的基础上增加 HyperOS 3 适配：按 SystemUI 的 `ModernStatusBarMobileView`、`ModernStatusBarWifiView` 和小米状态栏重建入口重新扫描信号视图，并隐藏 HyperOS 实际使用的 `mobile_type` / `mobile_type_single` 原始网络类型视图。模块折叠原网络类型容器，在同一个 TextView 中紧凑显示小号网络标签与大号实时数值。蜂窝按 NR/LTE/WCDMA/GSM 显示 `5G/4G/3G/2G`，Wi-Fi 显示 `WiFi`；主数值为 14sp，与电量百分比视觉高度一致。小标签单独向上校正，蜂窝与 Wi-Fi 容器会按照实际文本宽度动态伸缩，确保 `5G -100`、`5G -120` 等四位负数完整显示。

版本 `1.0.16` 修复 LineageOS / AOSP Android 16 双卡状态栏只显示一路的问题：针对 `stacked_mobile` Compose 容器按 `slotIndex` 排序并同时渲染全部活动订阅，例如 `5G -91 / 5G -77`；单卡显示保持不变，并随实际文本宽度扩展容器。

版本 `1.0.17` 修复 Redmi/HyperOS 3 在卡 2 负责蜂窝数据时，上下行指示图标与数字化 `5G` 文本重叠的问题：识别 `mobile_left_mobile_inout`，按图标实际可见状态动态增加左侧避让，并让数字文本在图标右侧区域内居中。卡 1 无上下行图标时不会额外占位。

版本 `1.0.18` 修复 Redmi/HyperOS 3 浅色状态栏的颜色同步问题：SystemUI 通过 `ModernStatusBarView` 外观回调切换到浅色状态栏时，已注入的蜂窝数字会同步为黑色；深色状态栏继续同步为白色。后续信号刷新会保留当前外观 tint，不会重新变回固定白色。

版本 `1.0.22` 修复 Redmi/HyperOS 3 锁屏颜色反向的问题：监听 `KeyguardStateControllerImpl` 和 `StatusBarStateControllerImpl` 的锁屏状态，锁屏期间无论状态栏 View 何时重建，数字化蜂窝/Wi-Fi 都使用白色；解锁后清除锁屏覆盖，恢复桌面或设置页的 SystemUI tint。

版本 `1.0.25` 修复 Redmi/HyperOS 3 下拉控制中心颜色反向的问题：HyperOS 控制中心单独使用 `ControlCenterExpandControllerDelegate`，展开后数字化蜂窝/Wi-Fi 会强制跟随控制中心顶部的白色状态栏；同时保留 AOSP/普通通知面板展开回退。冷启动 SystemUI 后首次打开控制中心仍能正确显示白色数字。

版本 `1.0.26` 在完全展开通知面板或控制中心时隐藏原生蜂窝/Wi-Fi 图标、模块数字覆盖层和 LineageOS Compose 双卡信号行，收起后重新渲染并恢复桌面、设置页和锁屏显示。PJZ110 的动态 Shade 类增加启动后短时重试，并兼容完全展开状态回调。Vector/Xposed 请求作用域已在 `app/src/main/assets/xposed_scope`、`app/src/main/resources/META-INF/xposed/scope.list` 和 Manifest 描述中明确标注为系统界面 `com.android.systemui`。

版本 `1.0.27` 将完全展开时的隐藏范围收窄为状态栏下方的重复蜂窝/Wi-Fi 信号行：顶部状态栏、桌面、锁屏、设置页以及 LineageOS 顶部 Compose 双卡信号继续显示；下方行通过 SystemUI 的 Shade 容器祖先层级识别并隐藏，避免同类顶部 Wi-Fi View 被误伤。

版本 `1.0.28` 修复 PJZ110 锁屏误隐藏：由于锁屏与展开面板可能复用 `ModernShadeCarrierGroupMobileView`，锁屏期间不再执行信号行隐藏，并在进入锁屏时主动恢复已被旧 Shade 状态隐藏的 View。只有明确解锁且完全展开时才隐藏下方重复行，普通下拉和锁屏数字保持显示。

版本 `1.0.29` 修复 HyperOS 3 状态栏数字颜色闪烁：小米原始信号 `ImageView` 的 tint 更新不再覆盖已经由 `ModernStatusBarView` 外观回调确认的状态栏颜色，避免数字在白色和黑色之间反复切换；在外观回调尚未到达时仍保留原始 tint 作为后备。

版本 `1.0.30` 修复小米完全展开控制中心颜色再次变黑：普通通知面板和 HyperOS 控制中心分别维护展开状态，普通面板的收起回调不会覆盖仍然打开的控制中心；控制中心完全展开时数字保持白色，收起后恢复最近一次 SystemUI 外观颜色。

版本 `1.0.31` 恢复 PJZ110 完全展开通知栏下方的蜂窝/Wi-Fi 信号行：该行为改为适配器级开关，仅 OnePlus/PJZ110 关闭重复行隐藏；小米继续保持 v1.0.30 的行为，不受本次调整影响。

版本 `1.0.32` 修复 PJZ110 展开通知栏颜色不一致：取消普通通知栏对数字的强制白色覆盖，改为跟随 LineageOS SystemUI 的实际外观 tint，使信号数字与黑色的电池、日期和时间保持一致；小米仍保留原有强制白色策略。

版本 `1.0.33` 补充 PJZ110 锁屏颜色适配：关闭该适配器在锁屏期间的强制白色覆盖，通知栏和锁屏均跟随 SystemUI appearance tint；小米的锁屏白色策略保持不变。

版本 `1.0.34` 修复 PJZ110 Compose/传统信号 View 颜色来源不一致：Compose 双卡数字和完全展开下方信号行统一继承传统移动/Wi-Fi 状态栏 View 的 appearance tint；小米不启用该回退路径。

版本 `1.0.35` 修复 PJZ110 通知栏颜色回退污染桌面/锁屏的问题：传统 View 的同伴 tint 只在完全展开通知栏中作为临时回退，Compose 自身 tint 不再被持久覆盖；进入锁屏时重新计算布局，并隐藏被锁屏复用的下方重复蜂窝/Wi-Fi 行，避免锁屏位置错乱。小米设备和 v1.0.30 行为不变。

版本 `1.0.36` 修复 PJZ110 解锁后桌面信号堆叠：LineageOS 同时保留了传统顶部蜂窝 View 和 stacked Compose 双卡容器，导致两套数字在同一位置绘制。PJZ110 现在在 Compose 双卡容器可见时隐藏传统顶部蜂窝 View；完全展开通知栏下方的信号行仍保留显示。小米适配器和 v1.0.30 行为不变。

版本 `1.0.37` 增加运行时系统模式隔离：按厂商、品牌、型号、设备代号、Android API 和 HyperOS 构建标识选择 `pjz110-lineage`、`xiaomi-hyperos3` 或通用 `aosp` 模式。PJZ110 专用的 Compose/传统 View 隐藏逻辑再次检查模式标识，即使把新版本误装到小米，也不会启用 PJZ110 规则。

版本 `1.0.38` 修复 PJZ110 下拉收起后重复信号行残留：`ModernShadeCarrierGroupMobileView` 下方蜂窝/Wi-Fi 行现在只允许在完全展开通知栏时显示，收起、桌面和锁屏状态统一隐藏；顶部 Compose 双卡数字保持显示，避免重复行再次压到桌面状态栏。

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
3. 作用域只勾选“系统界面（`com.android.systemui`）”。
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
