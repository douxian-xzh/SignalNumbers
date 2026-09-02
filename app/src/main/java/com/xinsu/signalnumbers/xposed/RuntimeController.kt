package com.xinsu.signalnumbers.xposed

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.xinsu.signalnumbers.compatibility.CompatibilityRegistry
import com.xinsu.signalnumbers.config.ModuleConfig
import com.xinsu.signalnumbers.config.RemoteConfigClient
import com.xinsu.signalnumbers.injection.ViewInjector
import com.xinsu.signalnumbers.signal.BatteryLevelTracker
import com.xinsu.signalnumbers.signal.MobileReading
import com.xinsu.signalnumbers.signal.MobileSignalTracker
import com.xinsu.signalnumbers.signal.SignalSnapshot
import com.xinsu.signalnumbers.signal.WifiReading
import com.xinsu.signalnumbers.signal.WifiSignalTracker
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("StaticFieldLeak") // Application context intentionally lives for the SystemUI process lifetime.
object RuntimeController {
    private lateinit var context: Context
    private lateinit var configClient: RemoteConfigClient
    private lateinit var logger: ModuleLogger
    private lateinit var injector: ViewInjector
    private lateinit var mobileTracker: MobileSignalTracker
    private lateinit var wifiTracker: WifiSignalTracker
    private lateinit var batteryTracker: BatteryLevelTracker
    private lateinit var hookInstaller: HookInstaller

    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("SignalNumbers-Worker", Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var worker: Handler
    private lateinit var workerExecutor: Executor
    private val started = AtomicBoolean(false)
    private val runtimeWorkStarted = AtomicBoolean(false)
    private val screenReceiverRegistered = AtomicBoolean(false)
    private val screenRefreshQueued = AtomicBoolean(false)
    private val uiRefreshQueued = AtomicBoolean(false)
    private val snapshotLock = Any()
    private val lastFailureAt = AtomicLong(0L)
    @Volatile private var hookActive = false
    private var snapshot = SignalSnapshot()
    private var pendingSnapshot = snapshot
    private var safeModeReported = false

    /**
     * Screen events are delivered on the worker Handler. SCREEN_OFF is not
     * needed: trackers already suppress updates while the display is off, and
     * registering it only adds a SystemUI broadcast callback with no work.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT,
                -> requestScreenRefresh()
            }
        }
    }

    fun start(systemUiContext: Context, classLoader: ClassLoader) {
        if (!started.compareAndSet(false, true)) return
        try {
            context = systemUiContext.applicationContext ?: systemUiContext
            workerThread.start()
            worker = Handler(workerThread.looper)
            workerExecutor = Executor { task -> worker.post(task) }

            XposedBridge.log("SignalNumbers: bootstrap context ready (${context.javaClass.name})")
            configClient = RemoteConfigClient(context, worker, workerExecutor, ::onConfigChanged)
            logger = ModuleLogger(configClient)
            val compatibility = CompatibilityRegistry.select()
            injector = ViewInjector(compatibility, { message ->
                logger.info("view-${message.hashCode()}", message, 750L)
            }, ::reportError)
            mobileTracker = MobileSignalTracker(context, ::onMobileChanged, ::reportError, workerExecutor)
            wifiTracker = WifiSignalTracker(context, ::onWifiChanged, ::reportError, worker)
            batteryTracker = BatteryLevelTracker(context, ::onBatteryChanged, ::reportError, worker)
            hookInstaller = HookInstaller(
                classLoader = classLoader,
                compatibility = compatibility,
                injector = injector,
                onSystemUiWifiRssi = wifiTracker::acceptSystemUiRssi,
                onEvent = { key, message -> logger.info(key, message, 1_000L) },
                onError = ::reportError,
                isHookEnabled = ::isHookActive,
                worker = worker,
            )

            // The initial provider read and all subsequent config changes are
            // asynchronous. Until a config is loaded, the hook stays off so a
            // slow or unavailable settings provider cannot stall SystemUI.
            configClient.start()
        } catch (throwable: Throwable) {
            hookActive = false
            XposedBridge.log("SignalNumbers: bootstrap failed: ${throwable.stackTraceToString()}")
        }
    }

    private fun onConfigChanged(config: ModuleConfig) {
        main.post {
            if (!::injector.isInitialized) return@post
            hookActive = config.systemUiHookEnabled && config.enabled && !config.safeMode
            injector.setHookEnabled(hookActive)
            injector.updateConfig(config)
            if (config.safeMode && !safeModeReported) {
                safeModeReported = true
                logger.info("safe-mode", "Replacement suspended until ${config.safeUntil}", 0)
            } else if (!config.safeMode) {
                safeModeReported = false
            }
            if (::worker.isInitialized) {
                val active = hookActive
                worker.post { reconcileRuntime(active) }
            }
            if (hookActive) requestUiRefresh()
        }
    }

    private fun reconcileRuntime(active: Boolean) {
        if (!active) {
            stopRuntimeWork()
            return
        }
        if (!isHookActive()) return
        hookInstaller.install()
        if (isHookActive()) startRuntimeWork()
    }

    private fun startRuntimeWork() {
        if (!runtimeWorkStarted.compareAndSet(false, true)) return
        registerScreenReceiver()
        mobileTracker.start()
        wifiTracker.start()
        batteryTracker.start()
        logger.info(
            "startup",
            "Started with ${hookInstaller.javaClass.simpleName}; hook=enabled",
            0,
        )
    }

    private fun stopRuntimeWork() {
        if (!runtimeWorkStarted.compareAndSet(true, false)) return
        unregisterScreenReceiver()
        mobileTracker.stop()
        wifiTracker.stop()
        batteryTracker.stop()
        screenRefreshQueued.set(false)
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered.get()) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                    screenReceiver,
                    filter,
                    null,
                    worker,
                    Context.RECEIVER_EXPORTED,
                )
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(screenReceiver, filter, null, worker)
            }
            screenReceiverRegistered.set(true)
        }.onFailure(::reportError)
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered.getAndSet(false)) return
        runCatching { context.unregisterReceiver(screenReceiver) }.onFailure(::reportError)
    }

    private fun requestScreenRefresh() {
        if (!isHookActive() || !screenRefreshQueued.compareAndSet(false, true)) return
        if (!worker.post {
                try {
                    if (isHookActive()) {
                        mobileTracker.refreshAfterScreenOn()
                        wifiTracker.refresh()
                        batteryTracker.refresh()
                        requestUiRefresh()
                    }
                } catch (throwable: Throwable) {
                    reportError(throwable)
                } finally {
                    screenRefreshQueued.set(false)
                }
            }
        ) {
            screenRefreshQueued.set(false)
        }
    }

    private fun onMobileChanged(readings: Map<Int, MobileReading>) {
        if (!isHookActive()) return
        logger.info(
            "mobile-reading",
            readings.values.joinToString {
                "sub=${it.subscriptionId}/slot=${it.slotIndex}:${it.dbm}/${it.radioFamily}/service=${it.inService}"
            },
            5_000L,
        )
        synchronized(snapshotLock) {
            pendingSnapshot = pendingSnapshot.copy(
                mobileBySubscription = readings,
                mobileBySlot = readings.values.associateBy { it.slotIndex },
            )
        }
        requestUiRefresh()
    }

    private fun onWifiChanged(reading: WifiReading) {
        if (!isHookActive()) return
        logger.info("wifi-reading", "connected=${reading.connected} rssi=${reading.rssi}", 5_000L)
        synchronized(snapshotLock) {
            pendingSnapshot = pendingSnapshot.copy(wifi = reading)
        }
        requestUiRefresh()
    }

    private fun onBatteryChanged(percent: Int?) {
        if (!isHookActive()) return
        logger.info("battery-reading", "percent=$percent", 5_000L)
        synchronized(snapshotLock) {
            pendingSnapshot = pendingSnapshot.copy(batteryPercent = percent)
        }
        requestUiRefresh()
    }

    private fun requestUiRefresh() {
        if (!isHookActive() || !uiRefreshQueued.compareAndSet(false, true)) return
        if (!main.post {
                val next = synchronized(snapshotLock) { pendingSnapshot }
                try {
                    if (isHookActive()) {
                        snapshot = next
                        injector.updateSignals(next)
                    }
                } finally {
                    uiRefreshQueued.set(false)
                    val changed = synchronized(snapshotLock) { pendingSnapshot != snapshot }
                    if (changed && isHookActive()) requestUiRefresh()
                }
            }) uiRefreshQueued.set(false)
    }

    private fun isHookActive(): Boolean = hookActive

    private fun reportError(throwable: Throwable) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFailureAt.getAndSet(now) < 1_000L) return
        val report = {
            if (::logger.isInitialized) logger.error("runtime", throwable)
            if (::configClient.isInitialized) configClient.recordFailureAsync()
        }
        if (::worker.isInitialized && worker.post(report)) return
        // This is only a bootstrap fallback. Normal runtime errors always use
        // the worker so stack-trace formatting and failure persistence cannot
        // consume a SystemUI callback or draw frame.
        XposedBridge.log("SignalNumbers: runtime error: ${throwable.javaClass.simpleName}: ${throwable.message}")
    }
}
