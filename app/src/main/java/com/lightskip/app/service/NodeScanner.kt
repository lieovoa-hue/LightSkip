package com.lightskip.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

class NodeScanner(private val rules: RuleEngine = RuleEngine) {

    data class Candidate(
        val node: AccessibilityNodeInfo,
        val clickable: AccessibilityNodeInfo?,
        val score: Int,
        val text: String
    )

    private data class Pending(
        val node: AccessibilityNodeInfo,
        val depth: Int
    )

    fun find(
        root: AccessibilityNodeInfo?,
        targetPackage: String,
        closeAdEnabled: Boolean,
        screenWidth: Int,
        screenHeight: Int
    ): Candidate? {
        if (root == null) return null

        val queue = ArrayDeque<Pending>()
        queue.addLast(Pending(root, 0))
        var visited = 0
        var best: Candidate? = null

        while (queue.isNotEmpty() && visited < 500) {
            val pending = queue.removeFirst()
            val node = pending.node
            visited++

            if (node.isVisibleToUser) {
                val packageName = node.packageName?.toString()
                if (packageName == null || packageName == targetPackage) {
                    val texts = buildList {
                        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { add(it) }
                        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }.distinct()

                    for (raw in texts) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        val clickable = findClickable(node)
                        val score = rules.score(
                            raw = raw,
                            bounds = bounds,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            clickable = clickable != null,
                            closeAdEnabled = closeAdEnabled
                        )
                        if (score > (best?.score ?: 0)) {
                            best = Candidate(node, clickable, score, raw)
                        }
                    }
                }
            }

            if (pending.depth < 20 && node.childCount > 0) {
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    queue.addLast(Pending(child, pending.depth + 1))
                }
            }
        }

        return best
    }

    private fun findClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 3) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            depth++
        }
        return null
    }
}