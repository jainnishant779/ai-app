package com.nishu.app.summarize

/**
 * Packs transcript segments into chunks that fit the model's context. Uses the real tokenizer (Roman-script Hindi
 * tokenizes far worse than English, so chars/4 would overflow). Consecutive chunks overlap by about 20% so a decision
 * that spans a boundary survives.
 */
class Chunker(private val tokenCount: (String) -> Int) {

    fun chunk(segments: List<String>, hardCap: Int, target: Int, overlapFraction: Double = 0.2): List<String> {
        require(target in 1..hardCap)
        val pieces = segments.map { it.trim() }.filter { it.isNotEmpty() }.flatMap { split(it, target) }
        if (pieces.isEmpty()) return emptyList()
        val tokens = pieces.map { tokenCount(it) + 1 } // +1 for the joining space

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < pieces.size) {
            var end = start
            var sum = 0
            while (end < pieces.size && (end == start || sum + tokens[end] <= target)) {
                sum += tokens[end]
                end++
            }
            chunks += join(pieces.subList(start, end), hardCap)
            if (end >= pieces.size) break
            // Step back over the trailing pieces worth ~overlapFraction of this chunk, but always make progress.
            var back = end
            var overlap = 0
            while (back > start + 1 && overlap < sum * overlapFraction) {
                back--
                overlap += tokens[back]
            }
            start = if (back > start) back else end
        }
        return chunks
    }

    /** Splits one oversized segment on word boundaries into pieces of at most [target] tokens. */
    private fun split(text: String, target: Int): List<String> {
        if (tokenCount(text) <= target) return listOf(text)
        val words = text.split(Regex("\\s+"))
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (w in words) {
            val candidate = if (current.isEmpty()) w else "$current $w"
            if (current.isNotEmpty() && tokenCount(candidate) > target) {
                out += current.toString()
                current = StringBuilder(w)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun join(pieces: List<String>, hardCap: Int): String {
        var list = pieces
        var text = list.joinToString(" ")
        // Joining can tokenize slightly differently than the sum of parts; trim from the end if needed.
        while (list.size > 1 && tokenCount(text) > hardCap) {
            list = list.dropLast(1)
            text = list.joinToString(" ")
        }
        return text
    }
}
