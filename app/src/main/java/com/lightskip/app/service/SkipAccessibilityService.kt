package com.lightskip.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.lightskip.app.BuildConfig
import com.lightskip.app.R
import com.lightskip.app.data.ImportedRuleStore
import com.lightskip.app.data.LocalStats
import com.lightskip.app.data.PopupMode
import com.lightskip.app.data.Prefs
import com.lightskip.app.data.RuleLearningStore

class SkipAccessibilityService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private lateinit var stats: LocalStats
    private lateinit var controller: SkipSessionController
    private lateinit var popupController: PopupSessionController
    private lateinit var learningStore: RuleLearningStore
    private lateinit var importedStore: ImportedRuleStore
    private var lastForegroundPackage: String? = null
    private var lastForegroundActivityClass: String = ""
    private var lastEventTypes: Int? = null
    private var lastFlags: Int? = null
    private var destroying = false
    private val useAllEventsForOem = Build.MANUFACTURER.equals("Xiaomi", true) ||
        Build.BRAND.equals("Redmi", true) ||
        Build.BRAND.equals("POCO", true)
    private val learningHandler = Handler(Looper.getMainLooper())
    private var learningArmPackage: String? = null
    private val learningArmRunnable = Runnable {
        val packageName = learningArmPackage ?: return@Runnable
        learningArmPackage = null
        armLearning(packageName, requireForeground = true)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        destroying = false
        if (::controller.isInitialized) {
            controller.cancel()
            popupController.cancel()
        }
        prefs = Prefs(this)
        stats = LocalStats(this, prefs)
        stats.prune()
        learningStore = RuleLearningStore(this)
        importedStore = ImportedRuleStore(this)
        val scanner = NodeScanner()

        controller = SkipSessionController(
            service = this,
            prefs = prefs,
            scanner = scanner,
            onSessionStarted = { stats.recordSession() },
            onSkipped = { packageName, responseMs ->
                stats.recordSkip(packageName, responseMs)
                armLearning(packageName, requireForeground = false)
            },
            onActiveChanged = { refreshServiceInfo() }
        )
        popupController = PopupSessionController(
            service = this,
            prefs = prefs,
            scanner = scanner,
            learningStore = learningStore,
            importedStore = importedStore,
            onPopupClosed = { packageName -> stats.recordSkip(packageName, -1L) },
            onActiveChanged = { refreshServiceInfo() }
        )
        DiagnosticsStore.serviceConnected = true
        DiagnosticsStore.serviceConnectedAt = System.currentTimeMillis()
        refreshServiceInfo()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::controller.isInitialized) return
        DiagnosticsStore.noteAccessibilityEvent()
        val eventType = event.eventType
        val packageName = resolveEventPackage(event) ?: return
        val className = event.className?.toString().orEmpty()
        val isOwnApp = packageName == this.packageName
        val isDemoSplash = BuildConfig.DEBUG &&
            isOwnApp &&
            className.contains("DemoSplashActivity")
        val isDemoPopup = BuildConfig.DEBUG &&
            isOwnApp &&
            (className.contains("Demo") && className.contains("Popup")) ||
            (BuildConfig.DEBUG &&
                isOwnApp &&
                lastForegroundActivityClass.contains("Demo") &&
                lastForegroundActivityClass.contains("Popup"))
        val watched = prefs.masterEnabled &&
            (prefs.isTarget(packageName) || isDemoSplash || isDemoPopup)
        val learningClick = eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            prefs.learningMode &&
            prefs.learningArmed &&
            packageName == prefs.learningTargetPackage
        if ((eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) &&
            lastForegroundPackage != packageName
        ) {
            val previous = lastForegroundPackage
            lastForegroundPackage = packageName
            if (watched) {
                controller.onForeground(packageName)
            } else if (previous != null) {
                controller.cancel()
                popupController.cancel()
            }
            refreshServiceInfo()
        }

        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (isOwnApp && !isDemoSplash && !isDemoPopup) {
                    if (prefs.learningMode) finishLearningSession()
                    controller.cancel()
                    popupController.cancel()
                    lastForegroundPackage = packageName
                    lastForegroundActivityClass = ""
                    refreshServiceInfo()
                    return
                }

                if (!watched) {
                    controller.cancel()
                    popupController.cancel()
                    lastForegroundPackage = packageName
                    lastForegroundActivityClass = ""
                    refreshServiceInfo()
                    return
                }

                recordDiagnostics(packageName, eventType, className)
                lastForegroundActivityClass = className
                if (prefs.learningMode && packageName == prefs.learningTargetPackage) {
                    scheduleLearningArm(packageName)
                }
                when {
                    isDemoSplash -> controller.onForeground(packageName, bypassTargetCheck = true)
                    isDemoPopup -> popupController.onWindowEvent(
                        packageName,
                        lastForegroundActivityClass,
                        bypassTargetCheck = true
                    )
                    else -> {
                        controller.onForeground(packageName)
                        popupController.onWindowEvent(packageName, lastForegroundActivityClass)
                    }
                }
                lastForegroundPackage = packageName
                refreshServiceInfo()
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (!watched) return
                recordDiagnostics(packageName, eventType, className)
                controller.onContentChanged(packageName)
                if (prefs.popupMode == PopupMode.LEARNED) {
                    popupController.onWindowEvent(
                        packageName,
                        lastForegroundActivityClass,
                        bypassTargetCheck = isDemoPopup
                    )
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                DiagnosticsStore.noteContentEvent()
                if (!watched) return
                val popupWatching = popupController.isRunning || shouldWatchForegroundPopup()
                if (!controller.isRunning && !popupWatching) return
                controller.onContentChanged(packageName)
                if (popupWatching) {
                    popupController.onContentChanged(packageName, lastForegroundActivityClass)
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (!learningClick) return
                recordDiagnostics(packageName, eventType, className)
                popupController.onViewClicked(
                    packageName = packageName,
                    source = event.source,
                    activityClass = lastForegroundActivityClass,
                    windowBounds = currentWindowBounds()
                )
            }
        }
    }

    private fun resolveEventPackage(event: AccessibilityEvent): String? {
        val eventPackage = event.packageName?.toString()
        val rootPackage = rootInActiveWindow?.packageName?.toString()

        if (eventPackage != null && NodeScanner.SYSTEM_AD_PACKAGES.contains(eventPackage)) {
            controller.targetPackageName?.let { return it }
            popupController.targetPackageName?.let { return it }
        }
        if (eventPackage != null && (prefs.isTarget(eventPackage) || eventPackage == packageName)) {
            return eventPackage
        }
        if (rootPackage != null && NodeScanner.SYSTEM_AD_PACKAGES.contains(rootPackage)) {
            controller.targetPackageName?.let { return it }
            popupController.targetPackageName?.let { return it }
        }
        if (rootPackage != null && (prefs.isTarget(rootPackage) || rootPackage == packageName)) {
            return rootPackage
        }
        return eventPackage ?: rootPackage ?: lastForegroundPackage
    }

    private fun recordDiagnostics(packageName: String, eventType: Int, className: String) {
        val rootPackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        val windowList = windows
        val windowPackages = windowList
            .mapNotNull { it.root?.packageName?.toString() }
            .distinct()
            .joinToString(",")
        DiagnosticsStore.recordEvent(
            packageName = packageName,
            type = eventType,
            className = className,
            windowCount = windowList.size,
            rootPackage = rootPackage,
            windowPackages = windowPackages
        )
    }

    private fun shouldWatchForegroundPopup(): Boolean {
        if (!prefs.masterEnabled || prefs.learningMode || prefs.popupMode != PopupMode.LEARNED) {
            return false
        }
        val packageName = lastForegroundPackage ?: return false
        if (!prefs.isTarget(packageName) && packageName != this.packageName) return false
        return popupController.hasRules(packageName)
    }

    private fun currentWindowBounds(): Rect {
        val root = rootInActiveWindow
        if (root != null) {
            val rect = Rect()
            root.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) return rect
        }
        var best = Rect()
        windows.forEach { window ->
            val rect = Rect()
            window.root?.getBoundsInScreen(rect)
            if (rect.width() * rect.height() > best.width() * best.height()) best = rect
        }
        if (best.width() <= 0 || best.height() <= 0) {
            val metrics = resources.displayMetrics
            best = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        return best
    }

    override fun onInterrupt() {
        if (::controller.isInitialized) controller.cancel()
        if (::popupController.isInitialized) popupController.cancel()
    }

    override fun onDestroy() {
        destroying = true
        learningHandler.removeCallbacksAndMessages(null)
        if (::controller.isInitialized) controller.cancel()
        if (::popupController.isInitialized) popupController.cancel()
        DiagnosticsStore.serviceConnected = false
        DiagnosticsStore.serviceConnectedAt = 0L
        super.onDestroy()
    }

    private fun scheduleLearningArm(packageName: String) {
        if (!prefs.learningMode || prefs.learningTargetPackage != packageName || prefs.learningArmed) return
        if (learningArmPackage == packageName) return
        learningHandler.removeCallbacks(learningArmRunnable)
        learningArmPackage = packageName
        learningHandler.postDelayed(learningArmRunnable, LEARNING_ARM_FALLBACK_MS)
    }

    private fun armLearning(packageName: String, requireForeground: Boolean) {
        if (!prefs.learningMode ||
            prefs.learningTargetPackage != packageName ||
            prefs.learningArmed
        ) {
            return
        }
        if (requireForeground && lastForegroundPackage != packageName) return
        learningHandler.removeCallbacks(learningArmRunnable)
        learningArmPackage = null
        prefs.learningArmed = true
        Toast.makeText(this, R.string.learning_armed, Toast.LENGTH_SHORT).show()
    }

    private fun finishLearningSession() {
        val count = prefs.learningSavedCount
        learningHandler.removeCallbacks(learningArmRunnable)
        learningArmPackage = null
        prefs.stopLearning()
        DiagnosticsStore.clearLearning()
        val message = if (count > 0) {
            getString(R.string.learning_finished_format, count)
        } else {
            getString(R.string.learning_finished_none)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun refreshServiceInfo() {
        if (destroying || !::controller.isInitialized) return
        val sessionActive = controller.isRunning || popupController.isRunning
        val popupWatching = shouldWatchForegroundPopup()
        val baseEvents = if (useAllEventsForOem) XIAOMI_BASE_EVENT_TYPES else BASE_EVENT_TYPES
        val eventTypes = when {
            sessionActive && useAllEventsForOem -> AccessibilityEvent.TYPES_ALL_MASK
            sessionActive || popupWatching -> baseEvents or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            else -> baseEvents
        }
        val flags = if (sessionActive) ACTIVE_FLAGS else BASE_FLAGS
        if (eventTypes == lastEventTypes && flags == lastFlags) return
        val info = serviceInfo ?: return
        info.eventTypes = eventTypes
        info.flags = flags
        info.notificationTimeout = NOTIFICATION_TIMEOUT_MS
        serviceInfo = info
        lastEventTypes = eventTypes
        lastFlags = flags
    }

    private companion object {
        const val NOTIFICATION_TIMEOUT_MS = 100L
        const val LEARNING_ARM_FALLBACK_MS = 3_000L

        val BASE_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_CLICKED

        val XIAOMI_BASE_EVENT_TYPES = BASE_EVENT_TYPES or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        const val BASE_FLAGS = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY

        const val ACTIVE_FLAGS = BASE_FLAGS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
}
