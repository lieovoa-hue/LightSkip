package com.lightskip.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lightskip.app.R

object RegionRecorderBridge {
    var start: ((String) -> Boolean)? = null
    var cancel: (() -> Unit)? = null
}

class RegionRecorder(
    private val service: AccessibilityService,
    private val onCaptured: (packageName: String, action: String) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var targetPackage: String? = null
    private var marker: View? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private var controls: View? = null
    private var controlsParams: WindowManager.LayoutParams? = null
    private var targetSeen = false
    private val timeout = Runnable { cancel() }
    private val outsideAppTimeout = Runnable { cancel() }

    fun start(packageName: String): Boolean {
        cancel()
        targetPackage = packageName
        targetSeen = false
        handler.postDelayed(timeout, WAIT_TIMEOUT_MS)
        return true
    }

    fun onForeground(packageName: String) {
        val target = targetPackage ?: return
        if (packageName != target) {
            if (targetSeen && packageName !in TRANSIENT_PACKAGES) {
                handler.removeCallbacks(outsideAppTimeout)
                handler.postDelayed(outsideAppTimeout, OUTSIDE_APP_GRACE_MS)
            }
            return
        }
        handler.removeCallbacks(outsideAppTimeout)
        targetSeen = true
        if (marker == null && controls == null) {
            handler.postDelayed({
                if (targetPackage != null && marker == null) showRecorder()
            }, CAPTURE_DELAY_MS)
        }
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        removeView(marker)
        removeView(controls)
        marker = null
        markerParams = null
        controls = null
        controlsParams = null
        targetPackage = null
        targetSeen = false
    }

    private fun showRecorder() {
        val metrics = service.resources.displayMetrics
        val size = px(20)
        val markerView = TextView(service).apply {
            text = "×"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_recorder_virtual_x)
        }
        val markerLayout = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - size) / 2
            y = (metrics.heightPixels - size) / 2
        }
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        markerView.setOnTouchListener { _, event ->
            val params = markerParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (event.rawX - downRawX)).toInt()
                        .coerceIn(0, metrics.widthPixels - size)
                    params.y = (startY + (event.rawY - downRawY)).toInt()
                        .coerceIn(0, metrics.heightPixels - size)
                    runCatching { windowManager.updateViewLayout(markerView, params) }
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> true
            }
        }

        val controlsView = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        controlsView.addView(TextView(service).apply {
            text = service.getString(R.string.rule_record_drag_hint)
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundResource(R.drawable.bg_recorder_pill)
            setPadding(px(14), px(9), px(14), px(9))
        })
        val actions = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, px(8), 0, 0)
        }
        actions.addView(TextView(service).apply {
            text = service.getString(R.string.rule_record_done)
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundResource(R.drawable.bg_recorder_pill)
            setPadding(px(18), px(9), px(18), px(9))
            setOnClickListener { completeRecording(markerView, size) }
        })
        actions.addView(TextView(service).apply {
            text = service.getString(R.string.rule_record_cancel)
            setTextColor(service.getColor(R.color.lime_glow))
            textSize = 12f
            setBackgroundResource(R.drawable.bg_recorder_cancel)
            setPadding(px(16), px(9), px(16), px(9))
            setOnClickListener { cancel() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = px(8) })
        controlsView.addView(actions)
        val controlsLayout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = px(110)
        }

        runCatching {
            windowManager.addView(markerView, markerLayout)
            windowManager.addView(controlsView, controlsLayout)
        }.onFailure {
            removeView(markerView)
            removeView(controlsView)
            cancel()
            Toast.makeText(service, R.string.rule_record_failed, Toast.LENGTH_LONG).show()
            return
        }
        marker = markerView
        markerParams = markerLayout
        controls = controlsView
        controlsParams = controlsLayout
    }

    private fun completeRecording(markerView: View, size: Int) {
        val packageName = targetPackage ?: return
        val location = IntArray(2)
        markerView.getLocationOnScreen(location)
        val bounds = Rect(
            location[0],
            location[1],
            location[0] + size,
            location[1] + size
        )
        val action = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
        cancel()
        Toast.makeText(service, R.string.rule_record_saved, Toast.LENGTH_SHORT).show()
        onCaptured(packageName, action)
    }

    private fun removeView(view: View?) {
        if (view == null) return
        runCatching { windowManager.removeView(view) }
    }

    private fun px(value: Int): Int =
        (value * service.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val WAIT_TIMEOUT_MS = 25_000L
        const val CAPTURE_DELAY_MS = 350L
        const val OUTSIDE_APP_GRACE_MS = 1_200L
        val TRANSIENT_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.meizu.flyme.launcher",
            "com.lightskip.app"
        )
    }
}
