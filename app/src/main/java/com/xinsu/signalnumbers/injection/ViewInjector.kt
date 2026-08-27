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
    private var mobileAppearanceTint: Int? = null
    private var wifiAppearanceTint: Int? = null

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
        }
        onComposeAppearanceChanged(root, tint)
        if (appearanceSourceRole == ViewRole.MOBILE) {
            composeMobile.values.toList().forEach {
                copyComposeTint(it)
            }
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
    }

    fun onKeyguardStateChanged(locked: Boolean) = guarded {
        onKeyguardStateChanged(locked, null)
    }

    private fun onKeyguardStateChanged(locked: Boolean, root: ViewGroup?) {
        val shadeWasExpanded = shadeExpanded
        val forcedWhiteBefore = isForcedWhite()
        val keyguardStateChanged = keyguardLocked != locked
        if (!keyguardStateChanged && !(locked && shadeWasExpanded)) return
        keyguardLocked = locked
        if (locked) {
            shadePanelFullyExpanded = false
            controlCenterFullyExpanded = false
            controlCenterVisible = false
            shadeExpanded = false
        }

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
        val injected = byOriginal[view as? ImageView ?: return@guarded] ?: return@guarded
        val requestedAlpha = view.alpha
        if (requestedAlpha > 0f) injected.text.alpha = requestedAlpha
        if (injected.text.visibility == View.VISIBLE && view.alpha != 0f) {
            alphaGuard.set(true)
            try { view.alpha = 0f } finally { alphaGuard.remove() }
        }
    }

    fun restoreAll() = guarded {
        liveViews().forEach(::restore)
        composeMobile.values.toList().forEach(::restoreCompose)
        composeMobile.clear()
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
    }

    private fun updateShadeState(source: String) {
        val previousExpanded = shadeExpanded
        val previousForcedWhite = isForcedWhite()
        shadeExpanded = (shadePanelFullyExpanded || controlCenterFullyExpanded) && keyguardLocked != true
        val forcedWhite = isForcedWhite()
        if (previousExpanded != shadeExpanded) renderAll()
        if (previousExpanded != shadeExpanded || previousForcedWhite != forcedWhite) {
            liveViews().forEach {
                copyTint(it)
                it.text.alpha = it.originalAlpha
            }
            composeMobile.values.toList().forEach(::copyComposeTint)
        }
        if (previousExpanded != shadeExpanded || previousForcedWhite != forcedWhite) {
            onEvent(
                "shade expanded=$shadeExpanded source=$source " +
                    "panel=$shadePanelFullyExpanded controlFull=$controlCenterFullyExpanded " +
                    "controlVisible=$controlCenterVisible tint=${if (forcedWhite) "ffffffff" else "appearance"}",
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
        view.wrapper.visibility = View.VISIBLE
        if ((view.role == ViewRole.MOBILE || view.role == ViewRole.WIFI) &&
            shouldHideExpandedShadeSignalRow(view.original)
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

    private fun expandedShadeFallbackTint(view: InjectedSignalView): Int? =
        if (isExpandedShadeAppearance() && isExpandedShadeSignal(view.original)) {
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
