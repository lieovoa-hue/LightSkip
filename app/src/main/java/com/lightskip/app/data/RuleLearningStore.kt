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

    data class SaveResult(
        val rule: LearnedRule,
        val added: Boolean
    )

    private val sp = context.getSharedPreferences("lightskip_rules", Context.MODE_PRIVATE)

    init {
        if (sp.getInt("schema_version", 0) < 1) {
            sp.edit().clear().putInt("schema_version", 1).apply()
        }
    }

    fun saveFromNode(
        packageName: String,
        viewId: String,
        contentDescription: String,
        className: String,
        bounds: Rect,
        windowBounds: Rect,
        activityClass: String
    ): SaveResult? {
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        val window = usableWindow(bounds, windowBounds)
        if (!isPlausibleCloseTarget(bounds, window, viewId, contentDescription)) return null
        val relX = ((bounds.exactCenterX() - window.left) / window.width()).coerceIn(0f, 1f)
        val relY = ((bounds.exactCenterY() - window.top) / window.height()).coerceIn(0f, 1f)
        val relW = (bounds.width().toFloat() / window.width()).coerceIn(0f, 1f)
        val relH = (bounds.height().toFloat() / window.height()).coerceIn(0f, 1f)
        val rule = LearnedRule(
            viewId = viewId,
            contentDescription = contentDescription,
            className = className,
            activityClass = activityClass,
            regionOnly = viewId.isBlank() && contentDescription.isBlank(),
            relX = relX,
            relY = relY,
            relW = relW,
            relH = relH
        )
        val rules = getRules(packageName).toMutableList()
        var duplicate = false
        rules.removeAll { existing ->
            val same = (rule.viewId.isNotBlank() && existing.viewId == rule.viewId) ||
                (rule.contentDescription.isNotBlank() && existing.contentDescription == rule.contentDescription) ||
                (abs(existing.relX - rule.relX) < 0.03f && abs(existing.relY - rule.relY) < 0.03f)
            if (same) duplicate = true
            same
        }
        rules.add(0, rule)
        while (rules.size > MAX_RULES) rules.removeAt(rules.lastIndex)
        sp.edit().putString(key(packageName), encode(rules).toString()).apply()
        return SaveResult(rule, !duplicate)
    }

    fun getRules(packageName: String): List<LearnedRule> {
        val raw = sp.getString(key(packageName), null) ?: return emptyList()
        return runCatching {
            if (raw.trimStart().startsWith("[")) decodeArray(JSONArray(raw)) else listOf(decodeObject(JSONObject(raw)))
        }.getOrDefault(emptyList())
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

    fun clearAll() {
        sp.edit().clear().putInt("schema_version", 1).apply()
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

    private fun isPlausibleCloseTarget(
        bounds: Rect,
        window: Rect,
        viewId: String,
        contentDescription: String
    ): Boolean {
        if (window.width() <= 0 || window.height() <= 0) return false
        val relW = bounds.width().toFloat() / window.width()
        val relH = bounds.height().toFloat() / window.height()
        if (relW <= 0f || relH <= 0f || relW > 0.22f || relH > 0.14f) return false
        if (relW * relH > 0.025f) return false

        val hint = (viewId + " " + contentDescription).lowercase()
        val explicitClose = listOf("close", "dismiss", "关闭", "取消").any { hint.contains(it) }
        if (explicitClose) return true

        if (abs(relW - relH) > 0.08f) return false
        val relX = (bounds.exactCenterX() - window.left) / window.width()
        val relY = (bounds.exactCenterY() - window.top) / window.height()
        val inCorner = relX >= 0.60f && (relY <= 0.35f || relY >= 0.65f)
        return inCorner
    }

    private fun key(packageName: String): String = "rule_$packageName"

    companion object {
        const val MAX_RULES = 5
    }
}
