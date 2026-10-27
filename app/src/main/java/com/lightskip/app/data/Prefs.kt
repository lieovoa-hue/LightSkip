package com.lightskip.app.data

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("lightskip_prefs", Context.MODE_PRIVATE)

    var masterEnabled: Boolean
        get() = sp.getBoolean(KEY_MASTER, true)
        set(value) = sp.edit().putBoolean(KEY_MASTER, value).apply()

    var targetPackages: Set<String>
        get() = cachedTargets()
        set(value) {
            val copy = value.toSet()
            synchronized(TARGET_LOCK) { targetCache = copy }
            sp.edit().putStringSet(KEY_TARGETS, copy).apply()
        }

    var detectionWindowMs: Long
        get() = sp.getLong(KEY_WINDOW, 6_000L).coerceIn(2_000L, 15_000L)
        set(value) = sp.edit().putLong(KEY_WINDOW, value.coerceIn(2_000L, 15_000L)).apply()

    var maxAttempts: Int
        get() = sp.getInt(KEY_ATTEMPTS, 3).coerceIn(1, 8)
        set(value) = sp.edit().putInt(KEY_ATTEMPTS, value.coerceIn(1, 8)).apply()

    var compatibilityMode: Boolean
        get() = sp.getBoolean(KEY_COMPAT, true)
        set(value) = sp.edit().putBoolean(KEY_COMPAT, value).apply()

    var popupMode: PopupMode
        get() = PopupMode.from(sp.getString(KEY_POPUP, PopupMode.OFF.name))
        set(value) = sp.edit().putString(KEY_POPUP, value.name).apply()

    var learningMode: Boolean
        get() = sp.getBoolean(KEY_LEARNING, false)
        set(value) = sp.edit().putBoolean(KEY_LEARNING, value).apply()

    var learningTargetPackage: String
        get() = sp.getString(KEY_LEARNING_TARGET, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LEARNING_TARGET, value).apply()

    var learningArmed: Boolean
        get() = sp.getBoolean(KEY_LEARNING_ARMED, false)
        set(value) = sp.edit().putBoolean(KEY_LEARNING_ARMED, value).apply()

    var learningSavedCount: Int
        get() = sp.getInt(KEY_LEARNING_COUNT, 0).coerceIn(0, RuleLearningStore.MAX_RULES)
        set(value) = sp.edit().putInt(KEY_LEARNING_COUNT, value.coerceIn(0, RuleLearningStore.MAX_RULES)).apply()

    var closeAdEnabled: Boolean
        get() = sp.getBoolean(KEY_CLOSE_AD, false)
        set(value) = sp.edit().putBoolean(KEY_CLOSE_AD, value).apply()

    var vibrateEnabled: Boolean
        get() = sp.getBoolean(KEY_VIBRATE, false)
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

    val effectiveWindowMs: Long
        get() = if (compatibilityMode) 10_000L else detectionWindowMs

    val effectiveMaxAttempts: Int
        get() = if (compatibilityMode) 5 else maxAttempts

    fun isTarget(packageName: String): Boolean = cachedTargets().contains(packageName)

    fun setTarget(packageName: String, enabled: Boolean) {
        val current = cachedTargets().toMutableSet()
        if (enabled) current.add(packageName) else current.remove(packageName)
        targetPackages = current
    }

    fun startLearning(packageName: String) {
        sp.edit()
            .putString(KEY_LEARNING_TARGET, packageName)
            .putBoolean(KEY_LEARNING, true)
            .putBoolean(KEY_LEARNING_ARMED, false)
            .putInt(KEY_LEARNING_COUNT, 0)
            .apply()
    }

    fun stopLearning() {
        sp.edit()
            .putBoolean(KEY_LEARNING, false)
            .putString(KEY_LEARNING_TARGET, "")
            .putBoolean(KEY_LEARNING_ARMED, false)
            .apply()
    }

    private fun cachedTargets(): Set<String> {
        targetCache?.let { return it }
        synchronized(TARGET_LOCK) {
            targetCache?.let { return it }
            val loaded = sp.getStringSet(KEY_TARGETS, emptySet())?.toSet() ?: emptySet()
            targetCache = loaded
            return loaded
        }
    }

    private companion object {
        val TARGET_LOCK = Any()

        @Volatile
        var targetCache: Set<String>? = null

        const val KEY_MASTER = "master_enabled"
        const val KEY_TARGETS = "target_packages"
        const val KEY_WINDOW = "detection_window_ms"
        const val KEY_ATTEMPTS = "max_attempts"
        const val KEY_COMPAT = "compatibility_mode"
        const val KEY_POPUP = "popup_mode"
        const val KEY_LEARNING = "learning_mode"
        const val KEY_LEARNING_TARGET = "learning_target_package"
        const val KEY_LEARNING_ARMED = "learning_armed"
        const val KEY_LEARNING_COUNT = "learning_saved_count"
        const val KEY_CLOSE_AD = "close_ad_enabled"
        const val KEY_VIBRATE = "vibrate_enabled"
        const val KEY_STATS = "stats_enabled"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_CHOSEN_APPS = "has_chosen_apps"
    }
}
