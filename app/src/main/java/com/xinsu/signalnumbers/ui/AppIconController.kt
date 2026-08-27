package com.xinsu.signalnumbers.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object AppIconController {
    private const val LAUNCHER_ALIAS = "com.xinsu.signalnumbers.LauncherAlias"

    fun isHidden(context: Context): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(component(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(false)

    fun setHidden(context: Context, hidden: Boolean): Boolean = runCatching {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        true
    }.getOrDefault(false)

    private fun component(context: Context) = ComponentName(context, LAUNCHER_ALIAS)
}
