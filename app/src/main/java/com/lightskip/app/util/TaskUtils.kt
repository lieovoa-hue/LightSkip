package com.lightskip.app.util

import android.app.Activity
import android.app.ActivityManager

object TaskUtils {

    fun setExcludeFromRecents(activity: Activity, exclude: Boolean) {
        val manager = activity.getSystemService(ActivityManager::class.java)
        runCatching {
            manager.appTasks.forEach { task -> task.setExcludeFromRecents(exclude) }
        }
    }
}
