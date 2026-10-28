package com.lightskip.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View

class DemoDelayedPopupActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val showPopup = Runnable {
        findViewById<View>(R.id.demo_delayed_card).visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_demo_delayed_popup)
        findViewById<View>(R.id.demo_popup_close).setOnClickListener { finish() }
        handler.postDelayed(showPopup, 2_000L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(showPopup)
        super.onDestroy()
    }
}
