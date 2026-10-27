package com.lightskip.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import com.lightskip.app.BuildConfig
import com.lightskip.app.data.LocalStats
import com.lightskip.app.data.Prefs

class SkipAccessibilityService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private lateinit var stats: LocalStats
    private lateinit var controller: SkipSessionController
    private var lastForegroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        stats = LocalStats(this, prefs)
        stats.prune()
        controller = SkipSessionController(
            service = this,
            prefs = prefs,
            scanner = NodeScanner(),
            onSessionStarted = { stats.recordSession() },
            onSkipped = { packageName, responseMs ->
                stats.recordSkip(packageName, responseMs)
                vibrateOnce()
            }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::controller.isInitialized) return
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString().orEmpty()
        val isDemo = BuildConfig.DEBUG &&
            packageName == this.packageName &&
            className.contains("DemoSplashActivity")

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (packageName == this.packageName && !isDemo) {
                    controller.cancel()
                    lastForegroundPackage = packageName
                    return
                }
                val shouldStart = prefs.masterEnabled && (prefs.isTarget(packageName) || isDemo)
                if (shouldStart && (isDemo || lastForegroundPackage != packageName)) {
                    controller.onForeground(packageName, bypassTargetCheck = isDemo)
                } else if (!shouldStart) {
                    controller.cancel()
                }
                lastForegroundPackage = packageName
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (prefs.masterEnabled) controller.onContentChanged(packageName)
            }
        }
    }

    override fun onInterrupt() {
        if (::controller.isInitialized) controller.cancel()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.cancel()
        super.onDestroy()
    }

    private fun vibrateOnce() {
        if (!prefs.vibrateEnabled) return
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(12L)
        }
    }
}