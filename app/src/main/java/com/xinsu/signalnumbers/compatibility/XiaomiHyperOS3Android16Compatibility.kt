package com.xinsu.signalnumbers.compatibility

/**
 * Xiaomi/Redmi HyperOS 3 on Android 16.
 *
 * The device SystemUI keeps the AOSP modern signal pipeline, but its layouts
 * and status-bar recreation path use Xiaomi-specific resource names/classes.
 * Keep those names here so the injector remains vendor-neutral.
 */
class XiaomiHyperOS3Android16Compatibility : AospCompatibility() {
    override val name = "Xiaomi HyperOS 3 / Android 16"
    override val mode = CompatibilityMode.XIAOMI_HYPEROS3

    override val mobileTypeResourceNames = setOf(
        "mobile_type_container",
        "mobile_type",
        "mobile_type_single",
    )

    override val mobileActivityResourceNames = setOf(
        "mobile_left_mobile_inout",
    )

    override val mobileGroupResourceNames = super.mobileGroupResourceNames + setOf(
        "mobile_signal_group",
        "status_bar_mobile_signal_group_inner",
        "status_bar_mobile_signal_group_new",
    )

    override val wifiGroupResourceNames = super.wifiGroupResourceNames + setOf(
        "new_status_bar_wifi_group",
        "status_bar_wifi_group_inner",
    )

    override val hookPoints = listOf(
        HookPoint(
            "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView",
            listOf("constructAndBind", "onAttachedToWindow", "onConfigurationChanged"),
            ViewRole.MOBILE,
            listOf("subId"),
        ),
        HookPoint(
            "com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView",
            listOf("constructAndBind", "onAttachedToWindow", "onConfigurationChanged"),
            ViewRole.WIFI,
        ),
        HookPoint(
            "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView",
            listOf("onFinishInflate", "onAttachedToWindow", "onConfigurationChanged"),
            ViewRole.STATUS_ROOT,
        ),
        HookPoint(
            "com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment",
            listOf("onViewCreated", "onConfigurationChanged"),
            ViewRole.STATUS_ROOT,
        ),
    ) + super.hookPoints
}
