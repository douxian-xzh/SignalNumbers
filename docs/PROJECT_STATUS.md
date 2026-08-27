# 项目状态

更新：2026-08-27

## 项目定位

SignalNumbers 是一个面向 Vector/Xposed 的 Android SystemUI 模块，在运行时将蜂窝和 Wi-Fi 信号图标替换为实时 dBm 数字。模块不修改系统分区或厂商 SystemUI APK，设置通过模块自身的 Provider 跨进程传递。

项目类型：`software / Android / Xposed module`

## 当前状态

- 当前版本：`1.0.38`，versionCode `39`。
- Xiaomi/Redmi/POCO + HyperOS 3 / Android 16：已完成当前目标设备的实机验证，可直接安装模块 APK，在 Vector/Xposed 中只勾选“系统界面”。
- PJZ110 / LineageOS / Android 16：已实现双卡、Compose 状态栏和展开通知栏的适配路径，但仍属于适配阶段；其他系统不保证布局、颜色和 View 结构兼容。
- 运行时通过 `CompatibilityRegistry` 按厂商、品牌、型号、设备代号、API 和系统构建标识选择 `xiaomi-hyperos3`、`pjz110-lineage` 或 `aosp` 模式。

## 已完成的关键工作

- 双卡按 `subscriptionId + slotIndex` 独立读取和渲染，不只读取默认数据卡。
- Xiaomi HyperOS 3 的 5G/4G 标签、数据活动图标避让、浅色/深色、锁屏和控制中心颜色适配。
- PJZ110 的 LineageOS Compose 双卡显示、展开通知栏重复行处理、桌面/锁屏显示隔离。
- 模块 Manifest、Vector/Xposed scope 和设置页均明确标注请求应用为 `com.android.systemui`。
- 构建使用 Gradle Wrapper，可在无额外 Gradle 安装的 Windows 环境复现。

## 维护重点

- 厂商升级可能改变 SystemUI 类名、资源名、View 层级或 Compose 结构；适配代码应继续集中在 `app/src/main/java/com/xinsu/signalnumbers/compatibility/`。
- 小米适配属于稳定基线，新增其他系统规则时必须通过运行时模式隔离，不能影响 `xiaomi-hyperos3`。
- 公开仓库中只保留可复现的源码和脱敏文档，不提交设备地址、日志、截图、订阅 ID、实时信号值、APK 或签名材料。

## 构建与验证

```powershell
./gradlew.bat :app:lintRelease :app:assembleRelease
```

release APK 默认使用 Gradle 调试签名，适合本地侧载验证；正式分发前应在本机配置独立的 release keystore，签名材料不得进入仓库。
