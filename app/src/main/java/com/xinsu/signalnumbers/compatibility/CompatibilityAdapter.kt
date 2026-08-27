package com.xinsu.signalnumbers.compatibility

enum class ViewRole { STATUS_ROOT, MOBILE, WIFI }

/** Runtime profile selected from the actual SystemUI host device. */
enum class CompatibilityMode(val id: String) {
    AOSP("aosp"),
    PJZ110_LINEAGE("pjz110-lineage"),
    XIAOMI_HYPEROS3("xiaomi-hyperos3"),
}

data class HookPoint(
    val className: String,
    val methods: List<String>,
    val role: ViewRole,
    val subscriptionFields: List<String> = emptyList(),
)

data class WifiStateHookPoint(
    val className: String,
    val methods: List<String>,
    val stateFields: List<String>,
    val rssiFields: List<String> = listOf("rssi", "mRssi"),
)

interface CompatibilityAdapter {
    val name: String
    /** Explicit mode guard used by device-specific behavior and startup diagnostics. */
    val mode: CompatibilityMode get() = CompatibilityMode.AOSP
    val mobileResourceNames: Set<String>
    /** Original vendor network-type views hidden while the replacement text is rendered. */
    val mobileTypeResourceNames: Set<String> get() = setOf("mobile_type_container")
    /** Vendor data-activity indicators that occupy space beside the mobile signal. */
    val mobileActivityResourceNames: Set<String> get() = emptySet()
    val mobileGroupResourceNames: Set<String>
    val wifiResourceNames: Set<String>
    val wifiGroupResourceNames: Set<String>
    val hookPoints: List<HookPoint>
    val wifiStateHookPoints: List<WifiStateHookPoint>
    val composeMobileCreatorHookPoints: List<Pair<String, String>> get() = emptyList()
    /** Whether the duplicate cellular/Wi-Fi row below the status bar is hidden when the shade is fully expanded. */
    val hideExpandedShadeSignalRow: Boolean get() = true
    /** Whether the duplicate row is allowed to remain visible only during a fully expanded shade. */
    val showExpandedShadeSignalRowOnlyWhenExpanded: Boolean get() = false
    /** Whether a shade carrier row reused by the lockscreen is hidden while keyguard is showing. */
    val hideExpandedShadeSignalRowOnKeyguard: Boolean get() = false
    /** Whether traditional top-bar mobile views are hidden when a stacked Compose mobile view is active. */
    val hideTraditionalMobileViewsWhenCompose: Boolean get() = false
    /** Whether injected signal text is forced white while the regular shade is fully expanded. */
    val forceWhiteInExpandedShade: Boolean get() = true
    /** Whether injected signal text is forced white while the keyguard is showing. */
    val forceWhiteOnKeyguard: Boolean get() = true
    /** Traditional SystemUI View classes whose appearance tint can be shared with Compose/shade fallbacks. */
    val appearanceTintSourceClassNames: Map<ViewRole, Set<String>> get() = emptyMap()

    fun isLikelyStatusResource(name: String): Boolean =
        name in mobileResourceNames || name in mobileGroupResourceNames ||
            name in wifiResourceNames || name in wifiGroupResourceNames ||
            name.contains("status_bar", ignoreCase = true)
}
