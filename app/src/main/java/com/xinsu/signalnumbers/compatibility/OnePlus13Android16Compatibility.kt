package com.xinsu.signalnumbers.compatibility

/**
 * PJZ110 / Android 16 profile verified against the device SystemUI APK.
 * OEM names live here so the injection and signal layers stay vendor-neutral.
 */
class OnePlus13Android16Compatibility : AospCompatibility() {
    override val name = "OnePlus 13 / Android 16"
    override val mode = CompatibilityMode.PJZ110_LINEAGE
    override val hideExpandedShadeSignalRow = false
    override val showExpandedShadeSignalRowOnlyWhenExpanded = true
    override val hideExpandedShadeSignalRowOnKeyguard = true
    override val hideTraditionalMobileViewsWhenCompose = true
    override val forceWhiteInExpandedShade = false
    override val forceWhiteOnKeyguard = false
    override val appearanceTintSourceClassNames = mapOf(
        ViewRole.MOBILE to setOf(
            "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView",
        ),
        ViewRole.WIFI to setOf(
            "com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView",
        ),
    )

    override val composeMobileCreatorHookPoints = listOf(
        "com.android.systemui.statusbar.pipeline.mobile.ui.StackedMobileBindableIcon\$initializer\$1" to "createAndBind",
    )

    override val mobileResourceNames = super.mobileResourceNames + setOf(
        "mobile_signal",
    )
    override val mobileGroupResourceNames = super.mobileGroupResourceNames + setOf(
        "mobile_signal_group",
        "status_bar_mobile_signal_group_inner",
        "status_bar_mobile_signal_group_new",
    )
    override val wifiResourceNames = super.wifiResourceNames + setOf(
        "wifi_signal",
        "wifi_signal_spacer",
    )
    override val wifiGroupResourceNames = super.wifiGroupResourceNames + setOf(
        "new_status_bar_wifi_group",
        "status_bar_wifi_group_inner",
    )

    override val hookPoints = listOf(
        HookPoint(
            "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView",
            listOf("constructAndBind", "onAttachedToWindow", "onConfigurationChanged", "configureLayoutForNewStatusBarIcons"),
            ViewRole.MOBILE,
            listOf("subId"),
        ),
        HookPoint(
            "com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView",
            listOf("constructAndBind", "onAttachedToWindow", "onConfigurationChanged", "updateDimensions"),
            ViewRole.WIFI,
        ),
    ) + super.hookPoints
}
