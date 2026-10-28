package com.lightskip.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.text.Collator
import java.util.Locale

class AppRepository(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private var cached: List<AppEntry>? = null

    @Suppress("DEPRECATION")
    fun load(): List<AppEntry> {
        cached?.let { return it }

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(intent, 0)
        val byPackage = LinkedHashMap<String, AppEntry>()

        for (info in resolved) {
            val activityInfo = info.activityInfo ?: continue
            val packageName = activityInfo.packageName ?: continue
            if (packageName == context.packageName) continue


            val label = runCatching { info.loadLabel(packageManager).toString().trim() }
                .getOrDefault("")
                .ifBlank { packageName.substringAfterLast('.') }

            byPackage[packageName] = AppEntry(
                packageName = packageName,
                label = label,
                color = colorForPackage(packageName)
            )
        }

        val collator = Collator.getInstance(Locale.CHINA)
        val result = byPackage.values.sortedWith { a, b -> collator.compare(a.label, b.label) }
        cached = result
        return result
    }

    fun recommendedInstalled(): Set<String> {
        return load()
            .asSequence()
            .map { it.packageName }
            .filter { it in RECOMMENDED }
            .toSet()
    }

    companion object {
        private val PALETTE = intArrayOf(
            0xFFE8583B.toInt(),
            0xFFE24A5B.toInt(),
            0xFF2C6FD1.toInt(),
            0xFFF0832D.toInt(),
            0xFFD64242.toInt(),
            0xFF2E9C8A.toInt(),
            0xFFE56A9A.toInt(),
            0xFF7C5CDB.toInt(),
            0xFF3E8E5A.toInt(),
            0xFFC28A2C.toInt()
        )

        val RECOMMENDED: Set<String> = setOf(
            "com.android.browser",
            "com.meizu.browser",
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.sina.weibo",
            "com.xingin.xhs",
            "com.zhihu.android",
            "com.taobao.taobao",
            "com.tmall.wireless",
            "com.jingdong.app.mall",
            "com.eg.android.AlipayGphone",
            "com.netease.cloudmusic",
            "com.tencent.qqmusic",
            "com.autonavi.minimap",
            "com.baidu.BaiduMap",
            "tv.danmaku.bili",
            "com.ss.android.ugc.aweme",
            "com.smile.gifmaker",
            "com.kuaishou.nebula",
            "com.tencent.qqlive",
            "com.qiyi.video",
            "com.youku.phone",
            "com.tencent.weishi",
            "com.immomo.momo",
            "com.hpbr.bosszhipin",
            "com.spotify.music",
            "com.google.android.youtube",
            "com.instagram.android",
            "com.facebook.katana",
            "com.twitter.android",
            "com.reddit.frontpage",
            "com.amazon.mShop.android.shopping"
        )

        fun colorForPackage(packageName: String): Int {
            val index = (packageName.hashCode() and 0x7fffffff) % PALETTE.size
            return PALETTE[index]
        }
    }
}
