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

    private val skipIdHints = listOf(
        "skip", "ad_skip", "splash_skip", "tv_skip", "btn_skip", "jump"
    )

    private val closeIdHints = listOf(
        "close", "dismiss", "ad_close", "iv_close", "dialog_close", "img_close", "btn_close"
    )

    private val closeTexts = setOf("关闭", "关闭广告", "close", "dismiss", "×", "x")

    private val nonCloseTexts = listOf(
        "立即领取", "查看详情", "知道了", "确定", "同意", "允许", "打开", "下载", "安装", "刷新", "重试", "领取", "购买", "支付", "设置", "聊天", "消息", "首页", "我的", "搜索", "返回", "菜单", "更多", "分享", "发现", "推荐", "关注", "个人", "头像"
    )

    fun normalize(raw: String): String {
        val builder = StringBuilder(raw.length)
        var pendingSpace = false
        for (ch in raw) {
            val mapped = when {
                ch in '０'..'９' -> '0' + (ch - '０')
                ch == '\u3000' -> ' '
                ch.isWhitespace() -> ' '
                ch == '|' || ch == '·' || ch == '>' || ch == '》' || ch == '»' -> ' '
                else -> ch
            }
            if (mapped == ' ') {
                if (builder.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    builder.append(' ')
                    pendingSpace = false
                }
                builder.append(mapped)
            }
        }
        return builder.toString()
    }

    fun compact(raw: String): String = normalize(raw).replace(" ", "").lowercase(Locale.ROOT)

    fun hasSplashSignal(
        raw: String,
        viewId: String,
        contentDescription: String
    ): Boolean {
        val text = compact(raw)
        if (matchesSplashText(text)) return true
        val id = viewId.lowercase(Locale.ROOT)
        if (skipIdHints.any { id.contains(it) }) return true
        val desc = compact(contentDescription)
        return desc.isNotEmpty() &&
            (matchesSplashText(desc) || skipIdHints.any { desc.contains(it) })
    }

    fun scoreSplash(
        raw: String,
        viewId: String,
        contentDescription: String,
        bounds: Rect,
        screenWidth: Int,
        screenHeight: Int,
        clickable: Boolean
    ): Int {
        if (screenWidth <= 0 || screenHeight <= 0) return 0
        if (bounds.width() < 24 || bounds.height() < 18) return 0
        if (bounds.width() > screenWidth * 0.9f && bounds.height() > screenHeight * 0.3f) return 0

        val text = compact(raw)
        val id = viewId.lowercase(Locale.ROOT)
        val desc = compact(contentDescription)
        val textHit = matchesSplashText(text)
        val idHit = skipIdHints.any { id.contains(it) }
        val descHit = desc.isNotEmpty() && (matchesSplashText(desc) || skipIdHints.any { desc.contains(it) })
        if (!textHit && !idHit && !descHit) return 0

        var score = when {
            textHit && text == "跳过" -> 120
            textHit && text in setOf("关闭广告", "关闭") -> 100
            textHit && text in setOf("×", "x") -> 85
            textHit && text.startsWith("跳过") -> 100
            textHit && text.endsWith("跳过") -> 90
            idHit -> 90
            descHit -> 85
            else -> 0
        }
        if (clickable) score += 20
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val topRight = centerX > screenWidth * 0.5f && centerY < screenHeight * 0.35f
        val bottomRight = centerX > screenWidth * 0.55f && centerY > screenHeight * 0.7f
        if (topRight || bottomRight) score += 20 else if (centerY < screenHeight * 0.5f) score += 10
        return score
    }

    fun scorePopup(
        raw: String,
        viewId: String,
        contentDescription: String,
        className: String,
        bounds: Rect,
        windowBounds: Rect,
        clickable: Boolean
    ): Int {
        if (!clickable) return 0
        if (bounds.width() < 18 || bounds.height() < 18) return 0
        val window = if (windowBounds.width() > 0 && windowBounds.height() > 0) windowBounds else bounds
        if (bounds.width() > window.width() * 0.35f || bounds.height() > window.height() * 0.25f) return 0

        val text = compact(raw)
        val id = viewId.lowercase(Locale.ROOT)
        val desc = compact(contentDescription)
        if (text.startsWith("跳过")) return 0
        if (nonCloseTexts.any { text.contains(it) }) return 0
        if (text.length > 10 && text !in closeTexts) return 0

        val idHit = closeIdHints.any { id.contains(it) }
        val descHit = closeTexts.contains(desc) || closeIdHints.any { desc.contains(it) }
        val strongText = text == "关闭广告"
        val weakText = text in closeTexts

        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val topRight = centerX > window.left + window.width() * 0.55f &&
            centerY < window.top + window.height() * 0.35f
        val small = bounds.width() <= window.width() * 0.12f && bounds.height() <= window.height() * 0.10f

        var score = when {
            idHit -> 110
            descHit -> 100
            strongText -> 90
            weakText -> 60
            topRight && small -> 40
            else -> 0
        }
        if (score == 0) return 0
        if (topRight) score += 25
        if (small) score += 10
        if (className.contains("Image", ignoreCase = true) || className.contains("Button", ignoreCase = true)) score += 5
        return score
    }

    fun isStrongCloseId(viewId: String): Boolean {
        val id = viewId.lowercase(Locale.ROOT)
        return closeIdHints.any { id.contains(it) }
    }

    private fun matchesSplashText(text: String): Boolean {
        if (text.isEmpty() || text.length > 10) return false
        if (rejectedFragments.any { text.contains(it) }) return false
        if (text in setOf("关闭广告", "关闭", "×", "x")) return true
        return skipPatterns.any { it.matches(text) }
    }
}
