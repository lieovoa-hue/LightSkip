package com.lightskip.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
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
    val targetPackageName: String? get() = targetPackage

    private var targetPackage: String? = null
    private var startedAt = 0L
    private var deadline = 0L
    private var attempts = 0
    private var scanCount = 0
    private var contentScanCount = 0
    private var scanLimit = 5
    private var contentScanLimit = 2
    private var lastScanAt = 0L
    private var fallbackTaps = 0
    private var lastFallbackTapAt = 0L
    private var adContainerSeen = false

    private var lastEndedPackage: String? = null
    private var lastEndedAt = 0L

    private var lastSignature: String? = null
    private var lastSignatureAt = 0L

    fun onForeground(
        packageName: String,
        bypassTargetCheck: Boolean = false,
        restartImmediately: Boolean = false
    ) {
        if (!prefs.masterEnabled || (!bypassTargetCheck && !prefs.isTarget(packageName))) {
            cancel()
            return
        }
        if (active && targetPackage == packageName) return

        val now = SystemClock.uptimeMillis()
        if (!restartImmediately && packageName == lastEndedPackage && now - lastEndedAt < 500L) {
            DiagnosticsStore.recordSplashScan(packageName, "session cooldown", null)
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
        fallbackTaps = 0
        lastFallbackTapAt = 0L
        adContainerSeen = false
        scanLimit = if (prefs.compatibilityMode) 9 else 5
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
            handler.postDelayed(scanRunnable, 4_000L)
            handler.postDelayed(scanRunnable, 6_000L)
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
        if (now - lastScanAt < MIN_SCAN_INTERVAL_MS) {
            if (contentTriggered) {
                handler.postDelayed(
                    contentScanRunnable,
                    MIN_SCAN_INTERVAL_MS - (now - lastScanAt)
                )
            }
            return
        }
        if (scanCount >= scanLimit) {
            finish()
            return
        }
        lastScanAt = now
        scanCount++
        if (contentTriggered) contentScanCount++

        val target = targetPackage ?: return
        val metrics = service.resources.displayMetrics
        val roots = roots()
        val candidate = scanner.findSplash(
            roots = roots,
            targetPackage = target,
            closeAdEnabled = prefs.closeAdEnabled,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )

        if (candidate == null) {
            val container = if (prefs.compatibilityMode) {
                scanner.findSplashContainer(
                    roots = roots,
                    targetPackage = target,
                    screenWidth = metrics.widthPixels,
                    screenHeight = metrics.heightPixels
                )
            } else {
                null
            }
            if (container != null) {
                adContainerSeen = true
                val elapsed = now - startedAt
                if (elapsed >= FALLBACK_MIN_AGE_MS &&
                    elapsed <= FALLBACK_WINDOW_MS &&
                    fallbackTaps < FALLBACK_MAX_TAPS &&
                    now - lastFallbackTapAt >= FALLBACK_MIN_TAP_GAP_MS
                ) {
                    val pointSpec = FALLBACK_POINTS[fallbackTaps % FALLBACK_POINTS.size]
                    val point = PointF(
                        metrics.widthPixels * pointSpec.first,
                        metrics.heightPixels * pointSpec.second
                    )
                    if (tapAt(point, target, "splash position fallback")) {
                        fallbackTaps++
                        lastFallbackTapAt = now
                        DiagnosticsStore.recordSplashScan(target, "ad container fallback", null)
                        return
                    }
                }
                DiagnosticsStore.recordSplashScan(target, "ad container waiting", null)
                if (fallbackTaps >= FALLBACK_MAX_TAPS && elapsed > FALLBACK_WINDOW_MS) {
                    finish()
                }
                return
            }
            if (adContainerSeen && fallbackTaps > 0) {
                DiagnosticsStore.recordClick(target, true, "splash fallback closed")
                onSkipped(target, now - startedAt)
                finish()
                return
            }
            DiagnosticsStore.recordSplashScan(target, "no candidate", null)
            return
        }

        DiagnosticsStore.recordSplashScan(target, "candidate", candidate.toDiagnostic(target))
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

    private fun tapAt(point: PointF, target: String, note: String): Boolean {
        val path = Path().apply { moveTo(point.x, point.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        if (!service.dispatchGesture(gesture, null, null)) return false
        DiagnosticsStore.recordClick(target, true, note)
        return true
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val seenWindows = mutableSetOf<Int>()
        val target = targetPackage ?: return result

        fun addRoot(root: AccessibilityNodeInfo?) {
            if (root == null) return
            val packageName = root.packageName?.toString()
            if (!NodeScanner.isAllowedPackage(packageName, target)) return
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
        fallbackTaps = 0
        lastFallbackTapAt = 0L
        adContainerSeen = false
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
        const val CONTENT_SCAN_DELAY_MS = 60L
        const val MIN_SCAN_INTERVAL_MS = 120L
        const val FALLBACK_MIN_AGE_MS = 1_000L
        const val FALLBACK_WINDOW_MS = 6_500L
        const val FALLBACK_MAX_TAPS = 4
        const val FALLBACK_MIN_TAP_GAP_MS = 1_000L
        val FALLBACK_POINTS = listOf(
            0.89f to 0.06f,
            0.94f to 0.06f,
            0.11f to 0.06f,
            0.16f to 0.06f
        )
    }
}
