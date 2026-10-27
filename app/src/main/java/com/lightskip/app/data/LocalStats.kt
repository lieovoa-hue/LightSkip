package com.lightskip.app.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalStats(context: Context, private val prefs: Prefs) {

    private val sp = context.getSharedPreferences("lightskip_stats", Context.MODE_PRIVATE)
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    data class LastSkip(val packageName: String, val at: Long)

    fun recordSkip(packageName: String, responseMs: Long) {
        if (!prefs.statsEnabled) return
        val editor = sp.edit()
        val key = dayKey("skip_count_")
        editor.putInt(key, sp.getInt(key, 0) + 1)
        editor.putString(KEY_LAST_PACKAGE, packageName)
        editor.putLong(KEY_LAST_AT, System.currentTimeMillis())
        if (responseMs in 0..15_000) {
            editor.putLong(KEY_RESPONSE_TOTAL, sp.getLong(KEY_RESPONSE_TOTAL, 0L) + responseMs)
            editor.putInt(KEY_RESPONSE_SAMPLES, sp.getInt(KEY_RESPONSE_SAMPLES, 0) + 1)
        }
        editor.apply()
    }

    fun recordSession() {
        if (!prefs.statsEnabled) return
        val key = dayKey("session_count_")
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply()
    }

    fun todaySkips(): Int = sp.getInt(dayKey("skip_count_"), 0)

    fun todaySessions(): Int = sp.getInt(dayKey("session_count_"), 0)

    fun averageResponseMs(): Long? {
        val samples = sp.getInt(KEY_RESPONSE_SAMPLES, 0)
        if (samples <= 0) return null
        return sp.getLong(KEY_RESPONSE_TOTAL, 0L) / samples
    }

    fun lastSkip(): LastSkip? {
        val packageName = sp.getString(KEY_LAST_PACKAGE, null) ?: return null
        val at = sp.getLong(KEY_LAST_AT, 0L)
        if (at <= 0L) return null
        return LastSkip(packageName, at)
    }

    fun clear() {
        val editor = sp.edit()
        sp.all.keys
            .filter { it.startsWith("skip_count_") || it.startsWith("session_count_") }
            .forEach { editor.remove(it) }
        editor.remove(KEY_LAST_PACKAGE)
        editor.remove(KEY_LAST_AT)
        editor.remove(KEY_RESPONSE_TOTAL)
        editor.remove(KEY_RESPONSE_SAMPLES)
        editor.apply()
    }

    fun prune() {
        val cutoff = dayFormat.format(Date(System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L))
        val editor = sp.edit()
        sp.all.keys
            .filter { it.startsWith("skip_count_") || it.startsWith("session_count_") }
            .forEach { key ->
                val day = key.substringAfterLast('_')
                if (day.length == 8 && day < cutoff) editor.remove(key)
            }
        editor.apply()
    }

    private fun dayKey(prefix: String): String = prefix + dayFormat.format(Date())

    private companion object {
        const val KEY_LAST_PACKAGE = "last_package"
        const val KEY_LAST_AT = "last_at"
        const val KEY_RESPONSE_TOTAL = "response_total"
        const val KEY_RESPONSE_SAMPLES = "response_samples"
    }
}