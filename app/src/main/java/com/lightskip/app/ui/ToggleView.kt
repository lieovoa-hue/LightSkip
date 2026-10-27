package com.lightskip.app.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.lightskip.app.R
import com.lightskip.app.util.dp

class ToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val lightStyle: Boolean
    private val offColor: Int
    private val onColor: Int
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val argbEvaluator = ArgbEvaluator()

    private var checked = false
    private var progress = 0f
    private var animator: ValueAnimator? = null

    var onCheckedChange: ((Boolean) -> Unit)? = null

    val isChecked: Boolean
        get() = checked

    init {
        val typed = context.obtainStyledAttributes(attrs, R.styleable.ToggleView)
        lightStyle = typed.getBoolean(R.styleable.ToggleView_lightStyle, false)
        typed.recycle()

        offColor = if (lightStyle) Color.argb(56, 255, 255, 255) else context.getColor(R.color.switch_off)
        onColor = if (lightStyle) context.getColor(R.color.lime) else context.getColor(R.color.forest)
        thumbPaint.color = Color.WHITE

        isClickable = true
        isFocusable = true
        setOnClickListener { setChecked(!checked, notify = true) }
    }

    fun setChecked(value: Boolean, animate: Boolean = true, notify: Boolean = false) {
        if (checked == value) return
        checked = value
        val target = if (value) 1f else 0f
        animator?.cancel()
        if (animate) {
            animator = ValueAnimator.ofFloat(progress, target).apply {
                duration = 180L
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            progress = target
            invalidate()
        }
        if (notify) onCheckedChange?.invoke(value)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(context.dp(38), widthMeasureSpec),
            resolveSize(context.dp(22), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val trackColor = argbEvaluator.evaluate(progress, offColor, onColor) as Int
        trackPaint.color = trackColor
        canvas.drawRoundRect(0f, 0f, width.toFloat(), h, h / 2f, h / 2f, trackPaint)

        val padding = context.dp(2).toFloat()
        val radius = h / 2f - padding
        val startX = padding + radius
        val endX = width - padding - radius
        val thumbX = startX + (endX - startX) * progress

        thumbPaint.color = if (lightStyle && checked) context.getColor(R.color.forest) else Color.WHITE
        canvas.drawCircle(thumbX, h / 2f, radius, thumbPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = checked
        info.isClickable = true
    }
}