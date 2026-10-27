package com.lightskip.app.service

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.lightskip.app.data.Prefs
import com.lightskip.app.util.AccessibilityUtils
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
    @Volatile var lastScanPackage: String = ""
    @Volatile var lastScanNote: String = ""
    @Volatile var lastScanCandidate: PopupCandidate? = null
    @Volatile var lastClickPackage: String = ""
    @Volatile var lastClickResult: String = ""

    private val recent = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var learningPackageName: String = ""
    private val learningItems = mutableListOf<PopupCandidate>()

    @Synchronized
    fun recordEvent(packageName: String, type: Int, className: String, windowCount: Int) {
        lastEventAt = System.currentTimeMillis()
        lastEventPackage = packageName
        lastEventType = eventTypeName(type)
        lastEventClass = className.take(80)
        val line = "${timeFormat.format(Date(lastEventAt))} ${eventTypeName(type)} $packageName w=$windowCount"
        recent.addLast(line)
        while (recent.size > 12) recent.removeFirst()
    }

    @Synchronized
    fun recordScan(packageName: String, note: String, candidate: PopupCandidate?) {
        lastScanPackage = packageName
        lastScanNote = note
        lastScanCandidate = candidate?.copy(
            bounds = Rect(candidate.bounds),
            windowBounds = Rect(candidate.windowBounds)
        )
    }

    @Synchronized
    fun recordClick(packageName: String, success: Boolean, note: String) {
        lastClickPackage = packageName
        lastClickResult = if (success) "success: $note" else "failed: $note"
    }

    @Synchronized
    fun setLearningCandidates(packageName: String, candidates: List<PopupCandidate>) {
        learningPackageName = packageName
        learningItems.clear()
        learningItems.addAll(candidates.take(20).map {
            it.copy(bounds = Rect(it.bounds), windowBounds = Rect(it.windowBounds))
        })
    }

    @Synchronized
    fun learningPackage(): String = learningPackageName

    @Synchronized
    fun learningCandidatesSnapshot(): List<PopupCandidate> = learningItems.toList()

    @Synchronized
    fun clearLearning() {
        learningPackageName = ""
        learningItems.clear()
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
        builder.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        builder.appendLine("accessibility setting: ${AccessibilityUtils.isServiceEnabled(context)}")
        builder.appendLine("service connected: $serviceConnected")
        builder.appendLine("compatibility: ${prefs.compatibilityMode}")
        builder.appendLine("popup mode: ${prefs.popupMode.name}")
        builder.appendLine(
            "learning: ${prefs.learningMode} armed=${prefs.learningArmed} count=${prefs.learningSavedCount}"
        )
        builder.appendLine("protected apps: ${prefs.targetPackages.size}")
        builder.appendLine("last event: $lastEventType $lastEventPackage $lastEventClass")
        builder.appendLine("last scan: $lastScanPackage / $lastScanNote")
        builder.appendLine("last candidate: ${formatCandidate(lastScanCandidate)}")
        builder.appendLine("last click: $lastClickPackage / $lastClickResult")
        builder.appendLine("recent:")
        if (recent.isEmpty()) builder.appendLine("  (none)") else recent.forEach { builder.appendLine("  $it") }
        return builder.toString()
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
