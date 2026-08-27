package com.xinsu.signalnumbers.compatibility

open class AospCompatibility : CompatibilityAdapter {
    override val name = "AOSP"
    override val mobileResourceNames = setOf("mobile_signal", "mobile_icon")
    override val mobileGroupResourceNames = setOf("mobile_signal_group", "mobile_group", "status_bar_mobile")
    override val wifiResourceNames = setOf("wifi_signal", "status_bar_wifi")
    override val wifiGroupResourceNames = setOf("wifi_group", "status_bar_wifi_group")
    override val wifiStateHookPoints = listOf(
        WifiStateHookPoint(
            "com.android.systemui.statusbar.connectivity.WifiSignalController",
            listOf("notifyListeners", "handleBroadcast"),
            listOf("mCurrentState", "currentState"),
        ),
    )
    override val hookPoints = listOf(
        HookPoint(
            "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView",
            listOf("constructAndBind", "onAttachedToWindow", "onConfigurationChanged"),
            ViewRole.MOBILE,
            listOf("subId", "subscriptionId"),
        ),
        HookPoint(
            "com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView",
            listOf("constructAndBind", "onAttachedToWindow", "onConfigurationChanged"),
            ViewRole.WIFI,
        ),
        HookPoint(
            "com.android.systemui.statusbar.StatusBarMobileView",
            listOf("fromContext", "applyMobileState", "onAttachedToWindow"),
            ViewRole.MOBILE,
            listOf("mSubId", "subId", "subscriptionId"),
        ),
        HookPoint(
            "com.android.systemui.statusbar.StatusBarWifiView",
            listOf("fromContext", "applyWifiState", "onAttachedToWindow"),
            ViewRole.WIFI,
        ),
        HookPoint(
            "com.android.systemui.statusbar.phone.PhoneStatusBarView",
            listOf("onFinishInflate", "onAttachedToWindow", "onConfigurationChanged"),
            ViewRole.STATUS_ROOT,
        ),
        HookPoint(
            "com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment",
            listOf("onViewCreated", "onResume"),
            ViewRole.STATUS_ROOT,
        ),
    )
}
