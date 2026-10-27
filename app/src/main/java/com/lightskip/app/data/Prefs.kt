package com.lightskip.app.data

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("lightskip_prefs", Context.MODE_PRIVATE)

    var masterEnabled: Boolean
        get() = sp.getBoolean(KEY_MASTER, true)
        set(value) = sp.edit().putBoolean(KEY_MASTER, value).apply()

    var targetPackages: Set<String>
        get() = sp.getStringSet(KEY_TARGETS, emptySet())?.toMutableSet() ?: mutableSetOf()
        set(value) = sp.edit().putStringSet(KEY_TARGETS, value.toSet()).apply()

    var detectionWindowMs: Long
        get() = sp.getLong(KEY_WINDOW, 6_000L).coerceIn(2_000L, 15_000L)
        set(value) = sp.edit().putLong(KEY_WINDOW, value.coerceIn(2_000L, 15_000L)).apply()

    var maxAttempts: Int
        get() = sp.getInt(KEY_ATTEMPTS, 3).coerceIn(1, 8)
        set(value) = sp.edit().putInt(KEY_ATTEMPTS, value.coerceIn(1, 8)).apply()

    var closeAdEnabled: Boolean
        get() = sp.getBoolean(KEY_CLOSE_AD, false)
        set(value) = sp.edit().putBoolean(KEY_CLOSE_AD, value).apply()

    var vibrateEnabled: Boolean
        get() = sp.getBoolean(KEY_VIBRATE, true)
        set(value) = sp.edit().putBoolean(KEY_VIBRATE, value).apply()

    var statsEnabled: Boolean
        get() = sp.getBoolean(KEY_STATS, true)
        set(value) = sp.edit().putBoolean(KEY_STATS, value).apply()

    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDING, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDING, value).apply()

    var hasChosenApps: Boolean
        get() = sp.getBoolean(KEY_CHOSEN_APPS, false)
        set(value) = sp.edit().putBoolean(KEY_CHOSEN_APPS, value).apply()

    fun isTarget(packageName: String): Boolean = targetPackages.contains(packageName)

    fun setTarget(packageName: String, enabled: Boolean) {
        val current = targetPackages.toMutableSet()
        if (enabled) current.add(packageName) else current.remove(packageName)
        targetPackages = current
    }

    private companion object {
        const val KEY_MASTER = "master_enabled"
        const val KEY_TARGETS = "target_packages"
        const val KEY_WINDOW = "detection_window_ms"
        const val KEY_ATTEMPTS = "max_attempts"
        const val KEY_CLOSE_AD = "close_ad_enabled"
        const val KEY_VIBRATE = "vibrate_enabled"
        const val KEY_STATS = "stats_enabled"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_CHOSEN_APPS = "has_chosen_apps"
    }
}