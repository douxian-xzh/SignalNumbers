package com.xinsu.signalnumbers.signal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler

/** Delivers the current battery percentage to the SystemUI-side renderer. */
class BatteryLevelTracker(
    private val context: Context,
    private val onChanged: (Int?) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val callbackHandler: Handler,
) {
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private var registered = false
    private var percent: Int? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            publish(readPercent(intent))
        }
    }

    fun start() = guarded {
        if (registered) return@guarded
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, null, callbackHandler, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter, null, callbackHandler)
        }
        registered = true
        refresh()
    }

    fun refresh() = guarded {
        val value = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
        publish(value)
    }

    fun stop() = guarded {
        if (!registered) return@guarded
        context.unregisterReceiver(receiver)
        registered = false
    }

    private fun readPercent(intent: Intent?): Int? {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            (level * 100 / scale).coerceIn(0, 100)
        } else {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .takeIf { it in 0..100 }
        }
    }

    private fun publish(value: Int?) {
        if (value == percent) return
        percent = value
        onChanged(value)
    }

    private inline fun guarded(block: () -> Unit) {
        runCatching(block).onFailure(onError)
    }
}
