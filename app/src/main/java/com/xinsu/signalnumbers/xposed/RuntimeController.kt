package com.xinsu.signalnumbers.xposed

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.xinsu.signalnumbers.compatibility.CompatibilityRegistry
import com.xinsu.signalnumbers.config.ModuleConfig
import com.xinsu.signalnumbers.config.RemoteConfigClient
import com.xinsu.signalnumbers.injection.ViewInjector
import com.xinsu.signalnumbers.signal.MobileReading
import com.xinsu.signalnumbers.signal.MobileSignalTracker
import com.xinsu.signalnumbers.signal.SignalSnapshot
import com.xinsu.signalnumbers.signal.WifiReading
import com.xinsu.signalnumbers.signal.WifiSignalTracker
import de.robv.android.xposed.XposedBridge

@SuppressLint("StaticFieldLeak") // Application context intentionally lives for the SystemUI process lifetime.
object RuntimeController {
    private lateinit var context: Context
    private lateinit var configClient: RemoteConfigClient
    private lateinit var logger: ModuleLogger
    private lateinit var injector: ViewInjector
    private lateinit var mobileTracker: MobileSignalTracker
    private lateinit var wifiTracker: WifiSignalTracker
    private val main = Handler(Looper.getMainLooper())
    private var snapshot = SignalSnapshot()
    private var safeModeReported = false

    fun start(systemUiContext: Context, classLoader: ClassLoader) {
        // applicationContext can still be null while Application.attach() is running.
        context = systemUiContext.applicationContext ?: systemUiContext
        XposedBridge.log("SignalNumbers: bootstrap context ready (${context.javaClass.name})")
        configClient = RemoteConfigClient(context, ::onConfigChanged)
        logger = ModuleLogger(configClient)
        val compatibility = CompatibilityRegistry.select()
        injector = ViewInjector(compatibility, { message ->
            logger.info("view-${message.hashCode()}", message, 750L)
        }, ::reportError)
        mobileTracker = MobileSignalTracker(context, ::onMobileChanged, ::reportError)
        wifiTracker = WifiSignalTracker(context, ::onWifiChanged, ::reportError)

        configClient.start()
        HookInstaller(
            classLoader,
            compatibility,
            injector,
            wifiTracker::acceptSystemUiRssi,
            { key, message -> logger.info(key, message, 1_000L) },
            ::reportError,
        ).install()
        registerScreenReceiver()
        mobileTracker.start()
        wifiTracker.start()
        logger.info(
            "startup",
            "Started with ${compatibility.name}; mode=${compatibility.mode.id}; ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}",
            0,
        )
    }

    private fun onConfigChanged(config: ModuleConfig) = main.post {
        injector.updateConfig(config)
        if (config.safeMode && !safeModeReported) {
            safeModeReported = true
            logger.info("safe-mode", "Replacement suspended until ${config.safeUntil}", 0)
        } else if (!config.safeMode) safeModeReported = false
    }

    private fun onMobileChanged(readings: Map<Int, MobileReading>) = main.post {
        logger.info("mobile-reading", readings.values.joinToString { "sub=${it.subscriptionId}/slot=${it.slotIndex}:${it.dbm}/${it.radioFamily}/service=${it.inService}" }, 5_000L)
        snapshot = snapshot.copy(
            mobileBySubscription = readings,
            mobileBySlot = readings.values.associateBy { it.slotIndex },
        )
        injector.updateSignals(snapshot)
    }

    private fun onWifiChanged(reading: WifiReading) = main.post {
        logger.info("wifi-reading", "connected=${reading.connected} rssi=${reading.rssi}", 5_000L)
        snapshot = snapshot.copy(wifi = reading)
        injector.updateSignals(snapshot)
    }

    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON || intent?.action == Intent.ACTION_USER_PRESENT) {
                    main.post {
                        mobileTracker.refreshAfterScreenOn()
                        wifiTracker.refresh()
                        injector.updateSignals(snapshot)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
    }

    private fun reportError(throwable: Throwable) {
        if (!::logger.isInitialized) return
        logger.error("runtime", throwable)
        if (::configClient.isInitialized) {
            val config = configClient.recordFailure()
            if (config.safeMode && ::injector.isInitialized) main.post { injector.restoreAll() }
        }
    }
}
