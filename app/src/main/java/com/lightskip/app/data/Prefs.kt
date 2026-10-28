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

    var compatibilityMode: Boolean
        get() = sp.getBoolean(KEY_COMPAT, true)
        set(value) = sp.edit().putBoolean(KEY_COMPAT, value).apply()

    var popupMode: PopupMode
        get() = PopupMode.from(sp.getString(KEY_POPUP, PopupMode.OFF.name))
        set(value) = sp.edit().putString(KEY_POPUP, value.name).apply()

    var hideBackgroundTask: Boolean
        get() = sp.getBoolean(KEY_HIDE_BACKGROUND, false)
        set(value) = sp.edit().putBoolean(KEY_HIDE_BACKGROUND, value).apply()

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

    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDING, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDING, value).apply()

    var hasChosenApps: Boolean
        get() = sp.getBoolean(KEY_CHOSEN_APPS, false)
        set(value) = sp.edit().putBoolean(KEY_CHOSEN_APPS, value).apply()

    val effectiveWindowMs: Long
        get() = if (compatibilityMode) 10_000L else 6_000L

    val effectiveMaxAttempts: Int
        get() = if (compatibilityMode) 5 else 3

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
        const val KEY_COMPAT = "compatibility_mode"
        const val KEY_POPUP = "popup_mode"
        const val KEY_HIDE_BACKGROUND = "hide_background_task"
        const val KEY_LEARNING = "learning_mode"
        const val KEY_LEARNING_TARGET = "learning_target_package"
        const val KEY_LEARNING_ARMED = "learning_armed"
        const val KEY_LEARNING_COUNT = "learning_saved_count"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_CHOSEN_APPS = "has_chosen_apps"
    }
}
