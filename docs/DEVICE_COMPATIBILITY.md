# 设备兼容性与适配边界

本文档记录可复现的适配结论、SystemUI 结构要点和当前边界。设备连接地址、ADB 序列、订阅 ID、实时信号值、截图、日志和 APK/SystemUI 哈希均不保存在仓库。

## Xiaomi / HyperOS 3

已验证目标：Redmi `23117RK66C` / device `manet`，HyperOS 3，Android 16（API 36）。

- 运行模式：`xiaomi-hyperos3`。
- 主要移动 View：`com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView`。
- 主要 Wi-Fi View：`com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView`。
- 小米状态栏重建入口：`MiuiPhoneStatusBarView` 和 `MiuiCollapsedStatusBarFragment` 的生命周期方法。
- 关键资源：`mobile_signal`、`wifi_signal`、`status_bar_mobile_signal_group_new`、`status_bar_mobile_signal_group_inner`、`new_status_bar_wifi_group`、`status_bar_wifi_group_inner`。
- 已覆盖：5G/4G 标签、卡 2 数据活动图标避让、双卡独立读数、浅色/深色状态栏、锁屏和 HyperOS 控制中心颜色同步。

小米/Redmi/POCO 的 HyperOS 3 是当前稳定适配目标，用户可以安装模块 APK，在 Vector/Xposed 中只勾选 `com.android.systemui` 后重载 SystemUI。厂商更新仍可能改变内部结构，升级后应重新验证。

## PJZ110 / LineageOS

已验证目标：OnePlus `PJZ110`，LineageOS，Android 16（API 36）。

- 运行模式：`pjz110-lineage`。
- 主要移动 View：`com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView`。
- 主要 Wi-Fi View：`com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView`。
- 双卡容器：LineageOS 的 `stacked_mobile` Compose 容器。
- 展开通知栏相关容器：`ModernShadeCarrierGroupMobileView` 及其 Shade 祖先层级。
- 已实现：按订阅和卡槽渲染双卡、Compose/传统 View 显示隔离、展开通知栏重复信号行边界、桌面和锁屏状态保护、跟随 SystemUI appearance tint。

PJZ110、LineageOS 其他版本以及其他厂商系统仍属于适配阶段。未命中明确设备模式时使用 `aosp` 通用模式，不保证布局、颜色、双卡顺序或信号 View 可识别。

## 适配选择

`CompatibilityRegistry` 先构造 `DeviceIdentity`，再根据厂商、品牌、型号、设备代号、Android API 和系统构建标识选择模式：

| 条件 | 模式 | 说明 |
| --- | --- | --- |
| Xiaomi/Redmi/POCO + HyperOS 3 / API 36 | `xiaomi-hyperos3` | 小米稳定适配路径 |
| PJZ110 + OnePlus 标识 / API 36 | `pjz110-lineage` | LineageOS 适配路径 |
| 其他设备 | `aosp` | 通用回退，功能不保证 |

适配器差异集中在 `app/src/main/java/com/xinsu/signalnumbers/compatibility/`。新增规则必须使用明确的模式条件，不能让 PJZ110 专用的隐藏或布局逻辑影响 Xiaomi/HyperOS 3。

## 双卡信号数据路径

- 每个活动订阅独立注册 `TelephonyCallback`。
- 以 SystemUI View 的订阅标识与 `slotIndex` 绑定对应 SIM。
- 按 NR → LTE → WCDMA → GSM 选择当前订阅的有效读数。
- 无服务订阅保留无效状态，不复用另一张 SIM 的信号值。
- LineageOS Compose 双卡容器按卡槽排序后同时渲染，不能使用 `firstOrNull()` 只显示默认卡。

## Wi-Fi 数据路径

优先读取当前 SystemUI 状态对象中的 RSSI；当厂商模型不提供原始 RSSI 时，回退到 `NetworkCapabilities`、`WifiInfo`、`WifiManager` 和系统 RSSI 广播的事件驱动路径。实现不使用高频轮询。

## 验证原则

1. 安装模块后确认 Vector/Xposed 作用域只有 `com.android.systemui`。
2. 在 SystemUI 重载后确认启动日志命中的 `mode` 与目标设备一致。
3. 分别检查桌面、锁屏、普通下拉和完全展开通知栏，避免复用的厂商 View 在不同状态重复绘制。
4. 检查双卡读数分别绑定到两张 SIM，不以“数值相同”推断绑定错误；信号值本身可能短时间相同。
5. 出现 SystemUI 崩溃时先取消模块作用域；模块不修改系统分区或厂商 SystemUI APK。
