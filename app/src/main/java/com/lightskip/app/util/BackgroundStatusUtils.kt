package com.lightskip.app.util

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

object BackgroundStatusUtils {

    enum class State {
        UNRESTRICTED,
        ALLOWED,
        RESTRICTED,
        UNKNOWN
    }

    data class Snapshot(
        val state: State,
        val batteryWhitelisted: Boolean,
        val backgroundRestricted: Boolean?,
        val runAnyInBackgroundAllowed: Boolean?,
        val standbyBucket: Int?
    )

    @Suppress("DEPRECATION")
    fun read(context: Context): Snapshot {
        val power = context.getSystemService(PowerManager::class.java)
        val batteryWhitelisted = runCatching {
            power.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

        val backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
            }.getOrNull()
        } else {
            null
        }

        val appOps = context.getSystemService(AppOpsManager::class.java)
        val uid = context.applicationInfo.uid
        val runAnyInBackgroundOp = "android:run_any_in_background"
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    runAnyInBackgroundOp,
                    uid,
                    context.packageName
                )
            } else {
                appOps.checkOpNoThrow(
                    runAnyInBackgroundOp,
                    uid,
                    context.packageName
                )
            }
        }.getOrNull()
        val runAnyInBackgroundAllowed = when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
            else -> null
        }

        val standbyBucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                context.getSystemService(UsageStatsManager::class.java).appStandbyBucket
            }.getOrNull()
        } else {
            null
        }

        val restricted = backgroundRestricted == true ||
            runAnyInBackgroundAllowed == false ||
            standbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED ||
            standbyBucket == STANDBY_BUCKET_NEVER
        val state = when {
            batteryWhitelisted -> State.UNRESTRICTED
            restricted -> State.RESTRICTED
            backgroundRestricted == false || runAnyInBackgroundAllowed == true -> State.ALLOWED
            else -> State.UNKNOWN
        }
        return Snapshot(
            state = state,
            batteryWhitelisted = batteryWhitelisted,
            backgroundRestricted = backgroundRestricted,
            runAnyInBackgroundAllowed = runAnyInBackgroundAllowed,
            standbyBucket = standbyBucket
        )
    }

    private const val STANDBY_BUCKET_NEVER = 50
}
