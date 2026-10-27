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
        screenHeight: Int
    ): Candidate? {
        var best: Candidate? = null
        walk(roots, targetPackage, screenWidth, screenHeight) { node, bounds, windowBounds ->
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
        includeAll: Boolean = false
    ): List<Candidate> {
        val found = mutableListOf<Candidate>()
        walk(roots, targetPackage, screenWidth, screenHeight) { node, bounds, windowBounds ->
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

    private fun walk(
        roots: List<AccessibilityNodeInfo>,
        targetPackage: String,
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
                if (packageName == null || packageName == targetPackage) {
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

    private companion object {
        const val MAX_VISITED_NODES = 400
        const val MAX_DEPTH = 14
        const val HIGH_CONFIDENCE_SCORE = 140
    }
}
