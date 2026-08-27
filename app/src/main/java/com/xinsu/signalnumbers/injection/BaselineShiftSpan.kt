package com.xinsu.signalnumbers.injection

import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import kotlin.math.roundToInt

/** Raises a small label without changing the baseline of the main dBm value. */
class BaselineShiftSpan(private val proportion: Float) : MetricAffectingSpan() {
    override fun updateDrawState(textPaint: TextPaint) = shift(textPaint)
    override fun updateMeasureState(textPaint: TextPaint) = shift(textPaint)

    private fun shift(textPaint: TextPaint) {
        textPaint.baselineShift -= (textPaint.textSize * proportion).roundToInt()
    }
}
