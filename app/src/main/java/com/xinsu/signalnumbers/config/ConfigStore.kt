package com.xinsu.signalnumbers.config

import android.content.Context
import android.content.SharedPreferences

object ConfigStore {
    private const val PREFS = "module_config"

    fun prefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): ModuleConfig {
        val p = prefs(context)
        if (p.getInt(KEY_SCHEMA, 0) < SCHEMA_109) {
            p.edit().putFloat(Keys.FONT_SIZE, 14f).putInt(KEY_SCHEMA, SCHEMA_109).commit()
        }
        return ModuleConfig(
            enabled = p.getBoolean(Keys.ENABLED, true),
            mobileEnabled = p.getBoolean(Keys.MOBILE, true),
            wifiEnabled = p.getBoolean(Keys.WIFI, true),
            showMinus = p.getBoolean(Keys.MINUS, true),
            fontSizeSp = p.getFloat(Keys.FONT_SIZE, 14f),
            bold = p.getBoolean(Keys.BOLD, true),
            unitMode = p.getInt(Keys.UNIT, ModuleConfig.UNIT_NONE),
            sim1Enabled = p.getBoolean(Keys.SIM1, true),
            sim2Enabled = p.getBoolean(Keys.SIM2, true),
            noServiceMode = p.getInt(Keys.NO_SERVICE, ModuleConfig.EMPTY_CROSS),
            wifiDisconnectedMode = p.getInt(Keys.WIFI_DISCONNECTED, ModuleConfig.EMPTY_HIDE),
            safeUntil = p.getLong(Keys.SAFE_UNTIL, 0L),
        )
    }

    private const val KEY_SCHEMA = "config_schema"
    private const val SCHEMA_109 = 109

    fun notifyChanged(context: Context) {
        context.contentResolver.notifyChange(ConfigContract.URI, null)
    }
}
