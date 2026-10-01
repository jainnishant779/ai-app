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
        // 4-gram repeats: the same 4 words 4+ times within any 64-word span. Cut at the 2nd occurrence so one
        // natural repeat survives and the rest of the loop is dropped, however long it ran.
        val words = Regex("\\S+").findAll(text).toList()
        if (words.size >= 8) {
            val seenAt = HashMap<String, ArrayDeque<Int>>()
            for (i in 0..words.size - 4) {
                val gram = (0 until 4).joinToString(" ") { normalize(words[i + it].value) }
                val at = seenAt.getOrPut(gram) { ArrayDeque() }
                while (at.isNotEmpty() && at.first() < i - 64) at.removeFirst()
                at.addLast(i)
                if (at.size >= 4) return words[at.elementAt(1)].range.first
            }
        }
        return -1
    }
}
