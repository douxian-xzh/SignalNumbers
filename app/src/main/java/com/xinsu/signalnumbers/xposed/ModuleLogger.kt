package com.xinsu.signalnumbers.xposed

import com.xinsu.signalnumbers.config.RemoteConfigClient
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

class ModuleLogger(private val configClient: RemoteConfigClient) {
    private val lastByKey = ConcurrentHashMap<String, Long>()

    fun info(key: String, message: String, intervalMs: Long = 30_000L) {
        val now = System.currentTimeMillis()
        val previous = lastByKey[key] ?: 0L
        if (now - previous < intervalMs) return
        lastByKey[key] = now
        val line = "SignalNumbers [$key] $message"
        XposedBridge.log(line)
        configClient.log(line)
    }

    fun error(key: String, throwable: Throwable) {
        info(key, "${throwable.javaClass.simpleName}: ${throwable.message}\n${throwable.stackTrace.take(8).joinToString("\n")}", 10_000L)
    }
}
