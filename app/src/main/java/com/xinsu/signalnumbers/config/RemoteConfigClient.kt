package com.xinsu.signalnumbers.config

import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class RemoteConfigClient(private val context: Context, private val onChanged: (ModuleConfig) -> Unit) {
    @Volatile var current = ModuleConfig()
        private set

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload()
    }

    fun start() {
        context.contentResolver.registerContentObserver(ConfigContract.URI, true, observer)
        reload()
    }

    fun reload() {
        runCatching {
            val bundle = context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_GET, null, null)
            current = ModuleConfig.from(bundle)
            onChanged(current)
        }
    }

    fun recordFailure(): ModuleConfig {
        return runCatching {
            val bundle = context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_FAILURE, null, null)
            ModuleConfig.from(bundle).also { current = it }
        }.getOrDefault(current)
    }

    fun log(message: String) {
        runCatching {
            context.contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_LOG, null, Bundle().apply {
                putString(ConfigContract.EXTRA_MESSAGE, message.take(1200))
            })
        }
    }
}
