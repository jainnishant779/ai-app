package com.nishu.app.summarize

/** Repairs JSON that was cut off mid-generation by tracking string state and bracket depth. */
object JsonSalvage {
    fun repair(raw: String): String? {
        val start = raw.indexOfFirst { it == '{' }
        if (start < 0) return null
        val s = raw.substring(start)
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var end = s.length
        for ((i, c) in s.withIndex()) {
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> {
                    if (stack.isEmpty() || stack.last() != c) return null
                    stack.removeLast()
                    if (stack.isEmpty()) { end = i + 1; break }
                }
            }
        }
        var out = s.substring(0, end)
        if (stack.isEmpty() && !inString) return out
        if (escaped) out = out.dropLast(1)
        if (inString) out += "\""
        out = out.trimEnd()
        // Drop a dangling comma or a key without a value: `, "key":` / `"key"`.
        out = out.replace(Regex(",\\s*\"[^\"]*\"\\s*:\\s*$"), "")
        out = out.replace(Regex("\"[^\"]*\"\\s*:\\s*$"), "")
        // A bare string directly inside an object is a key whose colon never arrived.
        if (stack.lastOrNull() == '}') out = out.replace(Regex("(?<=[{,])\\s*\"[^\"]*\"\\s*$"), "")
        out = out.trimEnd().trimEnd(',')
        return out + stack.reversed().joinToString("")
    }
}
