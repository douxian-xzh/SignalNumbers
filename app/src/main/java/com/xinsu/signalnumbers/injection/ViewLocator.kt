package com.xinsu.signalnumbers.injection

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.xinsu.signalnumbers.compatibility.CompatibilityAdapter
import com.xinsu.signalnumbers.compatibility.ViewRole

class ViewLocator(private val compatibility: CompatibilityAdapter) {
    data class Candidate(val image: ImageView, val role: ViewRole)

    fun locate(root: View, forcedRole: ViewRole? = null): List<Candidate> {
        val exact = mutableListOf<Candidate>()
        walk(root) { view ->
            if (view !is ImageView) return@walk
            val name = resourceName(view)
            when {
                forcedRole == ViewRole.MOBILE && name in compatibility.mobileResourceNames -> exact += Candidate(view, ViewRole.MOBILE)
                forcedRole == ViewRole.WIFI && name in compatibility.wifiResourceNames -> exact += Candidate(view, ViewRole.WIFI)
                name in compatibility.mobileResourceNames -> exact += Candidate(view, ViewRole.MOBILE)
                name in compatibility.wifiResourceNames -> exact += Candidate(view, ViewRole.WIFI)
            }
        }
        if (exact.isNotEmpty()) return exact.distinctBy { it.image }

        // Last-resort heuristic: only small status icon ImageViews with an explicit signal description.
        val heuristic = mutableListOf<Candidate>()
        walk(root) { view ->
            if (view !is ImageView || view.width > dp(view, 48) || view.height > dp(view, 48)) return@walk
            val description = view.contentDescription?.toString()?.lowercase().orEmpty()
            val name = resourceName(view).lowercase()
            val role = when {
                forcedRole == ViewRole.MOBILE -> ViewRole.MOBILE
                forcedRole == ViewRole.WIFI -> ViewRole.WIFI
                listOf("wifi", "wi-fi", "无线局域网").any { it in description || it in name } -> ViewRole.WIFI
                listOf("mobile", "cellular", "signal", "蜂窝", "移动网络", "信号").any { it in description || it in name } -> ViewRole.MOBILE
                else -> null
            }
            if (role != null && !name.contains("type") && !name.contains("roam")) heuristic += Candidate(view, role)
        }
        return heuristic.distinctBy { it.image }
    }

    fun locateBatteryViews(
        root: View,
        classNames: Set<String>,
        resourceNames: Set<String> = emptySet(),
    ): List<ViewGroup> {
        if (classNames.isEmpty() && resourceNames.isEmpty()) return emptyList()
        val resourceMatches = mutableListOf<ViewGroup>()
        val classMatches = mutableListOf<ViewGroup>()
        walk(root) { view ->
            if (view !is ViewGroup) return@walk
            if (resourceName(view) in resourceNames) resourceMatches += view
            if (view.javaClass.name in classNames) classMatches += view
        }
        // PJZ110 keeps the old BatteryMeterView in a zero-sized GONE wrapper
        // while the visible battery is drawn by one of these Compose anchors.
        // When resource anchors are declared, never fall back to a legacy class
        // in an unrelated inflated layout such as a notification section row.
        return if (resourceNames.isNotEmpty()) resourceMatches.distinct() else classMatches.distinct()
    }

    fun resourceName(view: View): String = runCatching {
        if (view.id == View.NO_ID) "" else view.resources.getResourceEntryName(view.id)
    }.getOrDefault("")

    private fun walk(view: View, visitor: (View) -> Unit) {
        visitor(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index), visitor)
    }

    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
}
