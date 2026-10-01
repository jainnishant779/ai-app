package com.nishu.app.summarize

data class ExtractedItem(val text: String, val dueHint: String? = null, val owner: String? = null)

data class Extraction(val tasks: List<ExtractedItem>, val decisions: List<ExtractedItem>) {
    val isEmpty: Boolean get() = tasks.isEmpty() && decisions.isEmpty()
}

/**
 * Rule-based tier 3: no model. On the untuned model this beats the model, and it is what makes V0.1 demoable.
 * Covers English and Roman-script Hinglish cues.
 */
object HeuristicExtractor {
    private val task = Regex(
        "karna hai|karni hai|karne hai|kar dena|kar lena|bhejna hai|dekhna hai|lena hai|dena hai|\\bTODO\\b|" +
            "\\bneed to\\b|\\bhave to\\b|\\bmust\\b|\\bremind me\\b|\\bwill send\\b|\\bfollow up\\b|\\bshould send\\b",
        RegexOption.IGNORE_CASE,
    )
    private val decision = Regex(
        "\\bdecided\\b|decide kiya|tay hua|tay kiya|final hai|faisla|\\bagreed\\b|we will go with|let's go with|let us go with",
        RegexOption.IGNORE_CASE,
    )
    private val weekday = Regex(
        "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|somvar|mangalvar|budhvar|guruvar|shukravar|shanivar|ravivar)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val relative = Regex("\\b(today|tomorrow|tonight|kal|aaj|parso)\\b", RegexOption.IGNORE_CASE)

    /** A named weekday is a real deadline; "kal" is ambiguous (tomorrow or yesterday), so it only wins by default. */
    private fun dueHint(s: String): String? =
        (weekday.find(s) ?: relative.find(s))?.value?.replaceFirstChar { it.uppercase() }
    private val sentenceSplit = Regex("(?<=[.!?])\\s+|\\n+")

    fun extract(segments: List<String>, limit: Int = 8): Extraction {
        val tasks = LinkedHashMap<String, ExtractedItem>()
        val decisions = LinkedHashMap<String, ExtractedItem>()
        for (segment in segments) {
            for (raw in segment.split(sentenceSplit)) {
                val s = raw.trim().trimEnd('.', '!', '?').trim()
                if (s.length < 8) continue
                val key = s.lowercase()
                if (decision.containsMatchIn(s) && decisions.size < limit) decisions.putIfAbsent(key, ExtractedItem(s))
                if (task.containsMatchIn(s) && tasks.size < limit) {
                    tasks.putIfAbsent(key, ExtractedItem(s, dueHint = dueHint(s)))
                }
            }
        }
        return Extraction(tasks.values.toList(), decisions.values.toList())
    }
}
