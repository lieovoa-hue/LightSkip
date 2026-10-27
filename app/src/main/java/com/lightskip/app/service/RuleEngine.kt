package com.lightskip.app.service

import android.graphics.Rect
import java.util.Locale

object RuleEngine {

    private val skipPatterns = listOf(
        Regex("^跳过(广告)?$"),
        Regex("^跳过(广告)?\\d{1,2}(s|秒)?$"),
        Regex("^\\d{1,2}(s|秒)?跳过$")
    )

    private val rejectedFragments = listOf(
        "跳过此", "跳过介绍", "跳过引导", "跳过内容", "跳过设置", "跳过登录", "跳过教程"
    )

    fun normalize(raw: String): String {
        val builder = StringBuilder(raw.length)
        for (ch in raw.trim()) {
            when {
                ch in '０'..'９' -> builder.append('0' + (ch - '０'))
                ch == '\u3000' -> builder.append(' ')
                ch.isWhitespace() -> builder.append(' ')
                ch == '|' || ch == '·' || ch == '>' || ch == '》' || ch == '»' -> builder.append(' ')
                else -> builder.append(ch)
            }
        }
        return builder.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun compact(raw: String): String = normalize(raw).replace(" ", "").lowercase(Locale.ROOT)

    fun matches(raw: String, closeAdEnabled: Boolean): Boolean {
        val text = compact(raw)
        if (text.isEmpty() || text.length > 10) return false
        if (rejectedFragments.any { text.contains(it) }) return false
        if (text == "关闭广告") return closeAdEnabled
        return skipPatterns.any { it.matches(text) }
    }

    fun score(
        raw: String,
        bounds: Rect,
        screenWidth: Int,
        screenHeight: Int,
        clickable: Boolean,
        closeAdEnabled: Boolean
    ): Int {
        if (screenWidth <= 0 || screenHeight <= 0) return 0
        if (!matches(raw, closeAdEnabled)) return 0
        if (bounds.width() < 24 || bounds.height() < 18) return 0
        if (bounds.width() > screenWidth * 0.9f && bounds.height() > screenHeight * 0.3f) return 0

        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()

        val text = compact(raw)
        var score = when {
            text == "跳过" -> 120
            text == "关闭广告" -> 100
            text.startsWith("跳过") -> 100
            text.endsWith("跳过") -> 90
            else -> 80
        }
        if (clickable) score += 20
        val topRight = centerX > screenWidth * 0.5f && centerY < screenHeight * 0.35f
        val bottomRight = centerX > screenWidth * 0.55f && centerY > screenHeight * 0.7f
        if (topRight || bottomRight) score += 20 else if (centerY < screenHeight * 0.5f) score += 10
        return score
    }
}