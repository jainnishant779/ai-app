package com.nishu.app.summarize

/**
 * The stock model answers in markdown with echoed labels ("**Summarize**: ...", "**Key Points**: ..."). Strip that so
 * the UI shows plain bullets, and drop lines that only repeat an earlier one.
 */
object SummaryText {
    private val label = Regex("^(summarize|summary|key points?|tl;dr|bullet points?|notes?)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val bulletMark = Regex("^[\\s\\-*•\\d.)]+")

    fun clean(raw: String): String {
        val seen = HashSet<String>()
        return raw.lines().map { line ->
            var s = line.replace("**", "").replace("__", "").replace("`", "").trim()
            s = s.trimStart('#').trim()
            s = s.replace(bulletMark, "").trim()
            s.replace(label, "").trim()
        }.filter { it.any(Char::isLetterOrDigit) }
            .filter { seen.add(it.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()) }
            .joinToString("\n") { "- $it" }
    }
}
