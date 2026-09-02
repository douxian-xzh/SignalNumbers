package com.xinsu.signalnumbers.xposed

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

class SystemUiModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SYSTEM_UI || lpparam.processName != SYSTEM_UI) return
        runCatching {
            XposedBridge.hookAllMethods(Application::class.java, "attach", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = param.args.firstOrNull() as? Context ?: return
                    if (started.compareAndSet(false, true)) {
                        // Application.attach is on SystemUI's main thread.
                        // Only hand off the bootstrap here; provider access,
                        // reflection and all runtime setup happen off-main.
                        runCatching {
                            Thread {
                                runCatching { RuntimeController.start(context, lpparam.classLoader) }
                                    .onFailure {
                                        started.set(false)
                                        XposedBridge.log("SignalNumbers: bootstrap failed: ${it.stackTraceToString()}")
                                    }
                            }.apply {
                                name = "SignalNumbers-Bootstrap"
                                isDaemon = true
                            }.start()
                        }.onFailure {
                            started.set(false)
                            XposedBridge.log("SignalNumbers: bootstrap thread failed: ${it.stackTraceToString()}")
                        }
                    }
                }
            })
        }.onFailure { XposedBridge.log("SignalNumbers: attach hook failed: ${it.stackTraceToString()}") }
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private val started = AtomicBoolean(false)
    }
}
