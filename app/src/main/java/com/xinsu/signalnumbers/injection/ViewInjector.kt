package com.xinsu.signalnumbers.injection

import android.app.KeyguardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xinsu.signalnumbers.R
import com.xinsu.signalnumbers.compatibility.CompatibilityAdapter
import com.xinsu.signalnumbers.compatibility.CompatibilityMode
import com.xinsu.signalnumbers.compatibility.ViewRole
import com.xinsu.signalnumbers.config.ModuleConfig
import com.xinsu.signalnumbers.signal.MobileReading
import com.xinsu.signalnumbers.signal.SignalSnapshot
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.max

class ViewInjector(
    private val compatibility: CompatibilityAdapter,
    private val onEvent: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val locator = ViewLocator(compatibility)
    private val byOriginal = WeakHashMap<ImageView, InjectedSignalView>()
    private val byNetworkType = WeakHashMap<View, InjectedSignalView>()
    private val byMobileActivity = WeakHashMap<View, InjectedSignalView>()
    private val composeMobile = WeakHashMap<ViewGroup, ComposeSignalView>()
    private val mergedByBattery = WeakHashMap<ViewGroup, MergedSignalView>()
    private val keyguardMergedByContainer = WeakHashMap<ViewGroup, KeyguardMergedSignalView>()
    private val keyguardHiddenSystemIcons = WeakHashMap<View, HiddenViewState>()
    private val all = mutableListOf<WeakReference<InjectedSignalView>>()
    private var config = ModuleConfig()
    private var snapshot = SignalSnapshot()
    private val alphaGuard = ThreadLocal<Boolean>()
    private val visibilityGuard = ThreadLocal<Boolean>()
    private var keyguardLocked: Boolean? = null
    private var shadePanelFullyExpanded = false
    private var controlCenterFullyExpanded = false
    private var controlCenterVisible = false
    private var shadeExpanded = false
    /** Whether a fully expanded shade surface is visible, including over keyguard. */
    private var shadeAppearanceExpanded = false
    private var mobileAppearanceTint: Int? = null
    private var wifiAppearanceTint: Int? = null
    private var statusAppearanceTint: Int? = null
    private var shadeAppearanceTint: Int? = null
    private var shadeMobileAppearanceTint: Int? = null
    private var shadeWifiAppearanceTint: Int? = null

    fun scanAndInject(root: View, forcedRole: ViewRole? = null, hintedSubId: Int = -1) = guarded {
        locator.locate(root, forcedRole).forEach { candidate ->
            val existing = byOriginal[candidate.image]
            if (existing == null) {
                inject(candidate.image, candidate.role, hintedSubId)
            } else if (candidate.role == ViewRole.MOBILE && hintedSubId >= 0) {
                existing.subscriptionId = hintedSubId
                val reading = snapshot.mobileBySubscription[hintedSubId]
                if (reading != null) existing.slotIndex = reading.slotIndex
                onEvent("bound existing mobile id=${System.identityHashCode(candidate.image)} subId=$hintedSubId slot=${existing.slotIndex}")
            }
        }
        if (compatibility.mergedSignalDisplay) {
            locator.locateBatteryViews(
                root,
                compatibility.batteryViewClassNames,
                compatibility.batteryViewResourceNames,
            )
                .forEach(::injectBatteryView)
        }
        renderAll()
    }

    fun injectKnownImage(image: ImageView) = guarded {
        if (byOriginal.containsKey(image)) return@guarded
        val name = locator.resourceName(image)
        val role = when (name) {
            in compatibility.mobileResourceNames -> ViewRole.MOBILE
            in compatibility.wifiResourceNames -> ViewRole.WIFI
            else -> return@guarded
        }
        inject(image, role, -1)
    }

    fun injectBatteryView(view: ViewGroup) = guarded {
        if (!compatibility.mergedSignalDisplay ||
            mergedByBattery.containsKey(view) ||
            !isMergedBatteryAnchor(view)
        ) return@guarded
        injectMergedBatteryView(view)
    }

    fun onBatteryViewChanged(view: ViewGroup) = guarded {
        val merged = mergedByBattery[view] ?: run {
            injectBatteryView(view)
            mergedByBattery[view]
        } ?: return@guarded
        applyMergedStyle(merged)
        renderMerged(merged)
    }

    fun onBatteryAppearanceChanged(view: ViewGroup, tint: Int?) = guarded {
        val merged = mergedByBattery[view] ?: run {
            injectBatteryView(view)
            mergedByBattery[view]
        } ?: return@guarded
        if (tint != null && tint ushr 24 != 0) merged.appearanceTint = tint
        copyMergedTint(merged)
        renderMerged(merged)
    }

    private fun injectMergedBatteryView(original: ViewGroup) {
        val parent = original.parent as? ViewGroup ?: return
        if (original.getTag(R.id.merged_signal_overlay) != null) return
        val index = parent.indexOfChild(original).takeIf { it >= 0 } ?: return
        val oldParams = original.layoutParams
        val oldVisibility = original.visibility
        val oldAlpha = original.alpha
        val host = FrameLayout(original.context).apply {
            layoutParams = copyLayoutParams(oldParams, ViewGroup.LayoutParams.WRAP_CONTENT)
            clipChildren = false
            clipToPadding = false
        }
        val text = TextView(original.context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        }
        val merged = MergedSignalView(
            original = original,
            host = host,
            text = text,
            originalParent = parent,
            originalIndex = index,
            originalLayoutParams = oldParams,
            originalVisibility = oldVisibility,
            originalAlpha = oldAlpha,
            requestedVisibility = oldVisibility,
            requestedAlpha = oldAlpha,
        )
        mergedByBattery[original] = merged
        original.setTag(R.id.merged_signal_overlay, text)
        var removed = false
        try {
            parent.removeViewAt(index)
            removed = true
            original.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_VERTICAL,
            )
            host.addView(original)
            host.addView(text)
            parent.addView(host, index)
            onEvent(
                "injected merged status element parent=${parent.javaClass.name} " +
                    "battery=${original.javaClass.name} id=${System.identityHashCode(original)}",
            )
            applyMergedStyle(merged)
            renderMerged(merged)
        } catch (t: Throwable) {
            mergedByBattery.remove(original)
            original.setTag(R.id.merged_signal_overlay, null)
            runCatching { host.removeView(original) }
            runCatching { (host.parent as? ViewGroup)?.removeView(host) }
            if (removed && original.parent == null) {
                runCatching { parent.addView(original, index.coerceAtMost(parent.childCount), oldParams) }
            }
            original.visibility = oldVisibility
            original.alpha = oldAlpha
            throw t
        }
    }

    fun injectComposeMobile(root: ViewGroup) = guarded {
        if (composeMobile.containsKey(root)) return@guarded
        val compose = runCatching {
            root.javaClass.getDeclaredField("composeView").apply { isAccessible = true }.get(root) as? View
        }.getOrNull() ?: findComposeChild(root)
        if (compose == null) {
            onEvent("compose mobile child not found: " + (0 until root.childCount).joinToString { index ->
                val child = root.getChildAt(index)
                "${child.javaClass.name}/${locator.resourceName(child)}"
            })
            return@guarded
        }
        val text = TextView(root.context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        }
        root.addView(text)
        val entry = ComposeSignalView(root, compose, text, compose.alpha)
        composeMobile[root] = entry
        root.minimumWidth = max(root.minimumWidth, numberWidth(root, ViewRole.MOBILE))
        onEvent("injected compose mobile root=${root.javaClass.name} child=${compose.javaClass.name}")
        applyComposeStyle(entry)
        renderCompose(entry)
    }

    private fun findComposeChild(group: ViewGroup): View? {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (locator.resourceName(child) == "compose_view" || child.javaClass.name.contains("ComposeView")) return child
            if (child is ViewGroup) findComposeChild(child)?.let { return it }
        }
        return null
    }

    fun onComposeAppearanceChanged(root: ViewGroup, tint: Int?) = guarded {
        val entry = composeMobile[root] ?: return@guarded
        if (tint != null && tint ushr 24 != 0) {
            // Keep the Compose view's own appearance tint as the stable value.
            // The traditional mobile tint is only a temporary fallback while
            // PJZ110's fully expanded shade is visible; persisting that peer
            // tint here leaks a shade color into the desktop and lockscreen.
            entry.appearanceTint = tint
        }
        if (compatibility.mergedSignalDisplay) {
            // PJZ110 renders all values in the battery-anchored merged element.
            // SystemUI can call the Compose appearance callback after a normal
            // render and otherwise expose the original mobile icons again.
            hideComposeForMergedDisplay(entry)
            return@guarded
        }
        copyComposeTint(entry)
        if (shouldHideExpandedShadeSignalRow(entry.root)) {
            entry.compose.alpha = 0f
            entry.text.visibility = View.GONE
            return@guarded
        }
        entry.text.visibility = if (root.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        if (entry.text.visibility == View.VISIBLE) entry.compose.alpha = 0f
    }

    fun onAppearanceChanged(root: ViewGroup, tint: Int?) = guarded {
        val appearanceSourceRole = if (tint != null && tint ushr 24 != 0) {
            compatibility.appearanceTintSourceClassNames.entries
                .firstOrNull { root.javaClass.name in it.value }
                ?.key
        } else {
            null
        }
        when (appearanceSourceRole) {
            ViewRole.MOBILE -> mobileAppearanceTint = tint
            ViewRole.WIFI -> wifiAppearanceTint = tint
            else -> Unit
        }
        if (tint != null && tint ushr 24 != 0) {
            // PJZ110 reuses the same signal View instances in the top bar and
            // the fully expanded shade. Once the shade is open, its current
            // appearance callback belongs to the expanded surface even when
            // the view has no shade-specific ancestor of its own.
            val expandedSource = isExpandedShadeSignal(root) || shadeAppearanceExpanded
            if (expandedSource) {
                shadeAppearanceTint = tint
                when (appearanceSourceRole) {
                    ViewRole.MOBILE -> shadeMobileAppearanceTint = tint
                    ViewRole.WIFI -> shadeWifiAppearanceTint = tint
                    else -> Unit
                }
            } else {
                statusAppearanceTint = tint
            }
            var updated = 0
            liveViews().forEach { view ->
                if (!isDescendant(view.wrapper, root)) return@forEach
                // Keep the raw SystemUI appearance tint as the stable value.
                // copyTint() applies the temporary white override for keyguard
                // and the expanded control center without destroying this value.
                view.appearanceTint = tint
                copyTint(view)
                view.text.alpha = view.originalAlpha
                updated++
            }
            if (updated > 0) onEvent("appearance root=${root.javaClass.name} tint=${Integer.toHexString(tint)} injected=$updated")
            updateMergedAppearance(root, tint, expandedSource)
        }
        onComposeAppearanceChanged(root, tint)
        if (appearanceSourceRole == ViewRole.MOBILE) {
            composeMobile.values.toList().forEach {
                copyComposeTint(it)
            }
        }
    }

    private fun updateMergedAppearance(source: ViewGroup, tint: Int, expandedSource: Boolean) {
        if (!compatibility.mergedSignalDisplay) return
        var updated = 0
        mergedByBattery.values.toList().forEach { view ->
            if (isExpandedShadeSignal(view.original) != expandedSource) return@forEach
            view.appearanceTint = tint
            copyMergedTint(view)
            updated++
        }
        if (updated > 0) {
            onEvent(
                "merged appearance root=${source.javaClass.name} " +
                    "expanded=$expandedSource tint=${Integer.toHexString(tint)} updated=$updated",
            )
        }
    }

    fun onShadeExpansionChanged(expanded: Boolean) = guarded {
        // A locked keyguard may still report the shade as open while its
        // lockscreen status bar is being rebuilt. Never hide lockscreen
        // signal views; only an explicitly unlocked, fully expanded shade
        // can hide its duplicate row.
        shadePanelFullyExpanded = expanded
        updateShadeState("notification-shade")
    }

    fun onControlCenterFractionChanged(fraction: Float) = guarded {
        // HyperOS can report the ordinary NotificationPanel as collapsed while
        // its control center is still fully visible. Keep this source separate
        // so a false panel callback cannot turn the numbers black underneath a
        // still-open control center.
        controlCenterFullyExpanded = fraction >= 0.99f
        updateShadeState("control-center-fraction=${"%.3f".format(java.util.Locale.US, fraction)}")
    }

    fun onControlCenterVisibilityChanged(visible: Boolean) = guarded {
        controlCenterVisible = visible
        if (!visible) controlCenterFullyExpanded = false
        updateShadeState("control-center-visible")
    }

    fun onStatusRootDraw(root: ViewGroup) = guarded {
        val manager = root.context.getSystemService(KeyguardManager::class.java)
        onKeyguardStateChanged(manager?.isKeyguardLocked == true, root)
        if (keyguardLocked == true) hideKeyguardNativeSystemIcons(root)
        if (compatibility.mergedSignalDisplay && shadeAppearanceExpanded) {
            // PJZ110 creates a different Compose battery slot for the fully
            // expanded shade. Its slot can appear after the initial layout
            // scan, so discover and wrap the visible anchor immediately
            // before the shade window draws it.
            ensureMergedBatteryViews(root)
        }
    }

    fun onKeyguardStateChanged(locked: Boolean) = guarded {
        onKeyguardStateChanged(locked, null)
    }

    private fun onKeyguardStateChanged(locked: Boolean, root: ViewGroup?) {
        val shadeWasExpanded = shadeExpanded
        val forcedWhiteBefore = isForcedWhite()
        val keyguardStateChanged = keyguardLocked != locked
        if (!keyguardStateChanged && !(locked && shadeWasExpanded)) {
            // The lockscreen status bar is rebuilt independently from the
            // desktop/shade battery anchors. Keep trying on every status-root
            // draw so a late-created visible container can receive the merged
            // element even when the keyguard state itself did not change.
            if (locked && root != null) {
                ensureKeyguardMergedView(root)
                keyguardMergedByContainer.values.toList().forEach(::renderKeyguardMerged)
            }
            return
        }
        keyguardLocked = locked
        if (locked) {
            shadePanelFullyExpanded = false
            controlCenterFullyExpanded = false
            controlCenterVisible = false
            shadeExpanded = false
            shadeAppearanceExpanded = false
            shadeAppearanceTint = null
            shadeMobileAppearanceTint = null
            shadeWifiAppearanceTint = null
        }

        if (locked && root != null) ensureKeyguardMergedView(root)
        if (!locked) restoreKeyguardNativeSystemIcons()

        // Re-render on every keyguard transition. PJZ110 reuses the lower
        // shade carrier row in its lockscreen hierarchy, so visibility and
        // layout must be recalculated even when the previous shade state was
        // already collapsed.
        if (keyguardStateChanged || (locked && shadeWasExpanded)) renderAll()

        var updated = 0
        liveViews().forEach { view ->
            if (root != null && !isDescendant(view.wrapper, root)) return@forEach
            copyTint(view)
            view.text.alpha = view.originalAlpha
            updated++
        }
        composeMobile.values.toList().forEach(::copyComposeTint)
        mergedByBattery.values.toList().forEach(::copyMergedTint)
        keyguardMergedByContainer.values.toList().forEach(::renderKeyguardMerged)
        if (updated > 0) onEvent("keyguard locked=$locked root=${root?.javaClass?.name ?: "all"} tint=${if (locked) "ffffffff" else "original"} forced=${isForcedWhite()} wasForced=$forcedWhiteBefore injected=$updated")
    }

    fun updateConfig(value: ModuleConfig) = guarded {
        config = value
        if (!value.enabled || value.safeMode) restoreAll() else renderAll()
    }

    fun updateSignals(value: SignalSnapshot) = guarded {
        snapshot = value
        renderAll()
    }

    fun onOriginalVisibilityChanged(view: View) = guarded {
        if (visibilityGuard.get() == true) return@guarded
        val compose = composeMobile.values.firstOrNull { it.compose === view }
        if (compose != null && compatibility.mergedSignalDisplay) {
            // The original Compose child is only a source view in merged mode.
            // Keep it hidden when SystemUI rebuilds or rebinds the status icon.
            hideComposeForMergedDisplay(compose)
            return@guarded
        }
        val requestedVisibility = view.visibility
        val group = view as? ViewGroup
        if (group != null && compatibility.mergedSignalDisplay && !mergedByBattery.containsKey(group)) {
            // A Compose battery anchor can receive its first visibility update
            // before the layout fallback has scanned the completed hierarchy.
            // Try to wrap it before allowing that update to hide the anchor.
            injectBatteryView(group)
        }
        val battery = group?.let(mergedByBattery::get)
        if (battery != null) {
            battery.requestedVisibility = requestedVisibility
            renderMerged(battery)
            return@guarded
        }
        if (compatibility.mergedSignalDisplay && isLegacyBatteryView(view)) {
            // BatteryMeterView is only the legacy drawing path on PJZ110. Keep
            // it hidden even when SystemUI later toggles it back on.
            visibilityGuard.set(true)
            try {
                if (view.visibility != View.GONE) view.visibility = View.GONE
            } finally {
                visibilityGuard.remove()
            }
            return@guarded
        }
        val signal = (view as? ImageView)?.let(byOriginal::get)
        if (signal != null) {
            render(signal)
            return@guarded
        }
        val activity = byMobileActivity[view]
        if (activity != null) {
            render(activity)
            return@guarded
        }
        val type = byNetworkType[view] ?: return@guarded
        type.networkTypeVisibilities = type.networkTypeVisibilities + (view to view.visibility)
        render(type)
    }

    fun onOriginalTintChanged(view: ImageView) = guarded {
        val injected = byOriginal[view] ?: return@guarded
        // HyperOS updates the original ImageView tint after its ModernStatusBarView
        // appearance callback. That tint is not always the final status-bar color
        // and can overwrite a just-applied white/black appearance, causing the
        // injected number to flicker until the next touch or layout pass. Once a
        // status-bar appearance tint has been received, keep it authoritative;
        // the original ImageView tint remains the fallback before that callback.
        copyTint(injected)
    }

    fun onOriginalAlphaChanged(view: View) = guarded {
        if (alphaGuard.get() == true) return@guarded
        val compose = composeMobile.values.firstOrNull { it.compose === view }
        if (compose != null && compatibility.mergedSignalDisplay) {
            // setAlpha(1f) is a common late update from the Compose binding.
            // Do not let that update bring the duplicate native layer back.
            hideComposeForMergedDisplay(compose)
            return@guarded
        }
        val battery = (view as? ViewGroup)?.let(mergedByBattery::get)
        if (battery != null) {
            val requestedAlpha = view.alpha
            battery.requestedAlpha = requestedAlpha
            if (requestedAlpha > 0f) battery.text.alpha = requestedAlpha
            if (view.alpha != 0f) {
                alphaGuard.set(true)
                try { view.alpha = 0f } finally { alphaGuard.remove() }
            }
            renderMerged(battery)
            return@guarded
        }
        val injected = byOriginal[view as? ImageView ?: return@guarded] ?: return@guarded
        val requestedAlpha = view.alpha
        if (requestedAlpha > 0f) injected.text.alpha = requestedAlpha
        if (injected.text.visibility == View.VISIBLE && view.alpha != 0f) {
            alphaGuard.set(true)
            try { view.alpha = 0f } finally { alphaGuard.remove() }
        }
    }

    fun restoreAll() = guarded {
        restoreKeyguardNativeSystemIcons()
        keyguardMergedByContainer.values.toList().forEach(::restoreKeyguardMerged)
        mergedByBattery.values.toList().forEach(::restoreMerged)
        liveViews().forEach(::restore)
        composeMobile.values.toList().forEach(::restoreCompose)
        composeMobile.clear()
        mergedByBattery.clear()
        keyguardMergedByContainer.clear()
        byOriginal.clear()
        byNetworkType.clear()
        byMobileActivity.clear()
        all.clear()
    }

    private fun inject(image: ImageView, role: ViewRole, hintedSubId: Int) {
        val parent = image.parent as? ViewGroup ?: return
        if (image.getTag(R.id.signal_number_overlay) != null) return
        val index = parent.indexOfChild(image).takeIf { it >= 0 } ?: return
        val oldParams = image.layoutParams
        val oldAlpha = image.alpha
        val subId = if (role == ViewRole.MOBILE) resolveSubscriptionId(image, hintedSubId) else -1
        val slot = if (role == ViewRole.MOBILE) resolveSlotIndex(image) else -1
        // SystemUI may temporarily reparent this image while rebuilding the
        // shade. Remember a lower duplicate row as soon as it is identifiable,
        // so a transient parent chain cannot make it render on top of Compose.
        val expandedShadeSignalRow = isExpandedShadeSignal(image)
        val wrapper = FrameLayout(image.context).apply {
            layoutParams = copyLayoutParams(oldParams, numberWidth(image, role))
            minimumWidth = dp(image, if (config.unitMode == ModuleConfig.UNIT_DBM) 32 else 25)
            minimumHeight = max(image.measuredHeight, dp(image, 12))
            clipChildren = false
            clipToPadding = false
        }
        val text = TextView(image.context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            minimumWidth = numberWidth(image, role)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        }
        var removed = false
        try {
            parent.removeViewAt(index)
            removed = true
            image.layoutParams = FrameLayout.LayoutParams(
                if (oldParams.width > 0) oldParams.width else ViewGroup.LayoutParams.WRAP_CONTENT,
                if (oldParams.height > 0) oldParams.height else ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
            wrapper.addView(image)
            wrapper.addView(text)
            parent.addView(wrapper, index)
            image.setTag(R.id.signal_number_overlay, text)
            val networkTypes = if (role == ViewRole.MOBILE) {
                findRelatedViews(image, compatibility.mobileTypeResourceNames)
            } else {
                emptyList()
            }
            val mobileActivityViews = if (role == ViewRole.MOBILE) {
                findRelatedViews(image, compatibility.mobileActivityResourceNames)
            } else {
                emptyList()
            }
            val injected = InjectedSignalView(
                role, image, wrapper, text, parent, index, oldParams, oldAlpha,
                networkTypes, networkTypes.associateWith { it.visibility }, mobileActivityViews, subId, slot,
                expandedShadeSignalRow = expandedShadeSignalRow,
            )
            byOriginal[image] = injected
            networkTypes.forEach { byNetworkType[it] = injected }
            mobileActivityViews.forEach { byMobileActivity[it] = injected }
            all += WeakReference(injected)
            onEvent("injected role=$role resource=${locator.resourceName(image)} typeViews=${networkTypes.map(locator::resourceName)} activityViews=${mobileActivityViews.map(locator::resourceName)} subId=$subId slot=$slot parent=${parent.javaClass.name} root=${image.rootView.javaClass.name} attached=${image.isAttachedToWindow} id=${System.identityHashCode(image)}")
            applyStyle(injected)
            render(injected)
        } catch (t: Throwable) {
            runCatching { wrapper.removeView(image) }
            if (removed && image.parent == null) runCatching { parent.addView(image, index.coerceAtMost(parent.childCount), oldParams) }
            image.alpha = oldAlpha
            throw t
        }
    }

    private fun renderAll() {
        liveViews().forEach {
            applyStyle(it)
            render(it)
        }
        composeMobile.values.toList().forEach {
            applyComposeStyle(it)
            renderCompose(it)
        }
        mergedByBattery.values.toList().forEach {
            applyMergedStyle(it)
            renderMerged(it)
        }
        keyguardMergedByContainer.values.toList().forEach(::renderKeyguardMerged)
    }

    private fun ensureMergedBatteryViews(root: ViewGroup) {
        if (mergedByBattery.values.any { view ->
                view.host.parent != null &&
                    isDescendant(view.host, root) &&
                    isVisibleInHierarchy(view.host)
            }) return
        locator.locateBatteryViews(
            root,
            compatibility.batteryViewClassNames,
            compatibility.batteryViewResourceNames,
        ).forEach(::injectBatteryView)
    }

    private fun updateShadeState(source: String) {
        val previousExpanded = shadeExpanded
        val previousAppearanceExpanded = shadeAppearanceExpanded
        val previousForcedWhite = isForcedWhite()
        shadeExpanded = (shadePanelFullyExpanded || controlCenterFullyExpanded) && keyguardLocked != true
        shadeAppearanceExpanded = shadePanelFullyExpanded || controlCenterFullyExpanded
        if (!previousAppearanceExpanded && shadeAppearanceExpanded) {
            // Start a new shade appearance sample on every expansion. The
            // previous shade may have used the opposite light/dark palette.
            shadeAppearanceTint = null
            shadeMobileAppearanceTint = null
            shadeWifiAppearanceTint = null
        }
        if (shadeAppearanceExpanded && shadeAppearanceTint == null) {
            // The expansion callback can arrive before the first shade tint
            // callback. Use the current signal tint as the initial value so a
            // stale top-bar tint cannot remain on the merged element.
            shadeAppearanceTint = mobileAppearanceTint ?: wifiAppearanceTint
        }
        val forcedWhite = isForcedWhite()
        // A locked expanded shade can reuse a battery anchor whose parent is
        // temporarily hidden. Do not recalculate host visibility merely
        // because its tint surface changed; update colors below instead.
        if (previousExpanded != shadeExpanded) renderAll()
        if (previousExpanded != shadeExpanded ||
            previousAppearanceExpanded != shadeAppearanceExpanded ||
            previousForcedWhite != forcedWhite
        ) {
            liveViews().forEach {
                copyTint(it)
                it.text.alpha = it.originalAlpha
            }
            composeMobile.values.toList().forEach(::copyComposeTint)
            mergedByBattery.values.toList().forEach(::copyMergedTint)
            keyguardMergedByContainer.values.toList().forEach(::copyKeyguardMergedTint)
        }
        if (previousExpanded != shadeExpanded ||
            previousAppearanceExpanded != shadeAppearanceExpanded ||
            previousForcedWhite != forcedWhite
        ) {
            onEvent(
                "shade expanded=$shadeExpanded source=$source " +
                    "panel=$shadePanelFullyExpanded controlFull=$controlCenterFullyExpanded " +
                    "controlVisible=$controlCenterVisible appearanceExpanded=$shadeAppearanceExpanded " +
                    "tint=${if (forcedWhite) "ffffffff" else "appearance"}",
            )
        }
    }

    private fun isForcedWhite(): Boolean =
        (keyguardLocked == true && compatibility.forceWhiteOnKeyguard) ||
            (shadeExpanded && compatibility.forceWhiteInExpandedShade) ||
            controlCenterVisible

    /**
     * The fully expanded shade has a second signal row below the status bar.
     * The top status-bar views can use the same leaf class (notably Wi-Fi), so
     * classify the lower row by its shade-specific ancestor instead of by the
     * leaf view class alone.
     */
    private fun isExpandedShadeSignal(view: View): Boolean {
        var current: View? = view
        var depth = 0
        while (current != null && depth++ < 24) {
            val name = current.javaClass.name
            if (
                name == "com.android.systemui.shade.NotificationPanelView" ||
                name == "com.android.systemui.shade.NotificationsQuickSettingsContainer" ||
                name.contains("ModernShadeCarrierGroupMobileView")
            ) return true
            current = current.parent as? View
        }
        return false
    }

    private fun renderCompose(view: ComposeSignalView) {
        if (compatibility.mergedSignalDisplay) {
            hideComposeForMergedDisplay(view)
            return
        }
        if (!config.enabled || config.safeMode || !config.mobileEnabled) return restoreCompose(view)
        if (shouldHideExpandedShadeSignalRow(view.root)) {
            view.compose.alpha = 0f
            view.text.visibility = View.GONE
            return
        }
        val readings = snapshot.mobileBySubscription.values
            .filter(::isComposeSlotEnabled)
            .sortedWith(compareBy<MobileReading> { if (it.slotIndex >= 0) it.slotIndex else Int.MAX_VALUE }
                .thenBy { it.subscriptionId })
            .mapNotNull { reading -> composeReadingValue(reading)?.let { reading to it } }
        if (readings.isEmpty()) {
            view.compose.alpha = view.originalAlpha
            view.text.visibility = View.GONE
            return
        }

        val rendered = SpannableStringBuilder()
        readings.forEachIndexed { index, (reading, value) ->
            if (index > 0) rendered.append(" / ")
            rendered.append(format(value, mobileRadioLabel(reading.radioFamily)))
        }
        view.text.text = rendered
        fitComposeWidth(view)
        view.text.visibility = if (view.root.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        view.compose.alpha = 0f
        onEvent("rendered compose mobile text=${view.text.text} slots=${readings.map { it.first.slotIndex }}")
    }

    private fun hideComposeForMergedDisplay(view: ComposeSignalView) {
        visibilityGuard.set(true)
        try {
            if (view.text.visibility != View.GONE) view.text.visibility = View.GONE
        } finally {
            visibilityGuard.remove()
        }
        alphaGuard.set(true)
        try {
            if (view.compose.alpha != 0f) view.compose.alpha = 0f
        } finally {
            alphaGuard.remove()
        }
    }

    private fun isComposeSlotEnabled(reading: MobileReading): Boolean =
        (reading.slotIndex != 0 || config.sim1Enabled) &&
            (reading.slotIndex != 1 || config.sim2Enabled)

    private fun composeReadingValue(reading: MobileReading): String? = when {
        reading.airplaneMode -> "—"
        !reading.inService || reading.dbm == null -> emptyMode(config.noServiceMode)
        else -> number(reading.dbm)
    }

    private fun applyComposeStyle(view: ComposeSignalView) {
        view.text.typeface = Typeface.create("sans-serif-condensed", if (config.bold) Typeface.BOLD else Typeface.NORMAL)
        view.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.fontSizeSp)
        view.root.minimumWidth = max(view.root.minimumWidth, numberWidth(view.root, ViewRole.MOBILE))
        view.text.minimumWidth = numberWidth(view.root, ViewRole.MOBILE)
        view.text.setPadding(0, 0, 0, 0)
        if (view.text.currentTextColor == 0) {
            val value = TypedValue()
            if (view.root.context.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)) view.text.setTextColor(value.data)
        }
    }

    private fun restoreCompose(view: ComposeSignalView) {
        view.compose.alpha = view.originalAlpha
        view.text.visibility = View.GONE
    }

    private fun render(view: InjectedSignalView) {
        if (!config.enabled || config.safeMode) return restore(view)
        if (compatibility.mergedSignalDisplay && (view.role == ViewRole.MOBILE || view.role == ViewRole.WIFI)) {
            hideSignalForMergedDisplay(view)
            return
        }
        view.wrapper.visibility = View.VISIBLE
        if ((view.role == ViewRole.MOBILE || view.role == ViewRole.WIFI) &&
            shouldHideExpandedShadeSignalRow(view)
        ) {
            // Hide only the duplicate signal row below the status bar while
            // the shade is fully expanded. Top status-bar, desktop, and
            // lockscreen views remain rendered.
            view.original.alpha = 0f
            view.text.visibility = View.GONE
            view.wrapper.visibility = View.GONE
            hideNetworkType(view)
            onEvent("hidden expanded shade row role=${view.role} slot=${view.slotIndex} id=${System.identityHashCode(view.original)}")
            return
        }
        if (shouldHideTraditionalMobileView(view)) {
            view.original.alpha = 0f
            view.text.visibility = View.GONE
            view.wrapper.visibility = View.GONE
            hideNetworkType(view)
            onEvent("hidden traditional mobile view while compose active slot=${view.slotIndex} id=${System.identityHashCode(view.original)}")
            return
        }
        val replacement = when (view.role) {
            ViewRole.MOBILE -> mobileText(view)
            ViewRole.WIFI -> wifiText()
            else -> null
        }
        if (replacement == null) {
            view.original.alpha = view.originalAlpha
            view.text.visibility = View.GONE
            restoreNetworkType(view)
            return
        }
        val label = when (view.role) {
            ViewRole.MOBILE -> mobileLabel(view)
            ViewRole.WIFI -> "WiFi"
            else -> null
        }
        view.text.text = format(replacement, label)
        fitWidth(view)
        view.text.visibility = if (view.original.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        view.original.alpha = 0f
        hideNetworkType(view)
        copyTint(view)
        onEvent("rendered role=${view.role} text=${view.text.text} subId=${view.subscriptionId} slot=${view.slotIndex} originalVisibility=${view.original.visibility} shown=${view.wrapper.isShown} id=${System.identityHashCode(view.original)}")
    }

    private fun hideSignalForMergedDisplay(view: InjectedSignalView) {
        view.original.alpha = 0f
        view.text.visibility = View.GONE
        view.wrapper.visibility = View.GONE
        hideNetworkType(view)
        onEvent("hidden native signal for merged display role=${view.role} slot=${view.slotIndex} id=${System.identityHashCode(view.original)}")
    }

    private fun renderMerged(view: MergedSignalView) {
        if (!compatibility.mergedSignalDisplay || !config.enabled || config.safeMode) return
        hideMergedOriginal(view)
        val segments = mergedSegments()
        if (segments.isEmpty()) {
            view.text.visibility = View.GONE
            view.host.visibility = View.GONE
            return
        }
        val rendered = SpannableStringBuilder()
        segments.forEachIndexed { index, segment ->
            if (index > 0) rendered.append(" / ")
            rendered.append(segment)
        }
        view.text.text = rendered
        fitMergedWidth(view)
        view.text.visibility = View.VISIBLE
        view.host.visibility = mergedHostVisibility(view)
        view.host.alpha = view.requestedAlpha
        copyMergedTint(view)
        onEvent("rendered merged status text=${view.text.text} visibility=${view.host.visibility} anchor=${locator.resourceName(view.original)}")
    }

    /**
     * PJZ110's lockscreen battery composables sit below a GONE/INVISIBLE
     * branch of the status-bar hierarchy. They are useful as a desktop
     * layout anchor, but no child of that branch can be drawn on the
     * lockscreen. This overlay is attached directly to the visible end-side
     * container instead, which is shared by the visible lockscreen status bar.
     */
    private fun ensureKeyguardMergedView(root: ViewGroup) {
        if (!compatibility.mergedSignalDisplay || keyguardLocked != true) return
        hideKeyguardNativeSystemIcons(root)
        val candidates = mutableListOf<View>()
        findNamedViews(root, setOf("status_bar_end_side_container"), candidates)
        val container = candidates
            .asSequence()
            .filterIsInstance<ViewGroup>()
            .firstOrNull(::isVisibleInHierarchy)
            ?: return
        val existing = keyguardMergedByContainer[container]
        if (existing != null && existing.host.parent === container) return

        val host = FrameLayout(container.context).apply {
            clipChildren = false
            clipToPadding = false
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = keyguardOverlayLayoutParams(container)
        }
        val text = TextView(container.context).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            )
        }
        host.addView(text)
        val merged = KeyguardMergedSignalView(container, host, text)
        try {
            container.addView(host, host.layoutParams)
            keyguardMergedByContainer[container] = merged
            onEvent("injected keyguard merged status element container=${container.javaClass.name} id=${System.identityHashCode(container)}")
            renderKeyguardMerged(merged)
        } catch (t: Throwable) {
            runCatching { host.removeView(text) }
            runCatching { container.removeView(host) }
            throw t
        }
    }

    private fun keyguardOverlayLayoutParams(container: ViewGroup): ViewGroup.LayoutParams = when (container) {
        is FrameLayout -> FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.FILL,
        )
        is LinearLayout -> LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        else -> ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private fun renderKeyguardMerged(view: KeyguardMergedSignalView) {
        if (!compatibility.mergedSignalDisplay || !config.enabled || config.safeMode || keyguardLocked != true) {
            view.text.visibility = View.GONE
            view.host.visibility = View.GONE
            return
        }
        val segments = mergedSegments()
        if (segments.isEmpty()) {
            view.text.visibility = View.GONE
            view.host.visibility = View.GONE
            return
        }
        val rendered = SpannableStringBuilder()
        segments.forEachIndexed { index, segment ->
            if (index > 0) rendered.append(" / ")
            rendered.append(segment)
        }
        view.text.text = rendered
        applyKeyguardMergedStyle(view)
        view.text.visibility = View.VISIBLE
        view.host.visibility = View.VISIBLE
        view.host.alpha = 1f
        // SystemUI can append its native battery child after the shade starts
        // opening. Keep the replacement above that child on every refresh.
        view.host.bringToFront()
    }

    private fun applyKeyguardMergedStyle(view: KeyguardMergedSignalView) {
        view.text.typeface = Typeface.create("sans-serif-condensed", if (config.bold) Typeface.BOLD else Typeface.NORMAL)
        view.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.fontSizeSp)
        view.text.setPadding(0, 0, 0, 0)
        view.text.includeFontPadding = false
        view.text.maxLines = 1
        view.text.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        // The visible lockscreen status-bar appearance callback is the
        // authoritative source. While the shade is expanded over keyguard,
        // use its appearance tint instead of the lockscreen-only tint.
        val tint = if (isForcedWhite()) {
            Color.WHITE
        } else if (isExpandedShadeTintAppearance()) {
            expandedShadeTint() ?: statusAppearanceTint
        } else {
            statusAppearanceTint
        }
        view.text.setTextColor(tint ?: Color.WHITE)
    }

    private fun copyKeyguardMergedTint(view: KeyguardMergedSignalView) {
        applyKeyguardMergedStyle(view)
    }

    private fun isVisibleInHierarchy(view: View): Boolean {
        var current: View? = view
        repeat(24) {
            val item = current ?: return true
            if (item.visibility != View.VISIBLE || item.alpha <= 0f) return false
            current = item.parent as? View
        }
        return true
    }

    private fun restoreKeyguardMerged(view: KeyguardMergedSignalView) {
        runCatching { (view.host.parent as? ViewGroup)?.removeView(view.host) }.onFailure(onError)
    }

    /**
     * PJZ110's visible lockscreen status bar still draws its native
     * `system_icons` group. Its battery percentage would therefore be drawn
     * on top of the merged lockscreen element. Hide that native group while
     * the replacement is active, then restore its original state after
     * unlocking.
     */
    private fun hideKeyguardNativeSystemIcons(root: ViewGroup) {
        val candidates = mutableListOf<View>()
        findNamedViews(root, setOf("system_icons"), candidates)
        var newlyHidden = 0
        candidates.forEach { view ->
            if (keyguardHiddenSystemIcons.putIfAbsent(view, HiddenViewState(view.visibility, view.alpha)) == null) {
                newlyHidden++
            }
            visibilityGuard.set(true)
            try {
                if (view.visibility != View.GONE) view.visibility = View.GONE
            } finally {
                visibilityGuard.remove()
            }
            alphaGuard.set(true)
            try {
                if (view.alpha != 0f) view.alpha = 0f
            } finally {
                alphaGuard.remove()
            }
        }
        if (newlyHidden > 0) onEvent("hidden keyguard native system icons count=$newlyHidden")
    }

    private fun restoreKeyguardNativeSystemIcons() {
        keyguardHiddenSystemIcons.entries.toList().forEach { (view, state) ->
            visibilityGuard.set(true)
            try {
                view.visibility = state.visibility
            } finally {
                visibilityGuard.remove()
            }
            alphaGuard.set(true)
            try {
                view.alpha = state.alpha
            } finally {
                alphaGuard.remove()
            }
        }
        keyguardHiddenSystemIcons.clear()
    }

    private fun mergedHostVisibility(view: MergedSignalView): Int {
        if (view.requestedVisibility == View.VISIBLE) return View.VISIBLE
        // The original battery child is only a layout anchor. PJZ110 can mark
        // either the legacy or Compose child GONE while the surrounding status
        // region remains visible. Keep the merged element visible in that
        // region so lockscreen and desktop do not lose the replacement.
        val isResourceAnchor = locator.resourceName(view.original) in compatibility.batteryViewResourceNames
        if ((isLegacyBatteryView(view.original) || isResourceAnchor) &&
            view.originalParent.visibility == View.VISIBLE
        ) {
            return View.VISIBLE
        }
        return view.requestedVisibility
    }

    private fun isMergedBatteryAnchor(view: ViewGroup): Boolean {
        val resourceName = locator.resourceName(view)
        if (resourceName in compatibility.batteryViewResourceNames) return true
        if (compatibility.batteryViewResourceNames.isNotEmpty()) return false
        if (view.javaClass.name !in compatibility.batteryViewClassNames) return false
        return true
    }

    private fun isLegacyBatteryView(view: View): Boolean =
        view.javaClass.name in compatibility.batteryViewClassNames

    private fun mergedSegments(): List<CharSequence> {
        val segments = mutableListOf<CharSequence>()
        if (config.mobileEnabled) {
            snapshot.mobileBySlot.values
                .filter { it.slotIndex >= 0 && isComposeSlotEnabled(it) }
                .distinctBy { it.slotIndex }
                .sortedBy { it.slotIndex }
                .forEach { reading ->
                    composeReadingValue(reading)?.let { value ->
                        segments += format(value, mobileRadioLabel(reading.radioFamily))
                    }
                }
        }
        val wifiRssi = snapshot.wifi.rssi
        if (config.wifiEnabled && snapshot.wifi.connected && wifiRssi != null) {
            segments += format(number(wifiRssi), "WiFi")
        }
        snapshot.batteryPercent?.let { percent -> segments += "$percent%" }
        return segments
    }

    private fun hideMergedOriginal(view: MergedSignalView) {
        visibilityGuard.set(true)
        try {
            if (view.original.visibility != View.GONE) view.original.visibility = View.GONE
        } finally {
            visibilityGuard.remove()
        }
        alphaGuard.set(true)
        try {
            if (view.original.alpha != 0f) view.original.alpha = 0f
        } finally {
            alphaGuard.remove()
        }
    }

    private fun applyMergedStyle(view: MergedSignalView) {
        view.text.typeface = Typeface.create("sans-serif-condensed", if (config.bold) Typeface.BOLD else Typeface.NORMAL)
        view.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.fontSizeSp)
        view.text.setPadding(0, 0, 0, 0)
        view.text.includeFontPadding = false
        view.text.maxLines = 1
        copyMergedTint(view)
    }

    private fun fitMergedWidth(view: MergedSignalView) {
        val width = max(dp(view.host, 1), ceil(Layout.getDesiredWidth(view.text.text, view.text.paint).toDouble()).toInt() + dp(view.host, 3))
        val hostParams = view.host.layoutParams
        if (hostParams.width != width) {
            hostParams.width = width
            view.host.layoutParams = hostParams
        }
        view.host.minimumWidth = width
        val textParams = (view.text.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        textParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        textParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        textParams.gravity = Gravity.CENTER
        textParams.leftMargin = 0
        textParams.rightMargin = 0
        view.text.layoutParams = textParams
    }

    private fun copyMergedTint(view: MergedSignalView) {
        val percentView = findBatteryPercentView(view.original)
        val childTint = percentView?.currentTextColor?.takeIf { it ushr 24 != 0 }
        val regionTint = if (isExpandedShadeSignal(view.original)) shadeAppearanceTint else statusAppearanceTint
        val tint = if (isForcedWhite()) {
            Color.WHITE
        } else if (isExpandedShadeTintAppearance()) {
            // The merged battery anchors can be shared by multiple surfaces,
            // so their cached appearanceTint may still be the collapsed
            // status-bar color. The expanded shade's tint must win while it
            // is visible, with the live mobile/Wi-Fi tint as the first-frame
            // fallback.
            expandedShadeTint() ?:
                view.appearanceTint ?: childTint ?: statusAppearanceTint
        } else {
            view.appearanceTint ?: childTint ?: regionTint ?: mobileAppearanceTint ?: wifiAppearanceTint
        }
        if (tint != null) {
            view.text.setTextColor(tint)
        } else {
            val value = TypedValue()
            if (view.original.context.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
                val color = if (value.resourceId != 0) {
                    runCatching { view.original.context.getColorStateList(value.resourceId) }.getOrNull()
                } else {
                    ColorStateList.valueOf(value.data)
                }
                if (color != null) view.text.setTextColor(color)
            }
        }
        view.text.alpha = 1f
    }

    private fun findBatteryPercentView(group: ViewGroup): TextView? {
        val method = runCatching {
            group.javaClass.methods.firstOrNull { it.name == "getBatteryPercentView" && it.parameterTypes.isEmpty() }
        }.getOrNull()
        val result = runCatching { method?.invoke(group) as? TextView }.getOrNull()
        if (result != null) return result
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child is TextView && locator.resourceName(child) == "battery_percentage_view") return child
            if (child is ViewGroup) findBatteryPercentView(child)?.let { return it }
        }
        return null
    }

    private fun mobileText(view: InjectedSignalView): String? {
        if (!config.mobileEnabled) return null
        val reading = snapshot.mobileBySubscription[view.subscriptionId]
            ?: snapshot.mobileBySlot[view.slotIndex]
            ?: return null
        if (reading.slotIndex == 0 && !config.sim1Enabled) return null
        if (reading.slotIndex == 1 && !config.sim2Enabled) return null
        view.slotIndex = reading.slotIndex
        if (reading.airplaneMode) return "—"
        if (!reading.inService || reading.dbm == null) return emptyMode(config.noServiceMode)
        return number(reading.dbm)
    }

    private fun mobileLabel(view: InjectedSignalView): String? {
        val reading = snapshot.mobileBySubscription[view.subscriptionId]
            ?: snapshot.mobileBySlot[view.slotIndex]
            ?: return null
        return mobileRadioLabel(reading.radioFamily)
    }

    private fun mobileRadioLabel(family: String): String = when {
        family.contains("NR", true) -> "5G"
        family.contains("LTE", true) -> "4G"
        family.contains("WCDMA", true) || family.contains("UMTS", true) -> "3G"
        family.contains("GSM", true) || family.contains("EDGE", true) -> "2G"
        else -> "CELL"
    }

    private fun wifiText(): String? {
        if (!config.wifiEnabled) return null
        val wifi = snapshot.wifi
        if (!wifi.connected || wifi.rssi == null) return emptyMode(config.wifiDisconnectedMode)
        return number(wifi.rssi)
    }

    private fun number(value: Int): String = if (config.showMinus) value.toString() else kotlin.math.abs(value).toString()

    private fun emptyMode(mode: Int): String? = when (mode) {
        ModuleConfig.EMPTY_CROSS -> "×"
        ModuleConfig.EMPTY_DASH -> "—"
        else -> null
    }

    private fun format(value: String, label: String? = null): CharSequence {
        val prefix = label?.let { "$it\u2009" }.orEmpty()
        val suffix = if (config.unitMode == ModuleConfig.UNIT_DBM && value != "×" && value != "—") " dBm" else ""
        if (prefix.isEmpty() && suffix.isEmpty()) return value
        val result = SpannableString("$prefix$value$suffix")
        if (prefix.isNotEmpty()) {
            result.setSpan(RelativeSizeSpan(0.62f), 0, label!!.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            result.setSpan(StyleSpan(Typeface.NORMAL), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            result.setSpan(BaselineShiftSpan(0.18f), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (suffix.isNotEmpty()) {
            val start = prefix.length + value.length
            result.setSpan(RelativeSizeSpan(0.58f), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return result
    }

    private fun applyStyle(view: InjectedSignalView) {
        view.text.typeface = Typeface.create("sans-serif-condensed", if (config.bold) Typeface.BOLD else Typeface.NORMAL)
        view.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.fontSizeSp)
        view.wrapper.minimumWidth = numberWidth(view.wrapper, view.role)
        view.text.minimumWidth = numberWidth(view.wrapper, view.role)
        view.text.setPadding(0, 0, 0, 0)
        applyTextLayout(view)
        copyTint(view)
    }

    private fun fitWidth(view: InjectedSignalView) {
        val contentWidth = contentWidth(view.text, view.role)
        val totalWidth = contentWidth + mobileActivityInset(view)
        val params = view.wrapper.layoutParams
        if (params.width != totalWidth) {
            params.width = totalWidth
            view.wrapper.layoutParams = params
        }
        view.wrapper.minimumWidth = totalWidth
        view.text.minimumWidth = contentWidth
        applyTextLayout(view)
    }

    private fun applyTextLayout(view: InjectedSignalView) {
        val params = (view.text.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val inset = mobileActivityInset(view)
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        params.leftMargin = inset
        params.rightMargin = 0
        view.text.layoutParams = params
    }

    private fun mobileActivityInset(view: InjectedSignalView): Int {
        if (view.role != ViewRole.MOBILE) return 0
        val activity = view.mobileActivityViews
            .asSequence()
            .filter { it.visibility == View.VISIBLE }
            .maxByOrNull { max(it.width, it.measuredWidth) }
            ?: return 0
        val layoutWidth = activity.layoutParams?.width?.takeIf { it > 0 } ?: 0
        val measuredWidth = max(activity.width, max(activity.measuredWidth, layoutWidth))
        return if (measuredWidth > 0) measuredWidth + dp(view.original, 2) else dp(view.original, 9)
    }

    private fun fitComposeWidth(view: ComposeSignalView) {
        val width = contentWidth(view.text, ViewRole.MOBILE)
        view.root.minimumWidth = width
        view.text.minimumWidth = width
        val params = view.root.layoutParams ?: return
        // LineageOS/PJZ110 uses WRAP_CONTENT for the stacked mobile container.
        // minimumWidth alone is not propagated by its parent during the shade
        // re-layout, so the two-SIM text keeps being measured at the old icon
        // width and collides with the adjacent Wi-Fi slot.
        if (params.width == ViewGroup.LayoutParams.WRAP_CONTENT ||
            (params.width > 0 && params.width < width)
        ) {
            params.width = width
            view.root.layoutParams = params
        }
    }

    private fun contentWidth(text: TextView, role: ViewRole): Int {
        val measured = ceil(Layout.getDesiredWidth(text.text, text.paint).toDouble()).toInt()
        return max(numberWidth(text, role), measured + dp(text, 3))
    }

    private fun copyTint(view: InjectedSignalView) {
        if (isForcedWhite()) {
            view.text.setTextColor(Color.WHITE)
        } else {
            // The source tint is a PJZ110 shade-only fallback. Applying it to
            // every injected view makes a desktop/lockscreen number inherit
            // the last notification-shade color.
            val appearanceTint = view.appearanceTint ?: expandedShadeFallbackTint(view)
            if (appearanceTint != null) {
                view.text.setTextColor(appearanceTint)
            } else {
                val tint = view.original.imageTintList
                if (tint != null) {
                    view.text.setTextColor(tint.getColorForState(view.original.drawableState, tint.defaultColor))
                } else {
                    val value = TypedValue()
                    if (view.original.context.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
                        val color = if (value.resourceId != 0) runCatching { view.original.context.getColorStateList(value.resourceId) }.getOrNull() else ColorStateList.valueOf(value.data)
                        if (color != null) view.text.setTextColor(color)
                    }
                }
            }
        }
        view.text.alpha = view.originalAlpha
    }

    private fun copyComposeTint(view: ComposeSignalView) {
        when {
            isForcedWhite() -> view.text.setTextColor(Color.WHITE)
            isExpandedShadeAppearance() && mobileAppearanceTint != null -> view.text.setTextColor(mobileAppearanceTint!!)
            view.appearanceTint != null -> view.text.setTextColor(view.appearanceTint!!)
        }
    }

    private fun isExpandedShadeAppearance(): Boolean = shadeExpanded && keyguardLocked != true

    private fun isExpandedShadeTintAppearance(): Boolean = shadeAppearanceExpanded

    private fun expandedShadeTint(): Int? =
        shadeMobileAppearanceTint ?: mobileAppearanceTint ?: shadeAppearanceTint ?: shadeWifiAppearanceTint ?: wifiAppearanceTint

    private fun expandedShadeFallbackTint(view: InjectedSignalView): Int? =
        if (isExpandedShadeAppearance() && isExpandedShadeSignal(view)) {
            fallbackAppearanceTint(view.role)
        } else {
            null
        }

    private fun fallbackAppearanceTint(role: ViewRole): Int? = when (role) {
        ViewRole.MOBILE -> mobileAppearanceTint
        ViewRole.WIFI -> wifiAppearanceTint
        else -> null
    }

    private fun shouldHideExpandedShadeSignalRow(view: View): Boolean {
        if (!isExpandedShadeSignal(view)) return false
        val hideWhenCollapsed = compatibility.showExpandedShadeSignalRowOnlyWhenExpanded && !isExpandedShadeAppearance()
        val hideInExpandedShade = compatibility.hideExpandedShadeSignalRow && isExpandedShadeAppearance()
        val hideOnKeyguard = compatibility.hideExpandedShadeSignalRowOnKeyguard && keyguardLocked == true
        return hideWhenCollapsed || hideInExpandedShade || hideOnKeyguard
    }

    private fun shouldHideExpandedShadeSignalRow(view: InjectedSignalView): Boolean {
        // Keep the classification sticky across SystemUI reparenting. The
        // current ancestor chain is still checked to catch rows discovered
        // before their final shade container is attached.
        if (isExpandedShadeSignal(view.original)) view.expandedShadeSignalRow = true
        if (!view.expandedShadeSignalRow) return false
        val hideWhenCollapsed = compatibility.showExpandedShadeSignalRowOnlyWhenExpanded && !isExpandedShadeAppearance()
        val hideInExpandedShade = compatibility.hideExpandedShadeSignalRow && isExpandedShadeAppearance()
        val hideOnKeyguard = compatibility.hideExpandedShadeSignalRowOnKeyguard && keyguardLocked == true
        return hideWhenCollapsed || hideInExpandedShade || hideOnKeyguard
    }

    private fun isExpandedShadeSignal(view: InjectedSignalView): Boolean =
        view.expandedShadeSignalRow || isExpandedShadeSignal(view.original)

    private fun shouldHideTraditionalMobileView(view: InjectedSignalView): Boolean {
        if (view.role != ViewRole.MOBILE ||
            compatibility.mode != CompatibilityMode.PJZ110_LINEAGE ||
            !compatibility.hideTraditionalMobileViewsWhenCompose
        ) return false
        if (isExpandedShadeSignal(view.original)) return false
        return composeMobile.values.any { it.root.visibility == View.VISIBLE }
    }

    private fun isDescendant(view: View, ancestor: View): Boolean {
        var current: View? = view
        repeat(12) {
            if (current === ancestor) return true
            current = (current?.parent as? View)
        }
        return false
    }

    private fun restoreMerged(view: MergedSignalView) {
        val parent = view.host.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(view.host).takeIf { it >= 0 } ?: view.originalIndex
        runCatching {
            visibilityGuard.set(true)
            try {
                view.host.removeView(view.original)
                parent.removeView(view.host)
                view.original.layoutParams = view.originalLayoutParams
                view.original.visibility = view.requestedVisibility
            } finally {
                visibilityGuard.remove()
            }
            alphaGuard.set(true)
            try {
                view.original.alpha = view.requestedAlpha
            } finally {
                alphaGuard.remove()
            }
            view.original.setTag(R.id.merged_signal_overlay, null)
            parent.addView(view.original, index.coerceAtMost(parent.childCount), view.originalLayoutParams)
        }.onFailure(onError)
    }

    private fun restore(view: InjectedSignalView) {
        val parent = view.wrapper.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(view.wrapper).takeIf { it >= 0 } ?: view.originalIndex
        runCatching {
            restoreNetworkType(view)
            view.wrapper.removeView(view.original)
            parent.removeView(view.wrapper)
            view.original.layoutParams = view.originalLayoutParams
            view.original.alpha = view.originalAlpha
            view.original.setTag(R.id.signal_number_overlay, null)
            parent.addView(view.original, index.coerceAtMost(parent.childCount), view.originalLayoutParams)
        }.onFailure(onError)
    }

    private fun hideNetworkType(view: InjectedSignalView) {
        visibilityGuard.set(true)
        try {
            view.networkTypeViews.forEach { type ->
                if (type.visibility != View.GONE) type.visibility = View.GONE
            }
        } finally {
            visibilityGuard.remove()
        }
    }

    private fun restoreNetworkType(view: InjectedSignalView) {
        visibilityGuard.set(true)
        try {
            view.networkTypeVisibilities.forEach { (type, visibility) -> type.visibility = visibility }
        } finally {
            visibilityGuard.remove()
        }
    }

    private fun resolveSubscriptionId(view: View, hint: Int): Int {
        if (hint >= 0) return hint
        return findNumericField(view, listOf("subId", "mSubId", "subscriptionId", "mSubscriptionId"))
    }

    private fun resolveSlotIndex(view: View): Int = findNumericField(view, listOf("slotIndex", "mSlotIndex", "simSlotIndex"))

    private fun findNumericField(start: View, names: List<String>): Int {
        var current: Any? = start
        repeat(7) {
            val obj = current ?: return -1
            var type: Class<*>? = obj.javaClass
            while (type != null) {
                for (name in names) {
                    val value = runCatching {
                        type.getDeclaredField(name).apply { isAccessible = true }.get(obj) as? Number
                    }.getOrNull()?.toInt()
                    if (value != null && value >= 0) return value
                }
                type = type.superclass
            }
            current = if (obj is View) obj.parent else null
        }
        return -1
    }

    private fun liveViews(): List<InjectedSignalView> {
        val values = all.mapNotNull { it.get() }
        all.removeAll { it.get() == null }
        return values
    }

    private fun dp(view: View?, value: Int) = ((view?.resources?.displayMetrics?.density ?: 1f) * value).toInt()

    private fun numberWidth(view: View?, role: ViewRole): Int {
        val width = when {
            config.unitMode == ModuleConfig.UNIT_DBM && role == ViewRole.WIFI -> 64
            config.unitMode == ModuleConfig.UNIT_DBM -> 58
            role == ViewRole.WIFI -> 47
            else -> 36
        }
        return dp(view, width)
    }

    private fun copyLayoutParams(source: ViewGroup.LayoutParams, width: Int): ViewGroup.LayoutParams {
        val copy = when (source) {
            is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(source)
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(source)
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(source)
            else -> ViewGroup.LayoutParams(source)
        }
        copy.width = width
        return copy
    }

    private fun findRelatedViews(start: View, resourceNames: Set<String>): List<View> {
        val result = mutableListOf<View>()
        var current: View? = start
        repeat(7) {
            val group = current as? ViewGroup
            if (group != null) findNamedViews(group, resourceNames, result)
            current = current?.parent as? View
        }
        return result.distinct()
    }

    private fun findNamedViews(group: ViewGroup, resourceNames: Set<String>, result: MutableList<View>) {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (locator.resourceName(child) in resourceNames) result += child
            if (child is ViewGroup) findNamedViews(child, resourceNames, result)
        }
    }

    private inline fun guarded(block: () -> Unit) {
        runCatching(block).onFailure(onError)
    }
}

private data class ComposeSignalView(
    val root: ViewGroup,
    val compose: View,
    val text: TextView,
    val originalAlpha: Float,
    var appearanceTint: Int? = null,
)

private data class MergedSignalView(
    val original: ViewGroup,
    val host: FrameLayout,
    val text: TextView,
    val originalParent: ViewGroup,
    val originalIndex: Int,
    val originalLayoutParams: ViewGroup.LayoutParams,
    val originalVisibility: Int,
    val originalAlpha: Float,
    var requestedVisibility: Int,
    var requestedAlpha: Float,
    var appearanceTint: Int? = null,
)

private data class KeyguardMergedSignalView(
    val container: ViewGroup,
    val host: FrameLayout,
    val text: TextView,
)

private data class HiddenViewState(
    val visibility: Int,
    val alpha: Float,
)
