package com.lightskip.app.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalStats(context: Context) {

    private val sp = context.getSharedPreferences("lightskip_stats", Context.MODE_PRIVATE)
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun recordSkip() {
        val key = dayKey("skip_count_")
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply()
    }

    fun todaySkips(): Int = sp.getInt(dayKey("skip_count_"), 0)

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

}
