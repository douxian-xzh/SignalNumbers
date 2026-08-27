package com.xinsu.signalnumbers.config

import android.os.Bundle

data class ModuleConfig(
    val enabled: Boolean = true,
    val mobileEnabled: Boolean = true,
    val wifiEnabled: Boolean = true,
    val showMinus: Boolean = true,
    val fontSizeSp: Float = 14f,
    val bold: Boolean = true,
    val unitMode: Int = UNIT_NONE,
    val sim1Enabled: Boolean = true,
    val sim2Enabled: Boolean = true,
    val noServiceMode: Int = EMPTY_CROSS,
    val wifiDisconnectedMode: Int = EMPTY_HIDE,
    val safeUntil: Long = 0L,
) {
    val safeMode: Boolean get() = safeUntil > System.currentTimeMillis()

    fun toBundle() = Bundle().apply {
        putBoolean(Keys.ENABLED, enabled)
        putBoolean(Keys.MOBILE, mobileEnabled)
        putBoolean(Keys.WIFI, wifiEnabled)
        putBoolean(Keys.MINUS, showMinus)
        putFloat(Keys.FONT_SIZE, fontSizeSp)
        putBoolean(Keys.BOLD, bold)
        putInt(Keys.UNIT, unitMode)
        putBoolean(Keys.SIM1, sim1Enabled)
        putBoolean(Keys.SIM2, sim2Enabled)
        putInt(Keys.NO_SERVICE, noServiceMode)
        putInt(Keys.WIFI_DISCONNECTED, wifiDisconnectedMode)
        putLong(Keys.SAFE_UNTIL, safeUntil)
    }

    companion object {
        const val UNIT_NONE = 0
        const val UNIT_DBM = 1
        const val EMPTY_HIDE = 0
        const val EMPTY_CROSS = 1
        const val EMPTY_DASH = 2

        fun from(bundle: Bundle?) = if (bundle == null) ModuleConfig() else ModuleConfig(
            enabled = bundle.getBoolean(Keys.ENABLED, true),
            mobileEnabled = bundle.getBoolean(Keys.MOBILE, true),
            wifiEnabled = bundle.getBoolean(Keys.WIFI, true),
            showMinus = bundle.getBoolean(Keys.MINUS, true),
            fontSizeSp = bundle.getFloat(Keys.FONT_SIZE, 14f).coerceIn(7f, 14f),
            bold = bundle.getBoolean(Keys.BOLD, true),
            unitMode = bundle.getInt(Keys.UNIT, UNIT_NONE),
            sim1Enabled = bundle.getBoolean(Keys.SIM1, true),
            sim2Enabled = bundle.getBoolean(Keys.SIM2, true),
            noServiceMode = bundle.getInt(Keys.NO_SERVICE, EMPTY_CROSS),
            wifiDisconnectedMode = bundle.getInt(Keys.WIFI_DISCONNECTED, EMPTY_HIDE),
            safeUntil = bundle.getLong(Keys.SAFE_UNTIL, 0L),
        )
    }
}

object Keys {
    const val ENABLED = "enabled"
    const val MOBILE = "mobile_enabled"
    const val WIFI = "wifi_enabled"
    const val MINUS = "show_minus"
    const val FONT_SIZE = "font_size"
    const val BOLD = "bold"
    const val UNIT = "unit_mode"
    const val SIM1 = "sim1_enabled"
    const val SIM2 = "sim2_enabled"
    const val NO_SERVICE = "no_service_mode"
    const val WIFI_DISCONNECTED = "wifi_disconnected_mode"
    const val SAFE_UNTIL = "safe_until"
}
