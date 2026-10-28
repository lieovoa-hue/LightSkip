package com.lightskip.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class ImportedRuleStore(context: Context) {

    data class PopupRule(
        val id: String,
        val action: String,
        val times: Int,
        val delayMs: Long
    )

    data class ImportResult(
        val matchedApps: Int,
        val ruleCount: Int,
        val unmatchedHashes: Int,
        val packages: Set<String>
    )

    private val sp = context.applicationContext.getSharedPreferences(
        "lightskip_imported_rules",
        Context.MODE_PRIVATE
    )

    fun importAllRules(raw: String, installedPackages: Set<String>): ImportResult {
        val root = parseJson(raw)
        val entries = linkedMapOf<Int, Any>()
        when (root) {
            is JSONArray -> {
                for (index in 0 until root.length()) {
                    val item = root.optJSONObject(index) ?: continue
                    val keys = item.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val hash = key.toIntOrNull() ?: continue
                        entries[hash] = item.opt(key) ?: continue
                    }
                }
            }
            is JSONObject -> {
                if (root.has("popup_rules")) {
                    throw IllegalArgumentException("single_app_rules")
                }
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val hash = key.toIntOrNull() ?: continue
                    entries[hash] = root.opt(key) ?: continue
                }
            }
            else -> throw IllegalArgumentException("invalid_rules")
        }
        if (entries.isEmpty()) throw IllegalArgumentException("invalid_rules")

        val packagesByHash = installedPackages.associateBy { packageHash(it) }
        val editor = sp.edit()
        var matchedApps = 0
        var ruleCount = 0
        var unmatchedHashes = 0
        val packages = mutableSetOf<String>()

        for ((hash, value) in entries) {
            val packageName = packagesByHash[hash]
            if (packageName == null) {
                unmatchedHashes++
                continue
            }
            val rules = parseRuleValue(value)
            if (rules.isEmpty()) continue
            editor.putString(key(packageName), encode(rules).toString())
            matchedApps++
            ruleCount += rules.size
            packages.add(packageName)
        }
        editor.apply()
        return ImportResult(matchedApps, ruleCount, unmatchedHashes, packages)
    }

    fun importForPackage(packageName: String, raw: String): Int {
        val root = parseJson(raw)
        val rules = when (root) {
            is JSONArray -> parseRuleArray(root)
            is JSONObject -> {
                if (root.has("popup_rules")) {
                    parseRuleObject(root)
                } else {
                    val matching = root.opt(packageHash(packageName).toString())
                    if (matching != null) parseRuleValue(matching) else emptyList()
                }
            }
            else -> emptyList()
        }
        if (rules.isEmpty()) throw IllegalArgumentException("invalid_rules")
        sp.edit().putString(key(packageName), encode(rules).toString()).apply()
        return rules.size
    }

    fun getRules(packageName: String): List<PopupRule> {
        val raw = sp.getString(key(packageName), null) ?: return emptyList()
        return runCatching { parseRuleArray(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun hasRules(packageName: String): Boolean =
        !sp.getString(key(packageName), null).isNullOrBlank()

    fun importedAppCount(): Int = sp.all.keys.count { key ->
        key.startsWith(KEY_PREFIX) && !sp.getString(key, null).isNullOrBlank()
    }

    private fun parseRuleValue(value: Any): List<PopupRule> {
        return when (value) {
            is String -> runCatching {
                parseRuleObject(parseJson(value) as JSONObject)
            }.getOrDefault(emptyList())
            is JSONObject -> parseRuleObject(value)
            else -> emptyList()
        }
    }

    private fun parseJson(raw: String): Any {
        return try {
            JSONTokener(raw).nextValue()
        } catch (first: Exception) {
            val cleaned = raw.replace(Regex(",\\s*([}\\]])"), "$1")
            if (cleaned == raw) throw first
            JSONTokener(cleaned).nextValue()
        }
    }

    private fun parseRuleObject(json: JSONObject): List<PopupRule> {
        val array = json.optJSONArray("popup_rules")
        if (array != null) return parseRuleArray(array)
        if (json.has("id") || json.has("action")) return parseRuleArray(JSONArray().put(json))
        return emptyList()
    }

    private fun parseRuleArray(array: JSONArray): List<PopupRule> {
        val result = mutableListOf<PopupRule>()
        for (index in 0 until array.length()) {
            if (result.size >= MAX_RULES_PER_APP) break
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim().take(MAX_TEXT_LENGTH)
            val action = item.optString("action").trim().take(MAX_TEXT_LENGTH)
            if (id.isEmpty() || action.isEmpty()) continue
            result.add(
                PopupRule(
                    id = id,
                    action = action,
                    times = item.optInt("times", 1).coerceIn(1, 20),
                    delayMs = item.optLong("delay_popup", 0L).coerceIn(0L, 15_000L)
                )
            )
        }
        return result
    }

    private fun encode(rules: List<PopupRule>): JSONArray {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject().apply {
                    put("id", rule.id)
                    put("action", rule.action)
                    put("times", rule.times)
                    put("delay_popup", rule.delayMs)
                }
            )
        }
        return array
    }

    private fun key(packageName: String): String = KEY_PREFIX + packageName

    companion object {
        private const val KEY_PREFIX = "rules_"
        private const val MAX_RULES_PER_APP = 100
        private const val MAX_TEXT_LENGTH = 200

        fun packageHash(packageName: String): Int {
            var hash = 0
            for (ch in packageName) {
                hash = 31 * hash + ch.code
            }
            return hash
        }
    }
}
