package com.xinsu.signalnumbers.xposed

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import com.xinsu.signalnumbers.compatibility.CompatibilityAdapter
import com.xinsu.signalnumbers.compatibility.HookPoint
import com.xinsu.signalnumbers.compatibility.ViewRole
import com.xinsu.signalnumbers.compatibility.WifiStateHookPoint
import com.xinsu.signalnumbers.injection.ViewInjector
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class HookInstaller(
    private val classLoader: ClassLoader,
    private val compatibility: CompatibilityAdapter,
    private val injector: ViewInjector,
    private val onSystemUiWifiRssi: (Int) -> Unit,
    private val onEvent: (String, String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var shadeExpansionMirrorsInstalled = false
    @Volatile
    private var shadeViewMirrorInstalled = false
    @Volatile
    private var controlCenterExpansionMirrorsInstalled = false
    @Volatile
    private var shadeStateManagerMirrorInstalled = false
    private var shadeExpansionRetryCount = 0
    private var shadeStateManagerRetryCount = 0
    private val shadeClassName = "com.android.systemui.shade.NotificationPanelViewController"
    private val shadeViewClassName = "com.android.systemui.shade.NotificationPanelView"
    private val shadeStateManagerClassName = "com.android.systemui.shade.ShadeExpansionStateManager"
    private val controlCenterClassName = "com.miui.systemui.controlcenter.container.ControlCenterExpandControllerDelegate"

    fun install() {
        compatibility.hookPoints.distinct().forEach(::installPoint)
        compatibility.wifiStateHookPoints.distinct().forEach(::installWifiStatePoint)
        installLayoutFallback()
        installComposeMobileFallback()
        installAppearanceMirrors()
        installKeyguardAppearanceMirrors()
        installKeyguardStateMirrors()
        installShadeClassLoadMirror()
        installShadeViewMirror()
        installShadeExpansionMirrors()
        installShadeStateManagerMirror()
        installControlCenterExpansionMirrors()
    }

    private fun installComposeMobileFallback() {
        compatibility.composeMobileCreatorHookPoints.forEach { (className, method) ->
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
            guarded {
                val hooks = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) = guarded {
                        val root = param.getResult() as? ViewGroup ?: return@guarded
                        onEvent("compose-mobile", "$className#$method root=${root.javaClass.name}")
                        val action = Runnable { guarded { injector.injectComposeMobile(root) } }
                        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else main.post(action)
                    }
                })
                if (hooks.isNotEmpty()) onEvent("hook-installed", "$className#$method count=${hooks.size}")
            }
        }
    }

    private fun installWifiStatePoint(point: WifiStateHookPoint) {
        val clazz = XposedHelpers.findClassIfExists(point.className, classLoader) ?: return
        point.methods.forEach { method ->
            guarded {
                XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) = guarded {
                        val state = point.stateFields.firstNotNullOfOrNull { field ->
                            runCatching { XposedHelpers.getObjectField(param.thisObject, field) }.getOrNull()
                        } ?: return@guarded
                        val rssi = point.rssiFields.firstNotNullOfOrNull { field ->
                            runCatching { XposedHelpers.getIntField(state, field) }.getOrNull()
                        } ?: return@guarded
                        if (rssi in -126..-1) onSystemUiWifiRssi(rssi)
                    }
                })
            }
        }
    }

    private fun installPoint(point: HookPoint) {
        val clazz = XposedHelpers.findClassIfExists(point.className, classLoader) ?: return
        point.methods.distinct().forEach { method ->
            guarded {
                val hooks = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) = guarded {
                        val root = findView(param) ?: return@guarded
                        tryInstallShadeExpansionMirrors(root.javaClass.classLoader ?: classLoader)
                        tryInstallControlCenterExpansionMirrors(root.javaClass.classLoader ?: classLoader)
                        val subId = readIntField(param.getResult() ?: param.thisObject, point.subscriptionFields)
                        onEvent("hook-callback", "${point.className}#$method root=${root.javaClass.name} subId=$subId thread=${Thread.currentThread().name}")
                        dispatchScan(root, point.role.takeUnless { it == ViewRole.STATUS_ROOT }, subId)
                    }
                })
                if (hooks.isNotEmpty()) onEvent("hook-installed", "${point.className}#$method count=${hooks.size}")
            }
        }
    }

    private fun installLayoutFallback() = guarded {
        XposedBridge.hookAllMethods(LayoutInflater::class.java, "inflate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = guarded {
                val root = param.getResult() as? View ?: return@guarded
                val resourceId = param.args.firstOrNull() as? Int ?: return@guarded
                val name = runCatching { root.resources.getResourceEntryName(resourceId) }.getOrDefault("")
                if (compatibility.isLikelyStatusResource(name)) {
                    onEvent("layout-fallback", "$name root=${root.javaClass.name} thread=${Thread.currentThread().name}")
                    dispatchScan(root, null, -1)
                }
            }
        })
    }

    private fun dispatchScan(root: View, role: ViewRole?, subId: Int) {
        tryInstallShadeExpansionMirrors(root.javaClass.classLoader ?: classLoader)
        tryInstallControlCenterExpansionMirrors(root.javaClass.classLoader ?: classLoader)
        findShadeView(root)?.let { installShadeViewMirrors(it.javaClass.classLoader ?: classLoader) }
        val action = Runnable { guarded { injector.scanAndInject(root, role, subId) } }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else main.post(action)
    }

    private fun installAppearanceMirrors() {
        guarded {
            XposedBridge.hookAllMethods(ImageView::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    val image = param.thisObject as? ImageView ?: return@guarded
                    tryInstallShadeExpansionMirrors(image.rootView.javaClass.classLoader ?: classLoader)
                    tryInstallControlCenterExpansionMirrors(image.rootView.javaClass.classLoader ?: classLoader)
                    val action = Runnable { guarded { injector.injectKnownImage(image) } }
                    if (Looper.myLooper() == Looper.getMainLooper()) action.run() else main.post(action)
                }
            })
        }
        guarded {
            XposedBridge.hookAllMethods(View::class.java, "setVisibility", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    injector.onOriginalVisibilityChanged(param.thisObject as? View ?: return@guarded)
                }
            })
        }
        guarded {
            XposedBridge.hookAllMethods(View::class.java, "setAlpha", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    injector.onOriginalAlphaChanged(param.thisObject as? View ?: return@guarded)
                }
            })
        }
        val modernView = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView",
            classLoader,
        )
        if (modernView != null) {
            listOf("setStaticDrawableColor", "setDecorColor", "onDarkChangedWithContrast", "setVisibleState").forEach { method ->
                guarded {
                    XposedBridge.hookAllMethods(modernView, method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) = guarded {
                            val view = param.thisObject as? ViewGroup ?: return@guarded
                            val tint = param.args.firstOrNull { it is Int } as? Int
                            injector.onAppearanceChanged(view, tint)
                        }
                    })
                }
            }
        }
        guarded {
            XposedBridge.hookAllMethods(ImageView::class.java, "setImageTintList", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    injector.onOriginalTintChanged(param.thisObject as? ImageView ?: return@guarded)
                }
            })
        }
    }

    private fun installKeyguardAppearanceMirrors() {
        compatibility.hookPoints
            .filter { it.role == ViewRole.STATUS_ROOT }
            .map { it.className }
            .distinct()
            .forEach { className ->
                val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
                guarded {
                    val hooks = XposedBridge.hookAllMethods(clazz, "onDraw", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) = guarded {
                            val root = param.thisObject as? ViewGroup ?: return@guarded
                            injector.onStatusRootDraw(root)
                        }
                    })
                    if (hooks.isNotEmpty()) onEvent("hook-installed", "$className#onDraw count=${hooks.size}")
                }
            }
    }

    private fun installKeyguardStateMirrors() {
        val targets = listOf(
            "com.android.systemui.statusbar.StatusBarStateControllerImpl" to listOf("setState", "setStateInternal"),
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl" to listOf(
                "setKeyguardShowing",
                "notifyKeyguardState",
            ),
        )
        targets.forEach { (className, methods) ->
            val clazz = runCatching { Class.forName(className, false, classLoader) }.getOrNull() ?: return@forEach
            methods.forEach { method ->
                guarded {
                    val hooks = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) = guarded {
                            tryInstallShadeExpansionMirrors(param.thisObject.javaClass.classLoader ?: classLoader)
                            tryInstallControlCenterExpansionMirrors(param.thisObject.javaClass.classLoader ?: classLoader)
                            val state = param.args.firstOrNull { it is Int } as? Int
                            val showing = param.args.firstOrNull { it is Boolean } as? Boolean
                            val locked = when {
                                state != null -> state == 1 || state == 2
                                showing != null -> showing
                                else -> return@guarded
                            }
                            injector.onKeyguardStateChanged(locked)
                        }
                    })
                    if (hooks.isNotEmpty()) onEvent("hook-installed", "$className#$method count=${hooks.size}")
                }
            }
        }
    }

    private fun installShadeClassLoadMirror() = guarded {
        XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = guarded {
                val className = param.args.firstOrNull() as? String ?: return@guarded
                if (className != shadeClassName && className != shadeStateManagerClassName && className != controlCenterClassName) return@guarded
                val loader = param.thisObject as? ClassLoader ?: return@guarded
                when (className) {
                    shadeClassName -> installShadeExpansionMirrors(loader)
                    shadeStateManagerClassName -> installShadeStateManagerMirror(loader)
                    else -> installControlCenterExpansionMirrors(loader)
                }
            }
        })
    }

    private fun installShadeViewMirror() = guarded {
        XposedBridge.hookAllMethods(ViewGroup::class.java, "addView", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = guarded {
                val child = param.args.firstOrNull { it is View } as? View ?: return@guarded
                if (child.javaClass.name != shadeViewClassName) return@guarded
                installShadeViewMirrors(child.javaClass.classLoader ?: classLoader)
            }
        })
    }

    private fun installShadeViewMirrors(loader: ClassLoader) {
        if (shadeViewMirrorInstalled) {
            tryInstallShadeExpansionMirrors(loader)
            return
        }
        val clazz = findSystemUiClass(shadeViewClassName, loader) ?: return
        guarded {
            val hooks = XposedBridge.hookAllMethods(clazz, "dispatchTouchEvent", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    tryInstallShadeExpansionMirrors(loader)
                }
            })
            if (hooks.isNotEmpty()) {
                shadeViewMirrorInstalled = true
                onEvent("hook-installed", "$shadeViewClassName#dispatchTouchEvent count=${hooks.size}")
            }
        }
        tryInstallShadeExpansionMirrors(loader)
        tryInstallControlCenterExpansionMirrors(loader)
    }

    private fun installShadeExpansionMirrors(loader: ClassLoader = classLoader) {
        if (shadeExpansionMirrorsInstalled) return
        val clazz = findSystemUiClass(shadeClassName, loader) ?: run {
            scheduleShadeExpansionRetry(loader)
            return
        }
        var installed = false
        guarded {
            val hooks = XposedBridge.hookAllMethods(clazz, "setExpandedFraction", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                        val fraction = param.args.firstOrNull { it is Float } as? Float ?: return@guarded
                        // Only the fully expanded panel hides signal rows. The
                        // intermediate drag position remains visually intact.
                    injector.onShadeExpansionChanged(fraction >= 0.99f)
                }
            })
            if (hooks.isNotEmpty()) {
                installed = true
                onEvent("hook-installed", "$shadeClassName#setExpandedFraction count=${hooks.size}")
            }
        }
        guarded {
            val hooks = XposedBridge.hookAllMethods(clazz, "setExpandedHeight", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    readShadeFullyExpanded(param.thisObject)?.let(injector::onShadeExpansionChanged)
                }
            })
            if (hooks.isNotEmpty()) {
                installed = true
                onEvent("hook-installed", "$shadeClassName#setExpandedHeight count=${hooks.size}")
            }
        }
        listOf("isShadeFullyExpanded", "isFullyExpanded").forEach { method ->
            guarded {
                val hooks = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) = guarded {
                        val expanded = param.result as? Boolean ?: return@guarded
                        injector.onShadeExpansionChanged(expanded)
                    }
                })
                if (hooks.isNotEmpty()) {
                    installed = true
                    onEvent("hook-installed", "$shadeClassName#$method count=${hooks.size}")
                }
            }
        }
        if (installed) {
            shadeExpansionMirrorsInstalled = true
        } else {
            scheduleShadeExpansionRetry(loader)
        }
    }

    private fun scheduleShadeExpansionRetry(loader: ClassLoader) {
        if (shadeExpansionRetryCount >= 30) return
        shadeExpansionRetryCount++
        main.postDelayed({ guarded { installShadeExpansionMirrors(loader) } }, 1_000L)
    }

    private fun installShadeStateManagerMirror(loader: ClassLoader = classLoader) {
        if (shadeStateManagerMirrorInstalled) return
        val clazz = findSystemUiClass(shadeStateManagerClassName, loader) ?: run {
            scheduleShadeStateManagerRetry(loader)
            return
        }
        guarded {
            val hooks = XposedBridge.hookAllMethods(clazz, "updateStateInternal", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    val state = param.args.firstOrNull { it is Int } as? Int ?: return@guarded
                    // ShadeExpansionStateManager uses CLOSED=0,
                    // OPENING=1 and OPEN=2. Only OPEN is the fully
                    // expanded panel requested by the user.
                    injector.onShadeExpansionChanged(state == 2)
                }
            })
            if (hooks.isNotEmpty()) {
                shadeStateManagerMirrorInstalled = true
                onEvent("hook-installed", "$shadeStateManagerClassName#updateStateInternal count=${hooks.size}")
            } else {
                scheduleShadeStateManagerRetry(loader)
            }
        }
    }

    private fun scheduleShadeStateManagerRetry(loader: ClassLoader) {
        if (shadeStateManagerRetryCount >= 30) return
        shadeStateManagerRetryCount++
        main.postDelayed({ guarded { installShadeStateManagerMirror(loader) } }, 1_000L)
    }

    private fun installControlCenterExpansionMirrors(loader: ClassLoader = classLoader) {
        if (controlCenterExpansionMirrorsInstalled) return
        val clazz = findSystemUiClass(controlCenterClassName, loader) ?: return
        var installed = false
        listOf("onExpansionChanged", "onVisibleChanged").forEach { method ->
            guarded {
                val hooks = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) = guarded {
                        val fraction = param.args.firstOrNull { it is Float } as? Float
                        val visible = param.args.firstOrNull { it is Boolean } as? Boolean
                        when {
                            fraction != null -> injector.onControlCenterFractionChanged(fraction)
                            visible != null -> injector.onControlCenterVisibilityChanged(visible)
                        }
                    }
                })
                if (hooks.isNotEmpty()) installed = true
            }
        }
        if (installed) {
            controlCenterExpansionMirrorsInstalled = true
            onEvent("hook-installed", "$controlCenterClassName expansion callbacks")
        }
    }

    private fun tryInstallShadeExpansionMirrors(loader: ClassLoader) {
        if (shadeExpansionMirrorsInstalled) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            installShadeExpansionMirrors(loader)
        } else {
            main.post { guarded { installShadeExpansionMirrors(loader) } }
        }
    }

    private fun tryInstallControlCenterExpansionMirrors(loader: ClassLoader) {
        if (controlCenterExpansionMirrorsInstalled) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            installControlCenterExpansionMirrors(loader)
        } else {
            main.post { guarded { installControlCenterExpansionMirrors(loader) } }
        }
    }

    private fun findShadeView(view: View): View? {
        var current: View? = view
        while (current != null) {
            if (current.javaClass.name == shadeViewClassName) return current
            current = current.parent as? View
        }
        return null
    }

    private fun findSystemUiClass(className: String, loader: ClassLoader): Class<*>? =
        XposedHelpers.findClassIfExists(className, loader)
            ?: runCatching { Class.forName(className, false, loader) }.getOrNull()

    private fun readShadeFullyExpanded(target: Any): Boolean? =
        listOf("isShadeFullyExpanded", "isFullyExpanded").firstNotNullOfOrNull { method ->
            runCatching { XposedHelpers.callMethod(target, method) as? Boolean }.getOrNull()
        }

    private fun findView(param: XC_MethodHook.MethodHookParam): View? {
        return param.getResult() as? View
            ?: param.args.firstOrNull { it is View } as? View
            ?: param.thisObject as? View
            ?: runCatching { XposedHelpers.callMethod(param.thisObject, "getView") as? View }.getOrNull()
    }

    private fun readIntField(target: Any?, names: List<String>): Int {
        if (target == null) return -1
        names.forEach { name ->
            val value = runCatching { XposedHelpers.getIntField(target, name) }.getOrNull()
            if (value != null && value >= 0) return value
        }
        return -1
    }

    private inline fun guarded(block: () -> Unit) {
        runCatching(block).onFailure(onError)
    }
}
