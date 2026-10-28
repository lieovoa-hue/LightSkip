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

    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDING, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDING, value).apply()

    var hasChosenApps: Boolean
        get() = sp.getBoolean(KEY_CHOSEN_APPS, false)
        set(value) = sp.edit().putBoolean(KEY_CHOSEN_APPS, value).apply()

    var builtinRulesVersion: Int
        get() = sp.getInt(KEY_BUILTIN_RULES, 0)
        set(value) = sp.edit().putInt(KEY_BUILTIN_RULES, value).apply()

    var splashRulesVersion: Int
        get() = sp.getInt(KEY_SPLASH_RULES, 0)
        set(value) = sp.edit().putInt(KEY_SPLASH_RULES, value).apply()

    var rulesRevision: Long
        get() = sp.getLong(KEY_RULES_REVISION, 0L)
        set(value) = sp.edit().putLong(KEY_RULES_REVISION, value).apply()

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

    fun bumpRulesRevision() {
        rulesRevision = rulesRevision + 1L
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
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_CHOSEN_APPS = "has_chosen_apps"
        const val KEY_BUILTIN_RULES = "builtin_rules_version"
        const val KEY_SPLASH_RULES = "splash_rules_version"
        const val KEY_RULES_REVISION = "rules_revision"
    }
}
