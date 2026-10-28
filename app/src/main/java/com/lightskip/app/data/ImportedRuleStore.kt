package com.lightskip.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class ImportedRuleStore(
    context: Context,
    prefsName: String = "lightskip_imported_rules",
    private val builtinAsset: String = "builtin_rules.json"
) {

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
        prefsName,
        Context.MODE_PRIVATE
    )

    fun importAllRules(raw: String, installedPackages: Set<String>): ImportResult {
        val root = parseJson(raw)
        if (root is JSONObject && root.has("popup_rules")) {
            throw IllegalArgumentException("single_app_rules")
        }
        return mergeRules(raw, installedPackages)
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

    fun importBuiltinRules(
        context: Context,
        installedPackages: Set<String>
    ): ImportResult? {
        val raw = runCatching {
            context.assets.open(builtinAsset).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return mergeRules(raw, installedPackages)
    }

    fun getRules(packageName: String): List<PopupRule> {
        val raw = sp.getString(key(packageName), null) ?: return emptyList()
        return runCatching { parseRuleArray(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun setRules(packageName: String, rules: List<PopupRule>) {
        if (rules.isEmpty()) {
            sp.edit().remove(key(packageName)).apply()
        } else {
            sp.edit().putString(key(packageName), encode(rules).toString()).apply()
        }
    }

    fun hasRules(packageName: String): Boolean =
        !sp.getString(key(packageName), null).isNullOrBlank()

    fun ruleCount(packageName: String): Int {
        val raw = sp.getString(key(packageName), null) ?: return 0
        return runCatching { JSONArray(raw).length() }.getOrDefault(0)
    }

    fun clearAllRules(): Int {
        val keys = sp.all.keys.filter { it.startsWith(KEY_PREFIX) }
        if (keys.isEmpty()) return 0
        val editor = sp.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
        return keys.size
    }

    fun importedAppCount(): Int = sp.all.keys.count { key ->
        key.startsWith(KEY_PREFIX) && !sp.getString(key, null).isNullOrBlank()
    }

    fun exportAllRules(): String {
        val array = JSONArray()
        sp.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .sorted()
            .forEach { storedKey ->
                val packageName = storedKey.removePrefix(KEY_PREFIX)
                val rules = getRules(packageName)
                if (rules.isEmpty()) return@forEach
                val value = JSONObject()
                    .put("popup_rules", encode(rules))
                    .toString()
                array.put(JSONObject().put(packageHash(packageName).toString(), value))
            }
        return array.toString()
    }

    fun exportPackageRules(packageName: String): String {
        return JSONObject()
            .put("popup_rules", encode(getRules(packageName)))
            .toString()
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

    private fun mergeRules(raw: String, installedPackages: Set<String>): ImportResult {
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
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val hash = key.toIntOrNull() ?: continue
                    entries[hash] = root.opt(key) ?: continue
                }
            }
            else -> throw IllegalArgumentException("invalid_rules")
        }
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
            val incoming = parseRuleValue(value)
            if (incoming.isEmpty()) continue
            matchedApps++
            val merged = getRules(packageName).toMutableList()
            var added = 0
            for (rule in incoming) {
                val index = merged.indexOfFirst { it.id == rule.id && it.action == rule.action }
                if (index < 0) {
                    merged.add(rule)
                    added++
                } else if (merged[index].times != rule.times || merged[index].delayMs != rule.delayMs) {
                    merged[index] = rule
                    added++
                }
            }
            if (added == 0) continue
            while (merged.size > MAX_RULES_PER_APP) merged.removeAt(merged.lastIndex)
            editor.putString(key(packageName), encode(merged).toString())
            ruleCount += added
            packages.add(packageName)
        }
        editor.apply()
        return ImportResult(matchedApps, ruleCount, unmatchedHashes, packages)
    }

    private fun parseJson(raw: String): Any {
        val candidate = extractJson(raw)
        return try {
            JSONTokener(candidate).nextValue()
        } catch (first: Exception) {
            val cleaned = candidate.replace(Regex(",\\s*([}\\]])"), "$1")
            if (cleaned == candidate) throw first
            JSONTokener(cleaned).nextValue()
        }
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        val arrayStart = trimmed.indexOf('[')
        val objectStart = trimmed.indexOf('{')
        val start = when {
            arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart) -> arrayStart
            objectStart >= 0 -> objectStart
            else -> 0
        }
        val end = if (start < trimmed.length && trimmed[start] == '[') {
            trimmed.lastIndexOf(']')
        } else {
            trimmed.lastIndexOf('}')
        }
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
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
