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
import com.lightskip.app.R
import com.lightskip.app.data.ImportedRuleStore
import com.lightskip.app.data.PopupMode
import com.lightskip.app.data.Prefs
import com.lightskip.app.data.RuleLearningStore

class PopupSessionController(
    private val service: AccessibilityService,
    private val prefs: Prefs,
    private val scanner: NodeScanner,
    private val learningStore: RuleLearningStore,
    private val importedStore: ImportedRuleStore,
    private val onPopupClosed: () -> Unit,
    private val onActiveChanged: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val scanRunnable = Runnable { scan() }
    private val contentScanRunnable = Runnable { scan(contentTriggered = true) }

    private var active = false
    val isRunning: Boolean get() = active
    val targetPackageName: String? get() = targetPackage

    private var targetPackage: String? = null
    private var targetActivityClass: String = ""
    private var deadline = 0L
    private var attempts = 0
    private var scanCount = 0
    private var contentScanCount = 0
    private var lastScanAt = 0L
    private var lastProbeAt = 0L
    private var scanLimit = SCAN_LIMIT
    private var contentScanLimit = CONTENT_SCAN_LIMIT
    private var lastClosedPackage: String? = null
    private var lastClosedAt = 0L
    private var cachedRulesPackage: String? = null
    private var cachedRulesRevision = -1L
    private var cachedRules: List<RuleLearningStore.LearnedRule> = emptyList()
    private var sessionImportedRules: List<ImportedRuleStore.PopupRule> = emptyList()
    private var sessionImportedRevision = -1L
    private var sessionRootHint: AccessibilityNodeInfo? = null

    fun onWindowEvent(
        packageName: String,
        activityClass: String,
        bypassTargetCheck: Boolean = false,
        rootHint: AccessibilityNodeInfo? = null
    ) {
        if (!prefs.masterEnabled) return
        if (!bypassTargetCheck && !prefs.isTarget(packageName)) return
        if (prefs.popupMode != PopupMode.LEARNED) return
        val rules = rulesFor(packageName)
        val hasLearnedContext = rules.any { learningStore.matchesContext(it, activityClass) }
        if ((rules.isEmpty() || !hasLearnedContext) && !importedStore.hasRules(packageName)) return

        val now = SystemClock.uptimeMillis()
        if (packageName == lastClosedPackage && now - lastClosedAt < 2_000L) return
        if (active && targetPackage == packageName) {
            if (sessionRootHint == null) sessionRootHint = rootHint
            targetActivityClass = activityClass
            handler.removeCallbacks(contentScanRunnable)
            handler.postDelayed(contentScanRunnable, CONTENT_SCAN_DELAY_MS)
            return
        }
        start(packageName, activityClass, now, probe = rules.isEmpty(), rootHint = rootHint)
    }

    fun onContentChanged(packageName: String, activityClass: String = "") {
        if (active) {
            if (!prefs.masterEnabled || prefs.popupMode != PopupMode.LEARNED) {
                finish()
                return
            }
            if (packageName != targetPackage) return
            targetActivityClass = activityClass
            handler.removeCallbacks(contentScanRunnable)
            handler.postDelayed(contentScanRunnable, CONTENT_SCAN_DELAY_MS)
            return
        }
        if (!prefs.masterEnabled || prefs.popupMode != PopupMode.LEARNED) return
        if (!prefs.isTarget(packageName) || !hasRules(packageName)) return

        val now = SystemClock.uptimeMillis()
        if (packageName == lastClosedPackage && now - lastClosedAt < 2_000L) return
        if (now - lastProbeAt < PROBE_COOLDOWN_MS) return
        lastProbeAt = now
        start(packageName, activityClass, now, probe = true)
    }

    fun hasRules(packageName: String): Boolean {
        return rulesFor(packageName).isNotEmpty() || importedStore.hasRules(packageName)
    }

    private fun start(
        packageName: String,
        activityClass: String,
        now: Long,
        probe: Boolean,
        rootHint: AccessibilityNodeInfo? = null
    ) {
        reset()
        active = true
        targetPackage = packageName
        targetActivityClass = activityClass
        sessionRootHint = rootHint
        scanLimit = if (probe) PROBE_SCAN_LIMIT else SCAN_LIMIT
        contentScanLimit = if (probe) 1 else CONTENT_SCAN_LIMIT
        sessionImportedRules = importedStore.getRules(packageName)
        sessionImportedRevision = prefs.rulesRevision
        deadline = now + (if (probe) PROBE_WINDOW_MS else POPUP_WINDOW_MS)
        attempts = 0
        scanCount = 0
        contentScanCount = 0
        lastScanAt = 0L
        onActiveChanged()
        handler.postDelayed(Runnable { finish() }, (if (probe) PROBE_WINDOW_MS else POPUP_WINDOW_MS) + 60L)
        if (probe) {
            handler.postDelayed(scanRunnable, 450L)
            handler.postDelayed(scanRunnable, 1_200L)
        } else {
            handler.postDelayed(scanRunnable, 300L)
            handler.postDelayed(scanRunnable, 900L)
            handler.postDelayed(scanRunnable, 1_800L)
            handler.postDelayed(scanRunnable, 2_800L)
        }
    }

    private fun scan(contentTriggered: Boolean = false) {
        if (!active) return
        if (!prefs.masterEnabled || prefs.popupMode != PopupMode.LEARNED) {
            finish()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now > deadline || attempts >= MAX_ATTEMPTS) {
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
        if (sessionImportedRevision != prefs.rulesRevision) {
            sessionImportedRules = importedStore.getRules(target)
            sessionImportedRevision = prefs.rulesRevision
        }
        val learnedRules = rulesFor(target).filter {
            learningStore.matchesContext(it, targetActivityClass)
        }
        val importedRules = sessionImportedRules
        if (learnedRules.isEmpty() && importedRules.isEmpty()) {
            DiagnosticsStore.recordScan(target, "popup: no rule", null)
            finish()
            return
        }

        val metrics = service.resources.displayMetrics
        if (learnedRules.isNotEmpty()) {
            val candidates = scanner.findPopupCandidates(
                roots = roots(),
                targetPackage = target,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                includeAll = true
            )

            val window = currentWindowBounds()
            for (rule in learnedRules.filter { it.regionOnly }) {
                val candidate = regionCandidate(candidates, rule, window) ?: continue
                DiagnosticsStore.recordScan(target, "popup: learned region", candidate.toDiagnostic(target))
                attempts++
                if (clickCandidate(candidate, target)) {
                    finish()
                    return
                }
            }

            for (rule in learnedRules) {
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
        }

        if (importedRules.isNotEmpty() && evaluateImportedRules(target, importedRules)) {
            finish()
            return
        }
        DiagnosticsStore.recordScan(target, "popup: no match", null)
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

    private fun evaluateImportedRules(
        target: String,
        rules: List<ImportedRuleStore.PopupRule>
    ): Boolean {
        val metrics = service.resources.displayMetrics
        val nodes = scanner.collectNodes(
            roots = roots(),
            targetPackage = target,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )
        if (nodes.isEmpty()) return false

        for (rule in rules) {
            val trigger = nodes.firstOrNull { matchesIdentifier(it, rule.id) } ?: continue
            DiagnosticsStore.recordScan(
                target,
                "imported: " + rule.id.take(24),
                trigger.toDiagnostic(target)
            )
            if (rule.action.equals("GLOBAL_ACTION_BACK", ignoreCase = true)) {
                attempts++
                if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                    DiagnosticsStore.recordClick(target, true, "imported back")
                    markClosed(target)
                    return true
                }
                DiagnosticsStore.recordClick(target, false, "imported back")
                continue
            }

            val actionBounds = parseBounds(rule.action)
            if (actionBounds != null) {
                attempts++
                if (tapAt(
                        PointF(actionBounds.exactCenterX(), actionBounds.exactCenterY()),
                        target,
                        "imported bounds"
                    )
                ) {
                    return true
                }
                continue
            }

            val action = nodes.firstOrNull { node ->
                node.windowId == trigger.windowId &&
                    node.clickable != null &&
                    matchesIdentifier(node, rule.action)
            } ?: nodes.filter { node ->
                node.clickable != null && matchesIdentifier(node, rule.action)
            }.takeIf { it.size == 1 }?.first() ?: continue
            attempts++
            if (clickNodeView(action, target, "imported " + rule.action.take(20))) return true
        }
        return false
    }

    private fun clickNodeView(node: NodeScanner.NodeView, target: String, note: String): Boolean {
        if (tapAt(
            PointF(node.bounds.exactCenterX(), node.bounds.exactCenterY()),
            target,
            note + " gesture"
        )) {
            return true
        }
        val clickNode = node.clickable ?: node.node
        if (clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            DiagnosticsStore.recordClick(target, true, note)
            markClosed(target)
            return true
        }
        return false
    }

    private fun matchesIdentifier(node: NodeScanner.NodeView, raw: String): Boolean {
        parseBounds(raw)?.let { return boundsMatch(node, it) }
        val fields = listOf(
            node.text,
            node.contentDescription,
            node.viewId,
            node.viewId.substringAfterLast('/')
        ).map { RuleEngine.compact(it) }.filter { it.isNotEmpty() }
        if (fields.isEmpty()) return false

        val orParts = splitNonEmpty(raw, '|')
        if (orParts.size > 1) return orParts.any { matchesAnd(fields, it) }
        return matchesAnd(fields, raw)
    }

    private fun parseBounds(raw: String): Rect? {
        val parts = raw.split(',').map { it.trim().toIntOrNull() ?: return null }
        if (parts.size != 4) return null
        val left = minOf(parts[0], parts[2])
        val top = minOf(parts[1], parts[3])
        val right = maxOf(parts[0], parts[2])
        val bottom = maxOf(parts[1], parts[3])
        if (right - left < 4 || bottom - top < 4) return null
        return Rect(left, top, right, bottom)
    }

    private fun boundsMatch(node: NodeScanner.NodeView, rect: Rect): Boolean {
        val centerX = node.bounds.exactCenterX().toInt()
        val centerY = node.bounds.exactCenterY().toInt()
        return rect.contains(centerX, centerY)
    }

    private fun matchesAnd(fields: List<String>, raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        val exact = trimmed.startsWith("=")
        val body = if (exact) trimmed.substring(1) else trimmed
        val andParts = splitNonEmpty(body, '&')
        if (andParts.size > 1) {
            val tokens = andParts.map { RuleEngine.compact(it) }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return false
            return fields.any { field -> tokens.all { field.contains(it) } }
        }
        val token = RuleEngine.compact(body)
        if (token.isEmpty()) return false
        return if (exact) fields.any { it == token } else fields.any { it.contains(token) }
    }

    private fun splitNonEmpty(raw: String, delimiter: Char): List<String> {
        return raw.split(delimiter).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun clickCandidate(candidate: NodeScanner.Candidate, target: String): Boolean {
        if (tapAt(
            PointF(candidate.bounds.exactCenterX(), candidate.bounds.exactCenterY()),
            target,
            "learned gesture"
        )) {
            return true
        }
        val clickNode = candidate.clickable ?: candidate.node
        if (clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            DiagnosticsStore.recordClick(target, true, "popup learned id=" + candidate.viewId.substringAfterLast('/').take(24))
            markClosed(target)
            return true
        }
        return false
    }

    private fun tapAt(point: PointF, target: String, note: String): Boolean {
        val path = Path().apply { moveTo(point.x, point.y) }
        val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()
        if (!service.dispatchGesture(gesture, null, null)) return false
        DiagnosticsStore.recordClick(target, true, note)
        markClosed(target)
        return true
    }

    private fun markClosed(target: String) {
        lastClosedPackage = target
        lastClosedAt = SystemClock.uptimeMillis()
        onPopupClosed()
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        val target = targetPackage ?: return emptyList()
        return rootsFor(target, sessionRootHint)
    }

    private fun rootsFor(
        target: String,
        extraRoot: AccessibilityNodeInfo? = null
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val seenWindows = mutableSetOf<Int>()

        fun addRoot(root: AccessibilityNodeInfo?) {
            if (root == null) return
            val packageName = root.packageName?.toString()
            if (!NodeScanner.isAllowedPackage(packageName, target)) return
            if (seenWindows.add(root.windowId)) result.add(root)
        }

        addRoot(service.rootInActiveWindow)
        addRoot(extraRoot)
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
        sessionRootHint = null
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
        sessionRootHint = null
        attempts = 0
        scanCount = 0
        contentScanCount = 0
        lastScanAt = 0L
        scanLimit = SCAN_LIMIT
        contentScanLimit = CONTENT_SCAN_LIMIT
        sessionImportedRules = emptyList()
        sessionImportedRevision = -1L
    }

    private fun rulesFor(packageName: String): List<RuleLearningStore.LearnedRule> {
        if (cachedRulesPackage != packageName || cachedRulesRevision != prefs.rulesRevision) {
            cachedRules = learningStore.getRules(packageName)
            cachedRulesPackage = packageName
            cachedRulesRevision = prefs.rulesRevision
        }
        return cachedRules
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

    private fun NodeScanner.NodeView.toDiagnostic(pkg: String): DiagnosticsStore.PopupCandidate {
        return DiagnosticsStore.PopupCandidate(
            packageName = pkg,
            text = text,
            viewId = viewId,
            contentDescription = contentDescription,
            className = className,
            bounds = Rect(bounds),
            windowBounds = Rect(windowBounds),
            score = 0,
            source = "imported"
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
        const val POPUP_WINDOW_MS = 3_500L
        const val PROBE_WINDOW_MS = 1_800L
        const val PROBE_SCAN_LIMIT = 2
        const val PROBE_COOLDOWN_MS = 2_000L
        const val TAP_DURATION_MS = 10L
    }
}
