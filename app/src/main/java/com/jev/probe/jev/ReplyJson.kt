package com.jev.probe.jev

import org.json.JSONTokener

/** JSONObject silently overwrites duplicate keys on Android. Never present a later
 * explanation as the user's message when a model emits text twice in one object. */
internal object ReplyJson {
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
