package com.lightskip.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.lightskip.app.BuildConfig
import com.lightskip.app.R
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
    private var lastForegroundPackage: String? = null
    private var lastForegroundActivityClass: String = ""
    private var contentEventsEnabled: Boolean? = null
    private var destroying = false
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
            onPopupClosed = { packageName -> stats.recordSkip(packageName, -1L) },
            onActiveChanged = { refreshServiceInfo() }
        )
        DiagnosticsStore.serviceConnected = true
        refreshServiceInfo()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::controller.isInitialized) return
        val eventType = event.eventType
        val eventPackage = event.packageName?.toString()
        val packageName = eventPackage ?: lastForegroundPackage ?: return
        val className = event.className?.toString().orEmpty()
        val isOwnApp = packageName == this.packageName
        val isDemoSplash = BuildConfig.DEBUG &&
            isOwnApp &&
            className.contains("DemoSplashActivity")
        val isDemoPopup = BuildConfig.DEBUG &&
            isOwnApp &&
            (className.contains("DemoPopupActivity") || lastForegroundActivityClass.contains("DemoPopupActivity"))
        val watched = prefs.masterEnabled &&
            (prefs.isTarget(packageName) || isDemoSplash || isDemoPopup)
        val learningClick = eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            prefs.learningMode &&
            prefs.learningArmed &&
            packageName == prefs.learningTargetPackage

        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (isOwnApp && !isDemoSplash && !isDemoPopup) {
                    if (prefs.learningMode) finishLearningSession()
                    controller.cancel()
                    popupController.cancel()
                    lastForegroundPackage = packageName
                    lastForegroundActivityClass = ""
                    return
                }

                if (!watched) {
                    controller.cancel()
                    popupController.cancel()
                    lastForegroundPackage = packageName
                    lastForegroundActivityClass = ""
                    return
                }

                DiagnosticsStore.recordEvent(packageName, eventType, className, windows.size)
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
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (!watched) return
                DiagnosticsStore.recordEvent(packageName, eventType, className, windows.size)
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
                if (!watched) return
                if (!controller.isRunning && !popupController.isRunning) return
                controller.onContentChanged(packageName)
                popupController.onContentChanged(packageName)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (!learningClick) return
                DiagnosticsStore.recordEvent(packageName, eventType, className, windows.size)
                popupController.onViewClicked(
                    packageName = packageName,
                    source = event.source,
                    activityClass = lastForegroundActivityClass,
                    windowBounds = currentWindowBounds()
                )
            }
        }
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
        val active = controller.isRunning || popupController.isRunning
        if (contentEventsEnabled == active) return
        val info = serviceInfo ?: return
        info.eventTypes = if (active) ACTIVE_EVENT_TYPES else BASE_EVENT_TYPES
        info.flags = if (active) ACTIVE_FLAGS else BASE_FLAGS
        info.notificationTimeout = NOTIFICATION_TIMEOUT_MS
        serviceInfo = info
        contentEventsEnabled = active
    }

    private companion object {
        const val NOTIFICATION_TIMEOUT_MS = 100L
        const val LEARNING_ARM_FALLBACK_MS = 3_000L

        val BASE_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_CLICKED

        val ACTIVE_EVENT_TYPES = BASE_EVENT_TYPES or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        const val BASE_FLAGS = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY

        const val ACTIVE_FLAGS = BASE_FLAGS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
}
