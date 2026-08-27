package com.xinsu.signalnumbers.injection

import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.xinsu.signalnumbers.compatibility.ViewRole

data class InjectedSignalView(
    val role: ViewRole,
    val original: ImageView,
    val wrapper: FrameLayout,
    val text: TextView,
    val originalParent: ViewGroup,
    val originalIndex: Int,
    val originalLayoutParams: ViewGroup.LayoutParams,
    val originalAlpha: Float,
    val networkTypeViews: List<View> = emptyList(),
    var networkTypeVisibilities: Map<View, Int> = emptyMap(),
    val mobileActivityViews: List<View> = emptyList(),
    var subscriptionId: Int = -1,
    var slotIndex: Int = -1,
    var appearanceTint: Int? = null,
)
