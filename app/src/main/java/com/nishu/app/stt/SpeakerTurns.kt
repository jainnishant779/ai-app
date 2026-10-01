package com.nishu.app.stt

data class SpeakerTurn(val startMs: Long, val endMs: Long, val speaker: Int)

/** A stretch of a speech segment spoken by one person. [speaker] is null when diarization gave no answer. */
data class SpeakerPiece(val startMs: Long, val endMs: Long, val speaker: Int?) {
    val durationMs: Long get() = endMs - startMs
}

object SpeakerTurns {
    /** Speakers numbered by first appearance (0, 1, 2...), so "Speaker 1" is whoever talks first. */
    fun renumber(turns: List<SpeakerTurn>): List<SpeakerTurn> {
        val order = LinkedHashMap<Int, Int>()
        turns.sortedBy { it.startMs }.forEach { order.getOrPut(it.speaker) { order.size } }
        return turns.map { it.copy(speaker = order.getValue(it.speaker)) }.sortedBy { it.startMs }
    }

    /**
     * Splits one VAD speech segment at speaker changes. Pieces shorter than [minPieceMs] are absorbed by the
     * longer neighbour, because whisper cannot read a fragment that short and a flickering label is worse than none.
     */
    fun split(segStartMs: Long, segEndMs: Long, turns: List<SpeakerTurn>, minPieceMs: Long = 1_000): List<SpeakerPiece> {
        val clipped = turns
            .filter { it.endMs > segStartMs && it.startMs < segEndMs }
            .map { SpeakerPiece(maxOf(it.startMs, segStartMs), minOf(it.endMs, segEndMs), it.speaker) }
            .filter { it.durationMs > 0 }
            .sortedBy { it.startMs }
        if (clipped.isEmpty()) return listOf(SpeakerPiece(segStartMs, segEndMs, null))

        // Turns can overlap (two people talking at once): keep the earlier one's end and start the next where it ends.
        val flat = ArrayList<SpeakerPiece>()
        for (p in clipped) {
            val last = flat.lastOrNull()
            val start = if (last != null && p.startMs < last.endMs) last.endMs else p.startMs
            if (p.endMs > start) flat += p.copy(startMs = start)
        }
        if (flat.isEmpty()) return listOf(SpeakerPiece(segStartMs, segEndMs, null))

        // Close gaps and cover the whole segment, so every sample belongs to someone.
        val covered = flat.mapIndexed { i, p ->
            p.copy(
                startMs = if (i == 0) segStartMs else p.startMs,
                endMs = if (i == flat.lastIndex) segEndMs else flat[i + 1].startMs,
            )
        }
        var pieces = mergeSame(covered)
        while (pieces.size > 1) {
            val shortest = pieces.withIndex().minByOrNull { it.value.durationMs }!!
            if (shortest.value.durationMs >= minPieceMs) break
            val i = shortest.index
            val left = pieces.getOrNull(i - 1)
            val right = pieces.getOrNull(i + 1)
            val adopt = when {
                left == null -> right!!
                right == null -> left
                left.durationMs >= right.durationMs -> left
                else -> right
            }
            pieces = mergeSame(pieces.toMutableList().also { it[i] = it[i].copy(speaker = adopt.speaker) })
        }
        return pieces
    }

    private fun mergeSame(pieces: List<SpeakerPiece>): List<SpeakerPiece> {
        val out = ArrayList<SpeakerPiece>()
        for (p in pieces) {
            val last = out.lastOrNull()
            if (last != null && last.speaker == p.speaker) out[out.lastIndex] = last.copy(endMs = p.endMs) else out += p
        }
        return out
    }

    fun label(speaker: Int?): String? = speaker?.let { "S${it + 1}" }
}

object SpeechChunks {
    /**
     * Whisper reads at most 30 s and silently drops the rest. Splits a long stretch into equal parts of at most
     * [maxSamples], so nothing is lost and no part is tiny.
     */
    fun split(total: Int, maxSamples: Int): List<IntRange> {
        if (total <= 0) return emptyList()
        val parts = (total + maxSamples - 1) / maxSamples
        val size = (total + parts - 1) / parts
        return (0 until parts).map { it * size until minOf(total, (it + 1) * size) }.filter { !it.isEmpty() }
    }
}
