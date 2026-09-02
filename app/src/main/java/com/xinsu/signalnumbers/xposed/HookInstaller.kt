package com.xinsu.signalnumbers.xposed

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.xinsu.signalnumbers.compatibility.CompatibilityAdapter
import com.xinsu.signalnumbers.compatibility.HookPoint
import com.xinsu.signalnumbers.compatibility.ViewRole
import com.xinsu.signalnumbers.compatibility.WifiStateHookPoint
import com.xinsu.signalnumbers.injection.ViewInjector
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class HookInstaller(
    private val classLoader: ClassLoader,
    private val compatibility: CompatibilityAdapter,
    private val injector: ViewInjector,
    private val onSystemUiWifiRssi: (Int) -> Unit,
    private val onEvent: (String, String) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val isHookEnabled: () -> Boolean,
    private val worker: Handler,
) {
    private val main = Handler(Looper.getMainLooper())
    private val installed = AtomicBoolean(false)
    private val shadeExpansionInstallQueued = AtomicBoolean(false)
    private val shadeStateManagerInstallQueued = AtomicBoolean(false)
    private val quickSettingsInstallQueued = AtomicBoolean(false)
    private val controlCenterInstallQueued = AtomicBoolean(false)
    private val shadeViewInstallQueued = AtomicBoolean(false)
    private val pendingScans = WeakHashMap<View, PendingScan>()
    private val pendingWorkerObjects = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val pendingComposeInjections = weakViewSet()
    private val pendingImageInjections = weakViewSet()
    private val pendingAppearanceUpdates = weakViewSet()
    private val pendingAlphaUpdates = weakViewSet()
    private val pendingVisibilityUpdates = weakViewSet()
    private val pendingTintUpdates = weakViewSet()
    private val pendingBatteryInjections = weakViewSet()
    private val pendingBatteryUpdates = weakViewSet()
    private val pendingStatusRootDraws = weakViewSet()
    private val statusRootPostTimes = WeakHashMap<View, Long>()
    private val keyguardStateQueued = AtomicBoolean(false)
    @Volatile
    private var pendingKeyguardState = false
    private val shadeExpansionUpdate = LatestUiUpdate(false, main, isHookEnabled, injector::onShadeExpansionChanged, onError)
    private val shadeQsExpansionUpdate = LatestUiUpdate(false, main, isHookEnabled, injector::onShadeQsExpandedChanged, onError)
    private val controlCenterFractionUpdate = LatestUiUpdate(0f, main, isHookEnabled, injector::onControlCenterFractionChanged, onError)
    private val controlCenterVisibilityUpdate = LatestUiUpdate(false, main, isHookEnabled, injector::onControlCenterVisibilityChanged, onError)
    @Volatile
    private var shadeExpansionMirrorsInstalled = false
    @Volatile
    private var shadeViewMirrorInstalled = false
    @Volatile
    private var controlCenterExpansionMirrorsInstalled = false
    @Volatile
    private var shadeStateManagerMirrorInstalled = false
    @Volatile
    private var quickSettingsExpansionMirrorInstalled = false
    private var shadeExpansionRetryCount = 0
    private var shadeStateManagerRetryCount = 0
    private var quickSettingsExpansionRetryCount = 0
    private val shadeClassName = "com.android.systemui.shade.NotificationPanelViewController"
    private val shadeViewClassName = "com.android.systemui.shade.NotificationPanelView"
    private val shadeStateManagerClassName = "com.android.systemui.shade.ShadeExpansionStateManager"
    private val quickSettingsClassName = "com.android.systemui.shade.QuickSettingsControllerImpl"
    private val controlCenterClassName = "com.miui.systemui.controlcenter.container.ControlCenterExpandControllerDelegate"

    fun install() {
        if (!isHookEnabled() || !installed.compareAndSet(false, true)) return
        runCatching {
            compatibility.hookPoints.distinct().forEach(::installPoint)
            compatibility.wifiStateHookPoints.distinct().forEach(::installWifiStatePoint)
            installLayoutFallback()
            installComposeMobileFallback()
            installAppearanceMirrors()
            installMergedBatteryViewHooks()
            installKeyguardAppearanceMirrors()
            installKeyguardStateMirrors()
            installShadeClassLoadMirror()
            installShadeViewMirror()
            installShadeExpansionMirrors()
            installShadeStateManagerMirror()
            installQuickSettingsExpansionMirror()
            installControlCenterExpansionMirrors()
        }.onFailure(onError)
    }

    private fun installComposeMobileFallback() {
        compatibility.composeMobileCreatorHookPoints.forEach { (className, method) ->
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
            guarded {
                val hooks = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) = guarded {
                        val root = param.getResult() as? ViewGroup ?: return@guarded
                        postMainOnce(root, pendingComposeInjections) {
                            onEvent("compose-mobile", "$className#$method root=${root.javaClass.name}")
                            injector.injectComposeMobile(root)
                        }
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
                        val target = param.thisObject ?: return@guarded
                        postWorkerOnce(target) {
                            val state = point.stateFields.firstNotNullOfOrNull { field ->
                                runCatching { XposedHelpers.getObjectField(target, field) }.getOrNull()
                            } ?: return@postWorkerOnce
                            val rssi = point.rssiFields.firstNotNullOfOrNull { field ->
                                runCatching { XposedHelpers.getIntField(state, field) }.getOrNull()
                            } ?: return@postWorkerOnce
                            if (rssi in -126..-1) onSystemUiWifiRssi(rssi)
                        }
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
                        val result = param.getResult()
                        val target = param.thisObject
                        val args = param.args.copyOf()
                        val workKey = target ?: result ?: return@guarded
                        postWorkerOnce(workKey) {
                            val root = findView(result, args, target) ?: return@postWorkerOnce
                            val subId = readIntField(result ?: target, point.subscriptionFields)
                            onEvent("hook-callback", "${point.className}#$method root=${root.javaClass.name} subId=$subId thread=${Thread.currentThread().name}")
                            dispatchScan(root, point.role.takeUnless { it == ViewRole.STATUS_ROOT }, subId)
                        }
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
                postWorkerOnce(root) {
                    val name = runCatching { root.resources.getResourceEntryName(resourceId) }.getOrDefault("")
                    if (compatibility.isLikelyStatusResource(name)) {
                        onEvent("layout-fallback", "$name root=${root.javaClass.name} thread=${Thread.currentThread().name}")
                        dispatchScan(root, null, -1)
                    }
                }
            }
        })
    }

    private fun dispatchScan(root: View, role: ViewRole?, subId: Int) {
        tryInstallShadeExpansionMirrors(root.javaClass.classLoader ?: classLoader)
        tryInstallControlCenterExpansionMirrors(root.javaClass.classLoader ?: classLoader)
        findShadeView(root)?.let { requestShadeViewMirrors(it.javaClass.classLoader ?: classLoader) }
        postScan(root, role, subId)
    }

    private fun installAppearanceMirrors() {
        guarded {
            XposedBridge.hookAllMethods(ImageView::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    val image = param.thisObject as? ImageView ?: return@guarded
                    tryInstallShadeExpansionMirrors(image.javaClass.classLoader ?: classLoader)
                    tryInstallControlCenterExpansionMirrors(image.javaClass.classLoader ?: classLoader)
                    postMainOnce(image, pendingImageInjections) {
                        injector.injectKnownImage(image)
                    }
                }
            })
        }
        guarded {
            XposedBridge.hookAllMethods(View::class.java, "setVisibility", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    val view = param.thisObject as? View ?: return@guarded
                    postMainOnce(view, pendingVisibilityUpdates) {
                        injector.onOriginalVisibilityChanged(view)
                    }
                }
            })
        }
        guarded {
            XposedBridge.hookAllMethods(View::class.java, "setAlpha", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    val view = param.thisObject as? View ?: return@guarded
                    postMainOnce(view, pendingAlphaUpdates) {
                        injector.onOriginalAlphaChanged(view)
                    }
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
                            postMainOnce(view, pendingAppearanceUpdates) {
                                injector.onAppearanceChanged(view, tint)
                            }
                        }
                    })
                }
            }
        }
        guarded {
            XposedBridge.hookAllMethods(ImageView::class.java, "setImageTintList", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    val image = param.thisObject as? ImageView ?: return@guarded
                    postMainOnce(image, pendingTintUpdates) {
                        injector.onOriginalTintChanged(image)
                    }
                }
            })
        }
    }

    private fun installMergedBatteryViewHooks() = guarded {
        if (!compatibility.mergedSignalDisplay ||
            (compatibility.batteryViewClassNames.isEmpty() && compatibility.batteryViewResourceNames.isEmpty())
        ) return@guarded

        // The visible PJZ110 battery slots are ComposeViews identified by
        // resource name. Hook their lifecycle so each slot is wrapped as soon
        // as it is attached. When resource anchors are declared, the legacy
        // BatteryMeterView is only a hide target, never a merge anchor.
        XposedBridge.hookAllMethods(ViewGroup::class.java, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = guarded {
                val view = param.thisObject as? ViewGroup ?: return@guarded
                postBatteryUpdate(view)
            }
        })
        guarded {
            // Some PJZ110 shade/keyguard battery slots are created after the
            // initial layout scan. Catch both the base View lifecycle and the
            // actual parent insertion so a late Compose anchor cannot expose
            // the native percentage for one frame or bypass the merge.
        XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    val view = param.thisObject as? ViewGroup ?: return@guarded
                    postBatteryUpdate(view)
                }
            })
        }
        guarded {
        XposedBridge.hookAllMethods(ViewGroup::class.java, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    val view = param.args.firstOrNull { it is View } as? ViewGroup ?: return@guarded
                    postBatteryUpdate(view)
                }
            })
        }

        compatibility.batteryViewClassNames.forEach { className ->
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
            listOf("onConfigurationChanged", "updatePercentText").forEach { method ->
                guarded {
                    XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) = guarded {
                            val view = param.thisObject as? ViewGroup ?: return@guarded
                            postMainOnce(view, pendingBatteryUpdates) {
                                injector.onBatteryViewChanged(view)
                            }
                        }
                    })
                }
            }
            guarded {
                XposedBridge.hookAllMethods(clazz, "onDarkChanged", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) = guarded {
                        val view = param.thisObject as? ViewGroup ?: return@guarded
                        val tint = param.args.lastOrNull { it is Int } as? Int
                        postMainOnce(view, pendingBatteryUpdates) {
                            injector.onBatteryAppearanceChanged(view, tint)
                        }
                    }
                })
            }
            onEvent("hook-installed", "$className merged battery callbacks")
        }
    }

    private fun installKeyguardAppearanceMirrors() {
        if (compatibility.mergedSignalDisplay) {
            guarded {
                // PJZ110 can load PhoneStatusBarView after the module starts,
                // so the profile-specific onDraw hook may miss the class.
                // View.draw is already available and gives the injector a
                // reliable late entry point without adding anything to the
                // Xiaomi/non-merged compatibility paths.
                val hooks = XposedBridge.hookAllMethods(View::class.java, "draw", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) = guarded {
                        val root = param.thisObject as? ViewGroup ?: return@guarded
                        when (root.javaClass.name) {
                            "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                            "com.android.systemui.statusbar.window.StatusBarWindowView",
                            "com.android.systemui.shade.NotificationShadeWindowView",
                            -> postStatusRootDraw(root)
                        }
                    }
                })
                if (hooks.isNotEmpty()) onEvent("hook-installed", "merged status root View#draw count=${hooks.size}")
            }
            guarded {
                // The keyguard header lives in NotificationShadeWindowView,
                // not in PhoneStatusBarView. Run before its children draw so
                // the native keyguard battery group cannot overlap the PJZ110
                // merged element.
                val hooks = XposedBridge.hookAllMethods(ViewGroup::class.java, "dispatchDraw", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = guarded {
                        val root = param.thisObject as? ViewGroup ?: return@guarded
                        when (root.javaClass.name) {
                            "com.android.systemui.shade.NotificationShadeWindowView",
                            "com.android.systemui.statusbar.phone.KeyguardStatusBarView",
                            -> postStatusRootDraw(root)
                        }
                    }
                })
                if (hooks.isNotEmpty()) onEvent("hook-installed", "merged keyguard ViewGroup#dispatchDraw count=${hooks.size}")
            }
            listOf(
                "com.android.systemui.shade.NotificationShadeWindowView" to listOf("onDraw"),
                "com.android.systemui.statusbar.phone.KeyguardStatusBarView" to listOf("onFinishInflate", "onLayout"),
            ).forEach { (className, methods) ->
                val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
                methods.forEach { method ->
                    guarded {
                        val hooks = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) = guarded {
                                val root = param.thisObject as? ViewGroup ?: return@guarded
                                postStatusRootDraw(root)
                            }
                        })
                        if (hooks.isNotEmpty()) onEvent("hook-installed", "$className#$method count=${hooks.size}")
                    }
                }
            }
        }
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
                            postStatusRootDraw(root)
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
                            postKeyguardState(locked)
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
                if (
                    className != shadeClassName &&
                    className != shadeStateManagerClassName &&
                    className != quickSettingsClassName &&
                    className != controlCenterClassName
                ) return@guarded
                val loader = param.thisObject as? ClassLoader ?: return@guarded
                when (className) {
                    shadeClassName -> tryInstallShadeExpansionMirrors(loader)
                    shadeStateManagerClassName -> tryInstallShadeStateManagerMirror(loader)
                    quickSettingsClassName -> tryInstallQuickSettingsExpansionMirror(loader)
                    else -> tryInstallControlCenterExpansionMirrors(loader)
                }
            }
        })
    }

    private fun installShadeViewMirror() = guarded {
        XposedBridge.hookAllMethods(ViewGroup::class.java, "addView", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = guarded {
                val child = param.args.firstOrNull { it is View } as? View ?: return@guarded
                if (child.javaClass.name != shadeViewClassName) return@guarded
                requestShadeViewMirrors(child.javaClass.classLoader ?: classLoader)
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
                    if (compatibility.mergedSignalDisplay) {
                        val shade = param.thisObject as? ViewGroup ?: return@guarded
                        postScan(shade, null, -1)
                    }
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
                    val fraction = param.args.firstOrNull { it is Float } as? Float
                        ?: return@guarded
                    // Only the fully expanded panel hides signal rows. The
                    // intermediate drag position remains visually intact.
                    shadeExpansionUpdate.post(fraction >= 0.99f)
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
                    val target = param.thisObject ?: return@guarded
                    postWorkerOnce(target) {
                        readShadeFullyExpanded(target)?.let(shadeExpansionUpdate::post)
                    }
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
                        shadeExpansionUpdate.post(expanded)
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
        worker.postDelayed({ tryInstallShadeExpansionMirrors(loader) }, 1_000L)
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
                    shadeExpansionUpdate.post(state == 2)
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
        worker.postDelayed({ tryInstallShadeStateManagerMirror(loader) }, 1_000L)
    }

    private fun installQuickSettingsExpansionMirror(loader: ClassLoader = classLoader) {
        if (quickSettingsExpansionMirrorInstalled) return
        val clazz = findSystemUiClass(quickSettingsClassName, loader) ?: run {
            scheduleQuickSettingsExpansionRetry(loader)
            return
        }
        var installed = false
        guarded {
            val hooks = XposedBridge.hookAllMethods(clazz, "setExpanded", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    val expanded = param.args.firstOrNull { it is Boolean } as? Boolean
                        ?: return@guarded
                    shadeQsExpansionUpdate.post(expanded)
                }
            })
            if (hooks.isNotEmpty()) installed = true
        }
        guarded {
            val hooks = XposedBridge.hookAllMethods(clazz, "getExpanded", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = guarded {
                    (param.result as? Boolean)?.let(shadeQsExpansionUpdate::post)
                }
            })
            if (hooks.isNotEmpty()) installed = true
        }
        if (installed) {
            quickSettingsExpansionMirrorInstalled = true
            onEvent("hook-installed", "$quickSettingsClassName QS expansion callbacks")
        } else {
            scheduleQuickSettingsExpansionRetry(loader)
        }
    }

    private fun scheduleQuickSettingsExpansionRetry(loader: ClassLoader) {
        if (quickSettingsExpansionRetryCount >= 30) return
        quickSettingsExpansionRetryCount++
        worker.postDelayed({ tryInstallQuickSettingsExpansionMirror(loader) }, 1_000L)
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
                            fraction != null -> controlCenterFractionUpdate.post(fraction)
                            visible != null -> controlCenterVisibilityUpdate.post(visible)
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
        if (!isHookEnabled() || shadeExpansionMirrorsInstalled ||
            !shadeExpansionInstallQueued.compareAndSet(false, true)
        ) return
        if (!worker.post {
                try {
                    guarded { installShadeExpansionMirrors(loader) }
                } finally {
                    shadeExpansionInstallQueued.set(false)
                }
            }) shadeExpansionInstallQueued.set(false)
    }

    private fun tryInstallControlCenterExpansionMirrors(loader: ClassLoader) {
        if (!isHookEnabled() || controlCenterExpansionMirrorsInstalled ||
            !controlCenterInstallQueued.compareAndSet(false, true)
        ) return
        if (!worker.post {
                try {
                    guarded { installControlCenterExpansionMirrors(loader) }
                } finally {
                    controlCenterInstallQueued.set(false)
                }
            }) controlCenterInstallQueued.set(false)
    }

    private fun tryInstallShadeStateManagerMirror(loader: ClassLoader) {
        if (!isHookEnabled() || shadeStateManagerMirrorInstalled ||
            !shadeStateManagerInstallQueued.compareAndSet(false, true)
        ) return
        if (!worker.post {
                try {
                    guarded { installShadeStateManagerMirror(loader) }
                } finally {
                    shadeStateManagerInstallQueued.set(false)
                }
            }) shadeStateManagerInstallQueued.set(false)
    }

    private fun tryInstallQuickSettingsExpansionMirror(loader: ClassLoader) {
        if (!isHookEnabled() || quickSettingsExpansionMirrorInstalled ||
            !quickSettingsInstallQueued.compareAndSet(false, true)
        ) return
        if (!worker.post {
                try {
                    guarded { installQuickSettingsExpansionMirror(loader) }
                } finally {
                    quickSettingsInstallQueued.set(false)
                }
            }) quickSettingsInstallQueued.set(false)
    }

    private fun requestShadeViewMirrors(loader: ClassLoader) {
        if (!isHookEnabled() || shadeViewMirrorInstalled ||
            !shadeViewInstallQueued.compareAndSet(false, true)
        ) return
        if (!worker.post {
                try {
                    guarded { installShadeViewMirrors(loader) }
                } finally {
                    shadeViewInstallQueued.set(false)
                }
            }) shadeViewInstallQueued.set(false)
    }

    /**
     * Keep view discovery and hierarchy mutation on SystemUI's UI thread, but
     * collapse bursts from LayoutInflater/View callbacks to one scan per root.
     */
    private fun postWorkerOnce(target: Any, action: () -> Unit) {
        synchronized(pendingWorkerObjects) {
            if (!pendingWorkerObjects.add(target)) return
        }
        if (!worker.post {
                try {
                    guarded(action)
                } finally {
                    synchronized(pendingWorkerObjects) { pendingWorkerObjects.remove(target) }
                }
            }) {
            synchronized(pendingWorkerObjects) { pendingWorkerObjects.remove(target) }
        }
    }

    private fun postScan(root: View, role: ViewRole?, subId: Int) {
        var schedule = false
        synchronized(pendingScans) {
            val pending = pendingScans[root]
            if (pending == null) {
                pendingScans[root] = PendingScan(root, role, subId)
                schedule = true
            } else {
                // A broad scan supersedes a role-specific scan for the same
                // root; otherwise a late callback could miss its peer icon.
                pending.role = if (pending.role == null || role == null) null else role
                if (subId >= 0) pending.subId = subId
            }
        }
        if (!schedule) return
        if (!main.post {
                val pending = synchronized(pendingScans) { pendingScans.remove(root) }
                if (pending != null) {
                    guarded { injector.scanAndInject(pending.root, pending.role, pending.subId) }
                }
            }) {
            synchronized(pendingScans) { pendingScans.remove(root) }
        }
    }

    private fun postMainOnce(view: View, pending: MutableSet<View>, action: () -> Unit) {
        synchronized(pending) {
            if (!pending.add(view)) return
        }
        if (!main.post {
                try {
                    guarded(action)
                } finally {
                    synchronized(pending) { pending.remove(view) }
                }
            }) {
            synchronized(pending) { pending.remove(view) }
        }
    }

    private fun postBatteryUpdate(view: ViewGroup) {
        postMainOnce(view, pendingBatteryInjections) {
            val resourceName = runCatching {
                if (view.id == View.NO_ID) "" else view.resources.getResourceEntryName(view.id)
            }.getOrDefault("")
            if (resourceName in compatibility.batteryViewResourceNames) {
                injector.injectBatteryView(view)
            }
        }
    }

    private fun postStatusRootDraw(root: ViewGroup) {
        val now = SystemClock.uptimeMillis()
        synchronized(statusRootPostTimes) {
            val previous = statusRootPostTimes[root] ?: Long.MIN_VALUE
            if (now - previous < STATUS_ROOT_POST_INTERVAL_MS) return
            statusRootPostTimes[root] = now
        }
        postMainOnce(root, pendingStatusRootDraws) {
            injector.onStatusRootDraw(root)
        }
    }

    private fun postKeyguardState(locked: Boolean) {
        pendingKeyguardState = locked
        if (!keyguardStateQueued.compareAndSet(false, true)) return
        if (!main.post {
                val applied = pendingKeyguardState
                try {
                    if (isHookEnabled()) injector.onKeyguardStateChanged(applied)
                } catch (throwable: Throwable) {
                    onError(throwable)
                } finally {
                    keyguardStateQueued.set(false)
                    if (pendingKeyguardState != applied && isHookEnabled()) {
                        postKeyguardState(pendingKeyguardState)
                    }
                }
            }) keyguardStateQueued.set(false)
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

    private fun findView(result: Any?, args: Array<Any?>, thisObject: Any?): View? {
        return result as? View
            ?: args.firstOrNull { it is View } as? View
            ?: thisObject as? View
            ?: runCatching {
                thisObject?.let { XposedHelpers.callMethod(it, "getView") as? View }
            }.getOrNull()
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
        if (!isHookEnabled()) return
        runCatching(block).onFailure(onError)
    }
}

private data class PendingScan(
    val root: View,
    var role: ViewRole?,
    var subId: Int,
)

private fun weakViewSet(): MutableSet<View> =
    Collections.newSetFromMap(WeakHashMap<View, Boolean>())

private class LatestUiUpdate<T>(
    initial: T,
    private val main: Handler,
    private val isEnabled: () -> Boolean,
    private val action: (T) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val queued = AtomicBoolean(false)

    @Volatile
    private var latest = initial

    fun post(value: T) {
        latest = value
        if (!queued.compareAndSet(false, true)) return
        if (!main.post {
                val applied = latest
                try {
                    if (isEnabled()) action(applied)
                } catch (throwable: Throwable) {
                    onError(throwable)
                } finally {
                    queued.set(false)
                    if (latest != applied && isEnabled()) post(latest)
                }
            }) queued.set(false)
    }
}

private const val STATUS_ROOT_POST_INTERVAL_MS = 250L
