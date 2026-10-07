package com.xinsu.signalnumbers.compatibility

import android.graphics.Color
import android.widget.ImageView

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
    override val useNativeSignalTint = true
    override val forceWhiteInExpandedShade = false
    override val forceWhiteOnKeyguard = false
    override val forceWhiteInControlCenter = false

    override fun resolveNativeSignalAppearance(view: ImageView, resourceId: Int): NativeSignalAppearance? {
        val name = runCatching { view.resources.getResourceEntryName(resourceId) }.getOrNull() ?: return null
        if (!signalDrawableName.matches(name)) return null
        val usesSystemTint = name.endsWith("_tint")
        val dark = usesSystemTint || name.endsWith("_darkmode")
        val colorName = if (dark) "dark_mode_icon_color_single_tone" else "light_mode_icon_color_single_tone"
        val fallback = if (dark) Color.BLACK else Color.WHITE
        val colorId = view.resources.getIdentifier(colorName, "color", "com.android.systemui")
        val color = if (colorId != 0) {
            runCatching { view.context.getColor(colorId) }.getOrDefault(fallback)
        } else {
            fallback
        }
        return NativeSignalAppearance(
            color = if (usesSystemTint) Color.BLACK else color,
            usesImageTint = usesSystemTint,
            mode = when {
                usesSystemTint -> "tint"
                dark -> "dark"
                else -> "light"
            },
        )
    }

    private companion object {
        val signalDrawableName = Regex(
            "^stat_sys_(?:signal_(?:[0-4](?:_no_voice)?|null)|wifi_signal_(?:[0-3]|unavailable_[0-3]))(?:_darkmode|_tint)?$",
        )
    }

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
