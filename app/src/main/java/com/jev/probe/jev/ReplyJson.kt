package com.jev.probe.jev

import org.json.JSONTokener

/** JSONObject silently overwrites duplicate keys on Android. Never present a later
 * explanation as the user's message when a model emits text twice in one object. */
internal object ReplyJson {
    /**
     * Which entries of the top-level `replies` array carry conflicting duplicate
     * keys. One bad row used to fail the whole batch: on a real device
     * (1.4-personal.111) a single row repeated `style` with two different values
     * and a usable batch was thrown away, forcing the user to wait through a
     * retry. Dropping only the ambiguous row keeps the other candidates.
     */
    fun ambiguousReplyIndexes(json: String): Set<Int> {
        val key = json.indexOf("\"replies\"")
        if (key < 0) return emptySet()
        val start = json.indexOf('[', key).takeIf { it >= 0 } ?: return emptySet()
        val out = HashSet<Int>()
        var index = -1
        var depth = 0
        var objectStart = -1
        var inString = false
        var i = start + 1
        while (i < json.length) {
            val c = json[i]
            if (inString) {
                when {
                    c == '\\' -> i++
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) { objectStart = i; index++ }
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && objectStart >= 0) {
                        if (hasConflictingKeys(json.substring(objectStart, i + 1))) out.add(index)
                        objectStart = -1
                    }
                }
                ']' -> if (depth == 0) return out
            }
            i++
        }
        return out
    }

    private fun hasConflictingKeys(json: String): Boolean {
        val seen = HashMap<String, Any?>()
        var i = 0
        var depth = 0
        while (i < json.length) {
            when (json[i]) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                '"' -> {
                    val start = i++
                    while (i < json.length) {
                        if (json[i] == '\\') { i += 2; continue }
                        if (json[i] == '"') break
                        i++
                    }
                    if (i >= json.length) return false
                    var next = i + 1
                    while (next < json.length && json[next].isWhitespace()) next++
                    if (depth == 1 && next < json.length && json[next] == ':') {
                        val name = JSONTokener(json.substring(start, i + 1)).nextValue() as? String
                        if (name != null) {
                            val value = runCatching { JSONTokener(json.substring(next + 1)).nextValue() }.getOrNull()
                            // Identical repeated text/style strings are unambiguous. A different
                            // later value (often a strategy explanation) must not win silently.
                            if (seen.containsKey(name) && !(name in setOf("text", "style") &&
                                    value is String && seen[name] == value)) return true
                            seen[name] = value
                        }
                    }
                }
            }
            i++
        }
        return false
    }

    fun requireUnambiguousKeys(json: String) {
        val objects = ArrayList<MutableMap<String, Any?>?>()
        var i = 0
        while (i < json.length) {
            when (json[i]) {
                '{' -> objects.add(HashMap())
                '[' -> objects.add(null)
                '}', ']' -> if (objects.isNotEmpty()) objects.removeAt(objects.lastIndex)
                '"' -> {
                    val start = i++
                    while (i < json.length) {
                        if (json[i] == '\\') { i += 2; continue }
                        if (json[i] == '"') break
                        i++
                    }
                    if (i >= json.length) return // The normal JSON parser reports malformed input.
                    var next = i + 1
                    while (next < json.length && json[next].isWhitespace()) next++
                    if (next < json.length && json[next] == ':') {
                        val key = JSONTokener(json.substring(start, i + 1)).nextValue() as? String
                        val seen = objects.lastOrNull()
                        if (key != null && seen != null) {
                            val value = runCatching { JSONTokener(json.substring(next + 1)).nextValue() }.getOrNull()
                            // Identical repeated text/style strings are unambiguous. A different
                            // later value (often a strategy explanation) must not win silently.
                            if (seen.containsKey(key) && !(key in setOf("text", "style") &&
                                    value is String && seen[key] == value))
                                throw IllegalStateException("回复接口返回冲突的重复JSON字段 请重试 本次未展示含义不确定的文本")
                            seen[key] = value
                        }
                    }
                }
            }
            i++
        }
    }
}
