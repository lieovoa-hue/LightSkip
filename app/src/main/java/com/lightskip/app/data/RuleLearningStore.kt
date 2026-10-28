package com.lightskip.app.data

import android.content.Context
import android.graphics.Rect
import com.lightskip.app.service.DiagnosticsStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class RuleLearningStore(context: Context) {

    data class LearnedRule(
        val viewId: String,
        val contentDescription: String,
        val className: String,
        val activityClass: String,
        val regionOnly: Boolean,
        val relX: Float,
        val relY: Float,
        val relW: Float,
        val relH: Float
    )

    private val sp = context.getSharedPreferences("lightskip_rules", Context.MODE_PRIVATE)

    init {
        if (sp.getInt("schema_version", 0) < 1) {
            sp.edit().clear().putInt("schema_version", 1).apply()
        }
    }

    fun getRules(packageName: String): List<LearnedRule> {
        val raw = sp.getString(key(packageName), null) ?: return emptyList()
        return runCatching {
            if (raw.trimStart().startsWith("[")) decodeArray(JSONArray(raw)) else listOf(decodeObject(JSONObject(raw)))
        }.getOrDefault(emptyList()).filterNot { isUnsafeRule(it) }
    }

    fun ruleCount(packageName: String): Int = getRules(packageName).size

    fun storedAppCount(): Int = sp.all.keys.count { key ->
        key.startsWith(KEY_PREFIX) && !sp.getString(key, null).isNullOrBlank()
    }

    fun removeRule(packageName: String, rule: LearnedRule): Boolean {
        val rules = getRules(packageName).toMutableList()
        val index = rules.indexOfFirst { sameRule(it, rule) }
        if (index < 0) return false
        rules.removeAt(index)
        writeRules(packageName, rules)
        return true
    }

    fun clearAll(): Int {
        val keys = sp.all.keys.filter { it.startsWith(KEY_PREFIX) }
        if (keys.isEmpty()) return 0
        val editor = sp.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
        return keys.size
    }

    fun matches(candidate: DiagnosticsStore.PopupCandidate, rule: LearnedRule): Boolean {
        if (rule.regionOnly) return false
        if (rule.viewId.isNotBlank() && candidate.viewId == rule.viewId) return true
        if (rule.contentDescription.isNotBlank() && candidate.contentDescription == rule.contentDescription) return true
        if (rule.className.isNotBlank() && candidate.className == rule.className) {
            val window = usableWindow(candidate.bounds, candidate.windowBounds)
            val relX = (candidate.bounds.exactCenterX() - window.left) / window.width()
            val relY = (candidate.bounds.exactCenterY() - window.top) / window.height()
            val relW = candidate.bounds.width().toFloat() / window.width()
            val relH = candidate.bounds.height().toFloat() / window.height()
            return abs(relX - rule.relX) <= 0.08f &&
                abs(relY - rule.relY) <= 0.08f &&
                abs(relW - rule.relW) <= 0.10f &&
                abs(relH - rule.relH) <= 0.10f
        }
        return false
    }

    fun matchesContext(rule: LearnedRule, activityClass: String): Boolean {
        if (rule.activityClass.isBlank() || activityClass.isBlank()) return true
        val recorded = rule.activityClass.substringAfterLast('.')
        val current = activityClass.substringAfterLast('.')
        return recorded == current || activityClass.contains(rule.activityClass) || rule.activityClass.contains(current)
    }

    fun clear(packageName: String) {
        sp.edit().remove(key(packageName)).apply()
    }

    private fun writeRules(packageName: String, rules: List<LearnedRule>) {
        if (rules.isEmpty()) {
            sp.edit().remove(key(packageName)).apply()
        } else {
            sp.edit().putString(key(packageName), encode(rules).toString()).apply()
        }
    }

    private fun sameRule(a: LearnedRule, b: LearnedRule): Boolean {
        if (a.viewId.isNotBlank() && b.viewId.isNotBlank()) return a.viewId == b.viewId
        if (a.contentDescription.isNotBlank() && b.contentDescription.isNotBlank()) {
            return a.contentDescription == b.contentDescription
        }
        return abs(a.relX - b.relX) < 0.001f &&
            abs(a.relY - b.relY) < 0.001f &&
            abs(a.relW - b.relW) < 0.001f &&
            abs(a.relH - b.relH) < 0.001f
    }

    private fun encode(rules: List<LearnedRule>): JSONArray {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(JSONObject().apply {
                put("viewId", rule.viewId)
                put("desc", rule.contentDescription)
                put("className", rule.className)
                put("activityClass", rule.activityClass)
                put("regionOnly", rule.regionOnly)
                put("relX", rule.relX.toDouble())
                put("relY", rule.relY.toDouble())
                put("relW", rule.relW.toDouble())
                put("relH", rule.relH.toDouble())
            })
        }
        return array
    }

    private fun decodeArray(array: JSONArray): List<LearnedRule> {
        val result = mutableListOf<LearnedRule>()
        for (i in 0 until array.length()) {
            runCatching { decodeObject(array.getJSONObject(i)) }.getOrNull()?.let { result.add(it) }
        }
        return result
    }

    private fun decodeObject(json: JSONObject): LearnedRule = LearnedRule(
        viewId = json.optString("viewId"),
        contentDescription = json.optString("desc"),
        className = json.optString("className"),
        activityClass = json.optString("activityClass"),
        regionOnly = json.optBoolean("regionOnly", false),
        relX = json.optDouble("relX", 0.0).toFloat(),
        relY = json.optDouble("relY", 0.0).toFloat(),
        relW = json.optDouble("relW", 0.0).toFloat(),
        relH = json.optDouble("relH", 0.0).toFloat()
    )

    private fun usableWindow(bounds: Rect, windowBounds: Rect): Rect {
        if (windowBounds.width() > 0 && windowBounds.height() > 0) return windowBounds
        return Rect(0, 0, bounds.right.coerceAtLeast(1), bounds.bottom.coerceAtLeast(1))
    }

    private fun key(packageName: String): String = "rule_$packageName"

    private fun isUnsafeRule(rule: LearnedRule): Boolean {
        if (rule.regionOnly || rule.viewId.isBlank()) return false
        val id = rule.viewId.lowercase()
        val explicit = CLOSE_ID_HINTS.any { id.contains(it) }
        return !explicit && NON_CLOSE_ID_HINTS.any { id.contains(it) }
    }

    companion object {
        private const val KEY_PREFIX = "rule_"
        private val CLOSE_ID_HINTS = listOf("close", "dismiss", "skip")
        private val NON_CLOSE_ID_HINTS = listOf(
            "menu", "more", "action", "detail", "banner", "video",
            "search", "tab", "avatar", "profile", "toolbar"
        )
    }
}
