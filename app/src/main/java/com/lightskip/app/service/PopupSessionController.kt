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
import android.widget.Toast
import com.lightskip.app.R
import com.lightskip.app.data.PopupMode
import com.lightskip.app.data.Prefs
import com.lightskip.app.data.RuleLearningStore

class PopupSessionController(
    private val service: AccessibilityService,
    private val prefs: Prefs,
    private val scanner: NodeScanner,
    private val learningStore: RuleLearningStore,
    private val onPopupClosed: (packageName: String) -> Unit,
    private val onActiveChanged: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val scanRunnable = Runnable { scan() }
    private val contentScanRunnable = Runnable { scan(contentTriggered = true) }

    private var active = false
    val isRunning: Boolean get() = active

    private var targetPackage: String? = null
    private var targetActivityClass: String = ""
    private var deadline = 0L
    private var attempts = 0
    private var scanCount = 0
    private var contentScanCount = 0
    private var lastScanAt = 0L
    private var lastClosedPackage: String? = null
    private var lastClosedAt = 0L
    private var cachedRulesPackage: String? = null
    private var cachedRules: List<RuleLearningStore.LearnedRule> = emptyList()

    fun onWindowEvent(packageName: String, activityClass: String, bypassTargetCheck: Boolean = false) {
        if (!prefs.masterEnabled) return
        if (!bypassTargetCheck && !prefs.isTarget(packageName)) return
        if (prefs.learningMode) return
        if (prefs.popupMode != PopupMode.LEARNED) return
        val rules = rulesFor(packageName)
        if (rules.isEmpty() || rules.none { learningStore.matchesContext(it, activityClass) }) return

        val now = SystemClock.uptimeMillis()
        if (packageName == lastClosedPackage && now - lastClosedAt < 2_000L) return
        if (active && targetPackage == packageName) {
            targetActivityClass = activityClass
            handler.removeCallbacks(contentScanRunnable)
            handler.postDelayed(contentScanRunnable, CONTENT_SCAN_DELAY_MS)
            return
        }
        start(packageName, activityClass, now)
    }

    fun onContentChanged(packageName: String) {
        if (!active || packageName != targetPackage) return
        handler.removeCallbacks(contentScanRunnable)
        handler.postDelayed(contentScanRunnable, CONTENT_SCAN_DELAY_MS)
    }

    fun onViewClicked(
        packageName: String,
        source: AccessibilityNodeInfo?,
        activityClass: String,
        windowBounds: Rect
    ) {
        if (!prefs.learningMode || !prefs.learningArmed || source == null) return
        val bounds = Rect()
        source.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val sourceText = source.text?.toString().orEmpty()
        val sourceDesc = source.contentDescription?.toString().orEmpty()
        if (sourceText.contains("跳过") || sourceDesc.contains("跳过")) {
            Toast.makeText(service, R.string.learning_wait_splash, Toast.LENGTH_SHORT).show()
            return
        }
        val saved = learningStore.saveFromNode(
            packageName = packageName,
            viewId = source.viewIdResourceName.orEmpty(),
            contentDescription = sourceDesc,
            className = source.className?.toString().orEmpty(),
            bounds = bounds,
            windowBounds = windowBounds,
            activityClass = activityClass
        ) ?: run {
            Toast.makeText(service, R.string.learning_invalid_close, Toast.LENGTH_SHORT).show()
            return
        }
        if (!saved.added) {
            Toast.makeText(service, R.string.learning_rule_duplicate, Toast.LENGTH_SHORT).show()
            return
        }

        prefs.popupMode = PopupMode.LEARNED
        val count = (prefs.learningSavedCount + 1).coerceAtMost(RuleLearningStore.MAX_RULES)
        prefs.learningSavedCount = count
        invalidateRules()
        DiagnosticsStore.recordScan(packageName, "learned close button", saved.rule.toDiagnostic(packageName))
        if (count >= RuleLearningStore.MAX_RULES) {
            prefs.stopLearning()
            Toast.makeText(service, R.string.learning_rule_limit, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                service,
                service.getString(R.string.learning_rule_saved_format, count),
                Toast.LENGTH_LONG
            ).show()
        }
        finish()
    }

    private fun start(packageName: String, activityClass: String, now: Long) {
        reset()
        active = true
        targetPackage = packageName
        targetActivityClass = activityClass
        deadline = now + 3_500L
        attempts = 0
        scanCount = 0
        contentScanCount = 0
        lastScanAt = 0L
        onActiveChanged()
        handler.postDelayed(Runnable { finish() }, 3_560L)
        handler.postDelayed(scanRunnable, 300L)
        handler.postDelayed(scanRunnable, 900L)
        handler.postDelayed(scanRunnable, 1_800L)
        handler.postDelayed(scanRunnable, 2_800L)
    }

    private fun scan(contentTriggered: Boolean = false) {
        if (!active) return
        val now = SystemClock.uptimeMillis()
        if (now > deadline || attempts >= MAX_ATTEMPTS) {
            finish()
            return
        }
        if (contentTriggered && contentScanCount >= CONTENT_SCAN_LIMIT) return
        if (now - lastScanAt < MIN_SCAN_INTERVAL_MS) return
        if (scanCount >= SCAN_LIMIT) {
            finish()
            return
        }
        lastScanAt = now
        scanCount++
        if (contentTriggered) contentScanCount++

        val target = targetPackage ?: return
        val rules = rulesFor(target).filter {
            learningStore.matchesContext(it, targetActivityClass)
        }
        if (rules.isEmpty()) {
            DiagnosticsStore.recordScan(target, "popup: no learned rule", null)
            finish()
            return
        }

        val metrics = service.resources.displayMetrics
        val candidates = scanner.findPopupCandidates(
            roots = roots(),
            targetPackage = target,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            includeAll = true
        )

        val window = currentWindowBounds()
        for (rule in rules.filter { it.regionOnly }) {
            val candidate = regionCandidate(candidates, rule, window) ?: continue
            DiagnosticsStore.recordScan(target, "popup: learned region", candidate.toDiagnostic(target))
            attempts++
            if (clickCandidate(candidate, target)) {
                finish()
                return
            }
        }

        for (rule in rules) {
            if (rule.regionOnly) continue
            val candidate = candidates.firstOrNull {
                learningStore.matches(it.toDiagnostic(target), rule)
            } ?: continue
            DiagnosticsStore.recordScan(target, "popup: learned match", candidate.toDiagnostic(target))
            attempts++
            if (clickCandidate(candidate, target)) {
                finish()
                return
            }
        }
        DiagnosticsStore.recordScan(target, "popup: no learned match", null)
        if (attempts >= MAX_ATTEMPTS) finish()
    }

    private fun regionCandidate(
        candidates: List<NodeScanner.Candidate>,
        rule: RuleLearningStore.LearnedRule,
        window: Rect
    ): NodeScanner.Candidate? {
        if (window.width() <= 0 || window.height() <= 0) return null
        val expectedX = window.left + window.width() * rule.relX
        val expectedY = window.top + window.height() * rule.relY
        val toleranceX = (window.width() * 0.06f).coerceAtLeast(24f)
        val toleranceY = (window.height() * 0.04f).coerceAtLeast(24f)
        return candidates.firstOrNull { candidate ->
            val centerX = candidate.bounds.exactCenterX()
            val centerY = candidate.bounds.exactCenterY()
            val smallEnough = candidate.bounds.width() <= window.width() * 0.22f &&
                candidate.bounds.height() <= window.height() * 0.14f
            smallEnough &&
                kotlin.math.abs(centerX - expectedX) <= toleranceX &&
                kotlin.math.abs(centerY - expectedY) <= toleranceY
        }
    }

    private fun clickCandidate(candidate: NodeScanner.Candidate, target: String): Boolean {
        val clickNode = candidate.clickable ?: candidate.node
        if (clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            DiagnosticsStore.recordClick(target, true, "popup learned id=" + candidate.viewId.substringAfterLast('/').take(24))
            markClosed(target)
            return true
        }
        return tapAt(PointF(candidate.bounds.exactCenterX(), candidate.bounds.exactCenterY()), target, "learned gesture")
    }

    private fun tapAt(point: PointF, target: String, note: String): Boolean {
        val path = Path().apply { moveTo(point.x, point.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        if (!service.dispatchGesture(gesture, null, null)) return false
        DiagnosticsStore.recordClick(target, true, note)
        markClosed(target)
        return true
    }

    private fun markClosed(target: String) {
        lastClosedPackage = target
        lastClosedAt = SystemClock.uptimeMillis()
        onPopupClosed(target)
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
        service.windows.forEach { addRoot(it.root) }
        return result
    }

    private fun currentWindowBounds(): Rect {
        val root = service.rootInActiveWindow
        if (root != null) {
            val rect = Rect()
            root.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) return rect
        }
        var best = Rect()
        service.windows.forEach { window ->
            val rect = Rect()
            window.root?.getBoundsInScreen(rect)
            if (rect.width() * rect.height() > best.width() * best.height()) best = rect
        }
        if (best.width() <= 0 || best.height() <= 0) {
            val metrics = service.resources.displayMetrics
            best = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        return best
    }

    private fun finish() {
        if (!active) {
            handler.removeCallbacksAndMessages(null)
            return
        }
        active = false
        handler.removeCallbacksAndMessages(null)
        targetPackage = null
        targetActivityClass = ""
        onActiveChanged()
    }

    fun cancel() {
        finish()
    }

    private fun reset() {
        handler.removeCallbacksAndMessages(null)
        active = false
        targetPackage = null
        targetActivityClass = ""
        attempts = 0
        scanCount = 0
        contentScanCount = 0
        lastScanAt = 0L
    }

    private fun rulesFor(packageName: String): List<RuleLearningStore.LearnedRule> {
        if (cachedRulesPackage != packageName) {
            cachedRules = learningStore.getRules(packageName)
            cachedRulesPackage = packageName
        }
        return cachedRules
    }

    private fun invalidateRules() {
        cachedRulesPackage = null
        cachedRules = emptyList()
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

    private fun RuleLearningStore.LearnedRule.toDiagnostic(pkg: String): DiagnosticsStore.PopupCandidate {
        val window = currentWindowBounds()
        val centerX = window.left + window.width() * relX
        val centerY = window.top + window.height() * relY
        return DiagnosticsStore.PopupCandidate(
            packageName = pkg,
            text = "",
            viewId = viewId,
            contentDescription = contentDescription,
            className = className,
            bounds = Rect(
                (centerX - window.width() * relW / 2f).toInt(),
                (centerY - window.height() * relH / 2f).toInt(),
                (centerX + window.width() * relW / 2f).toInt(),
                (centerY + window.height() * relH / 2f).toInt()
            ),
            windowBounds = Rect(window),
            score = 0,
            source = if (regionOnly) "region" else "learned"
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val SCAN_LIMIT = 5
        const val CONTENT_SCAN_LIMIT = 2
        const val MIN_SCAN_INTERVAL_MS = 300L
        const val CONTENT_SCAN_DELAY_MS = 300L
    }
}
