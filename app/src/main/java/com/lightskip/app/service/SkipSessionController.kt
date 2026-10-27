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
    private val onSkipped: (packageName: String, responseMs: Long) -> Unit,
    private val onActiveChanged: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val scanRunnable = Runnable { scan() }
    private val contentScanRunnable = Runnable { scan(contentTriggered = true) }

    private var active = false
    val isRunning: Boolean get() = active

    private var targetPackage: String? = null
    private var startedAt = 0L
    private var deadline = 0L
    private var attempts = 0
    private var scanCount = 0
    private var contentScanCount = 0
    private var scanLimit = 5
    private var contentScanLimit = 2
    private var lastScanAt = 0L

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
        if (packageName == lastEndedPackage && now - lastEndedAt < 2_000L) {
            DiagnosticsStore.recordScan(packageName, "session cooldown", null)
            return
        }
        start(packageName, now)
    }

    fun onContentChanged(packageName: String) {
        if (!active || packageName != targetPackage) return
        handler.removeCallbacks(contentScanRunnable)
        handler.postDelayed(contentScanRunnable, CONTENT_SCAN_DELAY_MS)
    }

    private fun start(packageName: String, now: Long) {
        reset()
        active = true
        targetPackage = packageName
        startedAt = now
        deadline = now + prefs.effectiveWindowMs
        attempts = 0
        scanCount = 0
        contentScanCount = 0
        lastScanAt = 0L
        scanLimit = if (prefs.compatibilityMode) 8 else 5
        contentScanLimit = if (prefs.compatibilityMode) 3 else 2
        lastSignature = null

        onActiveChanged()
        onSessionStarted()

        handler.postDelayed(Runnable { finish() }, prefs.effectiveWindowMs + 60L)
        handler.postDelayed(scanRunnable, 120L)
        handler.postDelayed(scanRunnable, 500L)
        handler.postDelayed(scanRunnable, 1_200L)
        handler.postDelayed(scanRunnable, 2_500L)
        if (prefs.compatibilityMode) {
            handler.postDelayed(scanRunnable, 5_000L)
            handler.postDelayed(scanRunnable, 8_000L)
        }
    }

    private fun scan(contentTriggered: Boolean = false) {
        if (!active) return
        val now = SystemClock.uptimeMillis()
        if (now > deadline || attempts >= prefs.effectiveMaxAttempts) {
            finish()
            return
        }
        if (contentTriggered && contentScanCount >= contentScanLimit) return
        if (now - lastScanAt < MIN_SCAN_INTERVAL_MS) return
        if (scanCount >= scanLimit) {
            finish()
            return
        }
        lastScanAt = now
        scanCount++
        if (contentTriggered) contentScanCount++

        val target = targetPackage ?: return
        val metrics = service.resources.displayMetrics
        val candidate = scanner.findSplash(
            roots = roots(),
            targetPackage = target,
            closeAdEnabled = prefs.closeAdEnabled,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )

        if (candidate == null) {
            DiagnosticsStore.recordScan(target, "no candidate", null)
            return
        }

        DiagnosticsStore.recordScan(target, "candidate", candidate.toDiagnostic(target))
        val bounds = candidate.bounds
        val signature = candidate.text + "|" + bounds.flattenToString()
        if (signature == lastSignature && now - lastSignatureAt < 450L) return
        lastSignature = signature
        lastSignatureAt = now
        attempts++

        val clickNode = candidate.clickable ?: candidate.node
        val clicked = clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            DiagnosticsStore.recordClick(target, true, "ACTION_CLICK " + candidate.text.take(24))
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
                DiagnosticsStore.recordClick(target, true, "gesture")
                onSkipped(target, now - startedAt)
                finish()
                return
            }
        }

        DiagnosticsStore.recordClick(target, false, "click failed")
        if (attempts >= prefs.effectiveMaxAttempts) finish()
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val seenWindows = mutableSetOf<Int>()
        val target = targetPackage ?: return result

        fun addRoot(root: AccessibilityNodeInfo?) {
            if (root == null) return
            val packageName = root.packageName?.toString()
            if (packageName != null && packageName != target) return
            if (seenWindows.add(root.windowId)) result.add(root)
        }

        addRoot(service.rootInActiveWindow)
        if (prefs.compatibilityMode) {
            service.windows.forEach { addRoot(it.root) }
        }
        return result
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
        onActiveChanged()
    }

    fun cancel() {
        finish()
    }

    private fun reset() {
        handler.removeCallbacksAndMessages(null)
        active = false
        targetPackage = null
        attempts = 0
        scanCount = 0
        contentScanCount = 0
        lastScanAt = 0L
    }

    private fun NodeScanner.Candidate.toDiagnostic(pkg: String): DiagnosticsStore.PopupCandidate {
        return DiagnosticsStore.PopupCandidate(
            packageName = pkg,
            text = text,
            viewId = viewId,
            contentDescription = contentDescription,
            className = className,
            bounds = Rect(bounds),
            windowBounds = Rect(windowBounds),
            score = score,
            source = source
        )
    }

    private companion object {
        const val CONTENT_SCAN_DELAY_MS = 240L
        const val MIN_SCAN_INTERVAL_MS = 250L
    }
}
