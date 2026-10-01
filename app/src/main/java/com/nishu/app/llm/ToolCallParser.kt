package com.nishu.app.llm

import org.json.JSONException
import org.json.JSONObject

data class ParsedToolCall(
    val name: String,
    val arguments: JSONObject,
    /** The arguments exactly as the model wrote them, so history re-renders byte-for-byte. */
    val rawArguments: String,
    /** True when more than one tool call block was present; only the first is used. */
    val anomaly: Boolean,
)

/** Parses `<tool_call>\n{"name": ..., "arguments": {...}}\n</tool_call>`. Keys are name/arguments. */
class ToolCallParser {
    private val closed = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
    private val open = Regex("<tool_call>\\s*(\\{.*\\})\\s*$", RegexOption.DOT_MATCHES_ALL)

    fun parse(text: String): ParsedToolCall? {
        val matches = closed.findAll(text).toList()
        val first = matches.firstOrNull()?.groupValues?.get(1) ?: open.find(text)?.groupValues?.get(1) ?: return null
        return try {
            val obj = JSONObject(first)
            val name = obj.getString("name")
            val args = obj.opt("arguments")
            val parsed = when (args) {
                is JSONObject -> args
                is String -> JSONObject(args)
                null -> JSONObject()
                else -> return null
            }
            ParsedToolCall(name, parsed, rawArguments(first) ?: parsed.toString(), matches.size > 1)
        } catch (e: JSONException) {
            null
        }
    }

    /** Slices the balanced `{...}` after `"arguments":` out of the original text. */
    private fun rawArguments(json: String): String? {
        val key = json.indexOf("\"arguments\"")
        if (key < 0) return null
        var i = json.indexOf(':', key) + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (j in i until json.length) {
            val c = json[j]
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return json.substring(i, j + 1)
                }
            }
        }
        return null
    }
}
