package com.nishu.app.summarize

/**
 * Detects the repetition loops small models fall into. Rules mirror the dataset builder: a normalized sentence of
 * 6+ words may appear at most twice, and no 4-gram may appear 4+ times within the last 64 words.
 */
object RepetitionGuard {
    private const val MAX_SENTENCE_REPEATS = 3 // llm_research/dataset_builder.py MAX_SENTENCE_REPEATS
    private val splitter = Regex("[.!?\\n]+")

    private fun normalize(s: String) =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), "").replace(Regex("\\s+"), " ").trim()

    /** True once the text contains a loop. Cheap enough to call on every piece. */
    fun isLooping(text: String): Boolean = loopStart(text) >= 0

    /** Returns [text] cut where the repetition began, trimmed. Unchanged if there is no loop. */
    fun clean(text: String): String {
        val cut = loopStart(text)
        return (if (cut >= 0) text.substring(0, cut) else text).trim()
    }

    /** Index in [text] where the offending repeat starts, or -1. */
    private fun loopStart(text: String): Int {
        // Sentence repeats.
        val seen = HashMap<String, Int>()
        var pos = 0
        for (m in splitter.findAll(text)) {
            val sentence = text.substring(pos, m.range.first)
            val norm = normalize(sentence)
            if (norm.split(' ').size >= 6) {
                val n = (seen[norm] ?: 0) + 1
                seen[norm] = n
                if (n >= MAX_SENTENCE_REPEATS) return pos
            }
            pos = m.range.last + 1
        }
        // 4-gram repeats within the recent window.
        val words = Regex("\\S+").findAll(text).toList()
        if (words.size >= 8) {
            val window = words.takeLast(64)
            val counts = HashMap<String, Int>()
            for (i in 0..window.size - 4) {
                val gram = (0 until 4).joinToString(" ") { normalize(window[i + it].value) }
                val n = (counts[gram] ?: 0) + 1
                counts[gram] = n
                if (n >= 4) return window[i].range.first
            }
        }
        return -1
    }
}
