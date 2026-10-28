package com.lightskip.app.service

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.lightskip.app.data.ImportedRuleStore
import com.lightskip.app.data.Prefs
import com.lightskip.app.util.AccessibilityUtils
import com.lightskip.app.util.BackgroundStatusUtils
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object DiagnosticsStore {

    data class PopupCandidate(
        val packageName: String,
        val text: String,
        val viewId: String,
        val contentDescription: String,
        val className: String,
        val bounds: Rect,
        val windowBounds: Rect,
        val score: Int,
        val source: String
    )

    @Volatile var serviceConnected: Boolean = false
    @Volatile var lastEventAt: Long = 0L
    @Volatile var lastEventPackage: String = ""
    @Volatile var lastEventType: String = ""
    @Volatile var lastEventClass: String = ""
    @Volatile var lastRootPackage: String = ""
    @Volatile var lastWindowPackages: String = ""
    @Volatile var lastScanPackage: String = ""
    @Volatile var lastScanNote: String = ""
    @Volatile var lastScanCandidate: PopupCandidate? = null
    @Volatile var lastSplashScanPackage: String = ""
    @Volatile var lastSplashScanNote: String = ""
    @Volatile var lastSplashCandidate: PopupCandidate? = null
    @Volatile var lastClickPackage: String = ""
    @Volatile var lastClickResult: String = ""
    @Volatile var eventCount: Int = 0
    @Volatile var rawEventCount: Int = 0
    @Volatile var stateEventCount: Int = 0
    @Volatile var windowEventCount: Int = 0
    @Volatile var contentEventCount: Int = 0
    @Volatile var scanCount: Int = 0
    @Volatile var candidateCount: Int = 0
    @Volatile var clickSuccessCount: Int = 0
    @Volatile var clickFailCount: Int = 0
    private val recent = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun recordEventSummary(packageName: String, type: Int, className: String) {
        eventCount++
        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> stateEventCount++
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> windowEventCount++
        }
        lastEventAt = System.currentTimeMillis()
        lastEventPackage = packageName
        lastEventType = eventTypeName(type)
        lastEventClass = className.take(80)
    }

    @Synchronized
    fun recordEvent(
        packageName: String,
        type: Int,
        className: String,
        windowCount: Int,
        rootPackage: String = "",
        windowPackages: String = ""
    ) {
        recordEventSummary(packageName, type, className)
        lastRootPackage = rootPackage.take(120)
        lastWindowPackages = windowPackages.take(240)
        val line = "${timeFormat.format(Date(lastEventAt))} ${eventTypeName(type)} $packageName " +
            "root=$lastRootPackage w=$windowCount [$lastWindowPackages]"
        recent.addLast(line)
        while (recent.size > 12) recent.removeFirst()
    }

    fun noteAccessibilityEvent() {
        rawEventCount++
    }

    @Synchronized
    fun recordScan(packageName: String, note: String, candidate: PopupCandidate?) {
        scanCount++
        if (candidate != null) candidateCount++
        lastScanPackage = packageName
        lastScanNote = note
        lastScanCandidate = candidate?.copy(
            bounds = Rect(candidate.bounds),
            windowBounds = Rect(candidate.windowBounds)
        )
    }

    @Synchronized
    fun recordSplashScan(packageName: String, note: String, candidate: PopupCandidate?) {
        scanCount++
        if (candidate != null) candidateCount++
        val copy = candidate?.copy(
            bounds = Rect(candidate.bounds),
            windowBounds = Rect(candidate.windowBounds)
        )
        lastSplashScanPackage = packageName
        lastSplashScanNote = note
        lastSplashCandidate = copy
        lastScanPackage = packageName
        lastScanNote = note
        lastScanCandidate = copy
    }

    @Synchronized
    fun recordClick(packageName: String, success: Boolean, note: String) {
        if (success) clickSuccessCount++ else clickFailCount++
        lastClickPackage = packageName
        lastClickResult = if (success) "success: $note" else "failed: $note"
    }

    fun noteContentEvent() {
        contentEventCount++
    }

    @Synchronized
    fun format(context: Context, prefs: Prefs): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("?")
        val builder = StringBuilder()
        builder.appendLine("轻跳诊断")
        builder.appendLine("version: $version")
        builder.appendLine("android: ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
        builder.appendLine("device: ${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL} / ${Build.DEVICE}")
        builder.appendLine("hardware: ${Build.HARDWARE} / ${Build.DISPLAY}")
        builder.appendLine("harmony: ${harmonyLabel()}")
        builder.appendLine(
            "harmony props: emui=${systemProperty("ro.build.version.emui")} " +
                "harmony=${systemProperty("ro.build.harmonyos.version")} " +
                "platform=${systemProperty("hw_sc.build.platform.version")}"
        )
        builder.appendLine("accessibility setting: ${AccessibilityUtils.isServiceEnabled(context)}")
        builder.appendLine("service connected: $serviceConnected")
        val background = BackgroundStatusUtils.read(context)
        builder.appendLine(
            "background: ${background.state.name} whitelisted=${background.batteryWhitelisted} " +
                "restricted=${background.backgroundRestricted} " +
                "runAny=${background.runAnyInBackgroundAllowed} " +
                "bucket=${background.standbyBucket}"
        )
        builder.appendLine("diagnosis: ${diagnose(prefs)}")
        builder.appendLine("compatibility: ${prefs.compatibilityMode}")
        builder.appendLine("popup mode: ${prefs.popupMode.name}")
        builder.appendLine("imported apps: ${ImportedRuleStore(context).importedAppCount()}")
        builder.appendLine(
            "learning: ${prefs.learningMode} armed=${prefs.learningArmed} count=${prefs.learningSavedCount}"
        )
        builder.appendLine("protected apps: ${prefs.targetPackages.size}")
        builder.appendLine(
            "events: raw=$rawEventCount target=$eventCount state=$stateEventCount " +
                "windows=$windowEventCount content=$contentEventCount"
        )
        builder.appendLine(
            "scans: total=$scanCount candidates=$candidateCount clicks ok=$clickSuccessCount fail=$clickFailCount"
        )
        builder.appendLine("last event: $lastEventType $lastEventPackage $lastEventClass")
        builder.appendLine("last root: $lastRootPackage")
        builder.appendLine("last windows: $lastWindowPackages")
        builder.appendLine("last scan: $lastScanPackage / $lastScanNote")
        builder.appendLine("last candidate: ${formatCandidate(lastScanCandidate)}")
        builder.appendLine("last splash scan: $lastSplashScanPackage / $lastSplashScanNote")
        builder.appendLine("last splash candidate: ${formatCandidate(lastSplashCandidate)}")
        builder.appendLine("last click: $lastClickPackage / $lastClickResult")
        builder.appendLine("recent:")
        if (recent.isEmpty()) builder.appendLine("  (none)") else recent.forEach { builder.appendLine("  $it") }
        return builder.toString()
    }

    fun diagnose(prefs: Prefs): String {
        if (!serviceConnected) return "无障碍服务未连接"
        if (rawEventCount == 0) return "服务已连接，但未收到任何无障碍事件（OEM 可能拦截了服务）"
        if (eventCount == 0 && contentEventCount == 0) {
            return "收到无障碍事件，但没有目标窗口事件（前台包名识别失败）"
        }
        if (prefs.targetPackages.isEmpty()) return "未选择守护应用"
        if (scanCount == 0) return "收到事件，但未进入目标 App 扫描（前台包名识别失败或未打开目标）"
        if (candidateCount == 0) return "已扫描但未识别到候选（广告没有可读文本/ID，或被系统窗口隔离）"
        if (clickFailCount > 0 && clickSuccessCount == 0) return "识别到候选但点击/手势失败（系统限制）"
        return "未发现明确故障，请附上最近事件和窗口包名"
    }

    private fun harmonyLabel(): String {
        val manufacturer = Build.MANUFACTURER.uppercase(Locale.ROOT)
        val brand = Build.BRAND.uppercase(Locale.ROOT)
        val display = Build.DISPLAY.uppercase(Locale.ROOT)
        val emui = systemProperty("ro.build.version.emui")
        val harmony = systemProperty("ro.build.harmonyos.version")
        if (harmony.isNotBlank() || emui.isNotBlank() || display.contains("HARMONY")) {
            return "HarmonyOS/EMUI (Android-compatible)"
        }
        if (manufacturer.contains("HUAWEI") || brand.contains("HONOR")) {
            return "Huawei/HONOR, HarmonyOS status unknown"
        }
        return "not detected"
    }

    private fun systemProperty(key: String): String {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            (get.invoke(null, key) as? String).orEmpty()
        }.getOrDefault("")
    }

    fun formatCandidate(candidate: PopupCandidate?): String {
        if (candidate == null) return "(none)"
        val text = candidate.text.take(24)
        val viewId = candidate.viewId.substringAfterLast('/').take(40)
        val desc = candidate.contentDescription.take(40)
        return "score=${candidate.score} source=${candidate.source} class=${candidate.className.substringAfterLast('.')} id=$viewId desc=$desc text=$text bounds=${candidate.bounds.toShortString()}"
    }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "windowState"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "windows"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "viewClicked"
        else -> "event($type)"
    }
}
