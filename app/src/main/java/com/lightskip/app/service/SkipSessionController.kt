package com.lightskip.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.lightskip.app.data.Prefs

class SkipSessionController(
    private val service: AccessibilityService,
    private val prefs: Prefs,
    private val scanner: NodeScanner,
    private val onSessionStarted: () -> Unit,
    private val onSkipped: (packageName: String, responseMs: Long) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val scanRunnable = Runnable { scan() }
    private val contentScanRunnable = Runnable { scan() }

    private var active = false
    private var targetPackage: String? = null
    private var startedAt = 0L
    private var deadline = 0L
    private var attempts = 0

    private var lastEndedPackage: String? = null
    private var lastEndedAt = 0L

    private var lastSignature: String? = null
    private var lastSignatureAt = 0L

    fun onForeground(packageName: String, bypassTargetCheck: Boolean = false) {
        if (!prefs.masterEnabled || (!bypassTargetCheck && !prefs.isTarget(packageName))) {
            cancel()
            return
        }
        if (active && targetPackage == packageName) return

        val now = SystemClock.uptimeMillis()
        if (packageName == lastEndedPackage && now - lastEndedAt < 8_000L) return
        start(packageName, now)
    }

    fun onContentChanged(packageName: String) {
        if (!active || packageName != targetPackage) return
        handler.removeCallbacks(contentScanRunnable)
        handler.postDelayed(contentScanRunnable, 80L)
    }

    private fun start(packageName: String, now: Long) {
        reset()
        active = true
        targetPackage = packageName
        startedAt = now
        deadline = now + prefs.detectionWindowMs
        attempts = 0
        lastSignature = null

        onSessionStarted()

        val window = prefs.detectionWindowMs
        handler.postDelayed(Runnable { finish() }, window + 60L)
        handler.postDelayed(scanRunnable, 40L)
        handler.postDelayed(scanRunnable, 320L)
        handler.postDelayed(scanRunnable, 900L)
    }

    private fun scan() {
        if (!active) return
        val now = SystemClock.uptimeMillis()
        if (now > deadline || attempts >= prefs.maxAttempts) {
            finish()
            return
        }

        val target = targetPackage ?: return
        val root = service.rootInActiveWindow ?: return
        val metrics = service.resources.displayMetrics
        val candidate = scanner.find(
            root = root,
            targetPackage = target,
            closeAdEnabled = prefs.closeAdEnabled,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        ) ?: return

        val bounds = Rect()
        candidate.node.getBoundsInScreen(bounds)
        val signature = candidate.text + "|" + bounds.flattenToString()
        if (signature == lastSignature && now - lastSignatureAt < 450L) return
        lastSignature = signature
        lastSignatureAt = now
        attempts++

        val clickNode = candidate.clickable ?: candidate.node
        val clicked = clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            onSkipped(target, now - startedAt)
            finish()
            return
        }

        if (bounds.width() > 0 && bounds.height() > 0) {
            val path = Path().apply {
                moveTo(bounds.exactCenterX(), bounds.exactCenterY())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build()
            if (service.dispatchGesture(gesture, null, null)) {
                onSkipped(target, now - startedAt)
                finish()
                return
            }
        }

        if (attempts >= prefs.maxAttempts) finish()
    }

    private fun finish() {
        if (!active) {
            handler.removeCallbacksAndMessages(null)
            return
        }
        active = false
        lastEndedPackage = targetPackage
        lastEndedAt = SystemClock.uptimeMillis()
        targetPackage = null
        handler.removeCallbacksAndMessages(null)
    }

    fun cancel() {
        finish()
    }

    private fun reset() {
        handler.removeCallbacksAndMessages(null)
        active = false
        targetPackage = null
        attempts = 0
    }
}