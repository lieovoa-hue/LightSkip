package com.lightskip.app.util

import android.content.Context
import java.util.Locale
import kotlin.math.roundToInt

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

fun formatCount(value: Int): String = if (value < 10) "0$value" else value.toString()

fun appIconLetter(label: String): String {
    val first = label.trim().firstOrNull() ?: return "?"
    return first.toString().uppercase(Locale.getDefault())
}
