package com.lightskip.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.lightskip.app.R
import com.lightskip.app.util.dp
import kotlin.math.min

class LogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.lime)
    }
    private val foregroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.forest)
    }
    private val rect = RectF()
    private val playPath = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = context.dp(40)
        setMeasuredDimension(
            resolveSize(size, widthMeasureSpec),
            resolveSize(size, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        rect.set(left, top, left + size, top + size)
        canvas.drawRoundRect(rect, size * 0.3f, size * 0.3f, backgroundPaint)

        playPath.reset()
        playPath.moveTo(left + size * 0.27f, top + size * 0.29f)
        playPath.lineTo(left + size * 0.27f, top + size * 0.71f)
        playPath.lineTo(left + size * 0.55f, top + size * 0.5f)
        playPath.close()
        canvas.drawPath(playPath, foregroundPaint)

        val barWidth = size * 0.075f
        val barHeight = size * 0.38f
        val barTop = top + size * 0.31f
        val barRadius = barWidth / 2f
        val firstLeft = left + size * 0.64f
        val secondLeft = firstLeft + barWidth + size * 0.055f
        canvas.drawRoundRect(
            firstLeft, barTop, firstLeft + barWidth, barTop + barHeight,
            barRadius, barRadius, foregroundPaint
        )
        canvas.drawRoundRect(
            secondLeft, barTop, secondLeft + barWidth, barTop + barHeight,
            barRadius, barRadius, foregroundPaint
        )
    }
}