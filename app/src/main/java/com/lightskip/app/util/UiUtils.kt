package com.lightskip.app.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

fun Context.colorRes(id: Int): Int = getColor(id)

fun View.dp(value: Int): Int = context.dp(value)

fun ImageView.setTintColor(color: Int) {
    setColorFilter(color)
}

fun TextView.setCompoundDrawableTintColor(color: Int) {
    compoundDrawableTintList = ColorStateList.valueOf(color)
}

fun View.applyRoundedBackground(color: Int, radiusDp: Float) {
    background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(1) * radiusDp
        setColor(color)
    }
}

fun formatCount(value: Int): String = if (value < 10) "0$value" else value.toString()

fun formatSeconds(ms: Long?): String {
    if (ms == null) return "--"
    val seconds = ms / 1000.0
    return String.format(Locale.US, "%.1f", seconds)
}

fun relativeTime(at: Long): CharSequence = DateUtils.getRelativeTimeSpanString(
    at,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS
)

fun appIconLetter(label: String): String {
    val first = label.trim().firstOrNull() ?: return "?"
    return first.toString().uppercase(Locale.getDefault())
}