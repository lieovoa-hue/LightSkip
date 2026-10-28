package com.lightskip.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

class NodeScanner(private val rules: RuleEngine = RuleEngine) {

    data class Candidate(
        val node: AccessibilityNodeInfo,
        val clickable: AccessibilityNodeInfo?,
        val score: Int,
        val text: String,
        val viewId: String,
        val contentDescription: String,
        val className: String,
        val bounds: Rect,
        val windowBounds: Rect,
        val source: String
    )

    data class NodeView(
        val node: AccessibilityNodeInfo,
        val clickable: AccessibilityNodeInfo?,
        val text: String,
        val contentDescription: String,
        val viewId: String,
        val className: String,
        val bounds: Rect,
        val windowBounds: Rect,
        val windowId: Int
    )

    private data class Pending(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val windowBounds: Rect
    )

    fun findSplash(
        roots: List<AccessibilityNodeInfo>,
        targetPackage: String,
        closeAdEnabled: Boolean,
        screenWidth: Int,
        screenHeight: Int,
        scanPackages: Set<String> = allowedPackages(targetPackage)
    ): Candidate? {
        var best: Candidate? = null
        walk(roots, scanPackages, screenWidth, screenHeight) { node, bounds, windowBounds ->
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val signal = rules.hasSplashSignal(
                raw = if (text.isNotBlank()) text else desc,
                viewId = viewId,
                contentDescription = desc,
                closeAdEnabled = closeAdEnabled
            )
            if (!signal) {
                return@walk false
            }
            val className = node.className?.toString().orEmpty()
            val clickable = findClickable(node)
            val score = rules.scoreSplash(
                raw = if (text.isNotBlank()) text else desc,
                viewId = viewId,
                contentDescription = desc,
                bounds = bounds,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                clickable = clickable != null,
                closeAdEnabled = closeAdEnabled
            )
            if (score > (best?.score ?: 0)) {
                best = Candidate(
                    node = node,
                    clickable = clickable,
                    score = score,
                    text = if (text.isNotBlank()) text else desc,
                    viewId = viewId,
                    contentDescription = desc,
                    className = className,
                    bounds = Rect(bounds),
                    windowBounds = Rect(windowBounds),
                    source = when {
                        text.isNotBlank() -> "text"
                        desc.isNotBlank() -> "desc"
                        else -> "id"
                    }
                )
            }
            score >= HIGH_CONFIDENCE_SCORE
        }
        return best
    }

    fun findPopupCandidates(
        roots: List<AccessibilityNodeInfo>,
        targetPackage: String,
        screenWidth: Int,
        screenHeight: Int,
        includeAll: Boolean = false,
        scanPackages: Set<String> = allowedPackages(targetPackage)
    ): List<Candidate> {
        val found = mutableListOf<Candidate>()
        walk(roots, scanPackages, screenWidth, screenHeight) { node, bounds, windowBounds ->
            val clickable = findClickable(node) ?: return@walk false
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val className = node.className?.toString().orEmpty()
            val score = rules.scorePopup(
                raw = if (text.isNotBlank()) text else desc,
                viewId = viewId,
                contentDescription = desc,
                className = className,
                bounds = bounds,
                windowBounds = windowBounds,
                clickable = true
            )
            val hasMetadata = viewId.isNotBlank() || desc.isNotBlank() || className.isNotBlank()
            if (score > 0 || (includeAll && hasMetadata)) {
                found += Candidate(
                    node = node,
                    clickable = clickable,
                    score = score,
                    text = if (text.isNotBlank()) text else desc,
                    viewId = viewId,
                    contentDescription = desc,
                    className = className,
                    bounds = Rect(bounds),
                    windowBounds = Rect(windowBounds),
                    source = when {
                        rules.isStrongCloseId(viewId) -> "id"
                        desc.isNotBlank() -> "desc"
                        text.isNotBlank() -> "text"
                        else -> "position"
                    }
                )
            }
            false
        }
        return found.sortedByDescending { it.score }.take(if (includeAll) 40 else 20)
    }

    fun collectNodes(
        roots: List<AccessibilityNodeInfo>,
        targetPackage: String,
        screenWidth: Int,
        screenHeight: Int,
        scanPackages: Set<String> = allowedPackages(targetPackage)
    ): List<NodeView> {
        val result = mutableListOf<NodeView>()
        walk(roots, scanPackages, screenWidth, screenHeight) { node, bounds, windowBounds ->
            if (result.size >= MAX_VISITED_NODES) return@walk true
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val clickable = findClickable(node)
            if (text.isBlank() && desc.isBlank() && viewId.isBlank() && clickable == null) {
                return@walk false
            }
            result.add(
                NodeView(
                    node = node,
                    clickable = clickable,
                    text = text,
                    contentDescription = desc,
                    viewId = viewId,
                    className = node.className?.toString().orEmpty(),
                    bounds = Rect(bounds),
                    windowBounds = Rect(windowBounds),
                    windowId = node.windowId
                )
            )
            false
        }
        return result
    }

    private fun walk(
        roots: List<AccessibilityNodeInfo>,
        scanPackages: Set<String>,
        screenWidth: Int,
        screenHeight: Int,
        visitor: (AccessibilityNodeInfo, Rect, Rect) -> Boolean
    ): Boolean {
        val queue = ArrayDeque<Pending>()
        roots.forEach { root ->
            val windowBounds = Rect()
            root.getBoundsInScreen(windowBounds)
            if (windowBounds.width() <= 0 || windowBounds.height() <= 0) {
                windowBounds.set(0, 0, screenWidth, screenHeight)
            }
            queue.addLast(Pending(root, 0, windowBounds))
        }

        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_VISITED_NODES) {
            val pending = queue.removeFirst()
            val node = pending.node
            visited++
            if (node.isVisibleToUser) {
                val packageName = node.packageName?.toString()
                if (packageName == null || scanPackages.contains(packageName)) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        if (visitor(node, bounds, pending.windowBounds)) return true
                    }
                }
            }
            if (pending.depth < MAX_DEPTH && node.childCount > 0) {
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    queue.addLast(Pending(child, pending.depth + 1, pending.windowBounds))
                }
            }
        }
        return false
    }

    private fun findClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 4) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            depth++
        }
        return null
    }

    companion object {
        const val MAX_VISITED_NODES = 400
        const val MAX_DEPTH = 14
        const val HIGH_CONFIDENCE_SCORE = 140

        val SYSTEM_AD_PACKAGES = setOf(
            "com.miui.systemAdSolution",
            "com.miui.msa.global",
            "com.xiaomi.ad"
        )

        fun allowedPackages(targetPackage: String): Set<String> {
            if (SYSTEM_AD_PACKAGES.isEmpty()) return setOf(targetPackage)
            return SYSTEM_AD_PACKAGES + targetPackage
        }

        fun isAllowedPackage(packageName: String?, targetPackage: String): Boolean {
            if (packageName == null) return true
            return packageName == targetPackage || SYSTEM_AD_PACKAGES.contains(packageName)
        }
    }
}
