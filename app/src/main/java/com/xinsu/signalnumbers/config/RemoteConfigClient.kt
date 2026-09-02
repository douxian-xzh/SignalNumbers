package com.xinsu.signalnumbers.config

import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RemoteConfigClient(
    private val context: Context,
    private val worker: Handler,
    private val executor: Executor,
    private val onChanged: (ModuleConfig) -> Unit,
) {
    @Volatile var current = ModuleConfig()
        private set

    private val observer = object : ContentObserver(worker) {
        override fun onChange(selfChange: Boolean) = scheduleReload()
    }
    private val registered = AtomicBoolean(false)
    private val reloadQueued = AtomicBoolean(false)
    private val failureQueued = AtomicBoolean(false)
    private val pendingLogs = AtomicInteger(0)
    private var loaded = false

    fun start() {
        if (!registered.compareAndSet(false, true)) return
        if (!execute {
            runCatching {
                context.contentResolver.registerContentObserver(ConfigContract.URI, true, observer)
                scheduleReload()
            }.onFailure {
                registered.set(false)
            }
        }) registered.set(false)
    }

    fun stop() {
        execute {
            if (!registered.compareAndSet(true, false)) return@execute
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }

    private fun scheduleReload() {
        if (!reloadQueued.compareAndSet(false, true)) return
        if (!execute {
            try {
                val bundle = context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_GET, null, null)
                val next = ModuleConfig.from(bundle)
                if (!loaded || next != current) {
                    loaded = true
                    current = next
                    onChanged(next)
                }
            } catch (_: Throwable) {
                // Configuration is advisory. Keep the last known value if the
                // settings provider is temporarily unavailable.
            } finally {
                reloadQueued.set(false)
            }
        }) reloadQueued.set(false)
    }

    fun recordFailureAsync() {
        if (!failureQueued.compareAndSet(false, true)) return
        if (!execute {
            try {
                val bundle = context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_FAILURE, null, null)
                val next = ModuleConfig.from(bundle)
                current = next
                onChanged(next)
            } catch (_: Throwable) {
                // Failure reporting must never become another SystemUI fault.
            } finally {
                failureQueued.set(false)
            }
        }) failureQueued.set(false)
    }

    fun log(message: String) {
        if (message.isBlank()) return
        val queued = pendingLogs.incrementAndGet()
        if (queued > MAX_PENDING_LOGS) {
            pendingLogs.decrementAndGet()
            return
        }
        if (!execute {
            try {
                context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_LOG, null, Bundle().apply {
                    putString(ConfigContract.EXTRA_MESSAGE, message.take(1200))
                })
            } catch (_: Throwable) {
                // Logging is best effort and must not affect SystemUI.
            } finally {
                pendingLogs.decrementAndGet()
            }
        }) pendingLogs.decrementAndGet()
    }

    private fun execute(task: () -> Unit): Boolean = runCatching {
        executor.execute(task)
    }.isSuccess

    companion object {
        private const val MAX_PENDING_LOGS = 32
    }
}
