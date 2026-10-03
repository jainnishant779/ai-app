package com.nishu.app.data

import com.nishu.app.data.db.NishuDatabase

/** What a chat is about: one recording, all recordings, or nothing in particular. */
sealed interface ChatScope {
    data object General : ChatScope
    data object AllRecordings : ChatScope
    data class Recording(val id: Long) : ChatScope
}

/**
 * Picks the transcript text a question needs, within a token budget: the model has 1024 tokens of context, so a
 * 40-minute transcript can never be sent whole. Retrieval is keyword overlap (works for Hinglish spellings because it
 * matches word stems loosely), not embeddings, which do not fit beside the LLM.
 */
class ChatContext(private val db: NishuDatabase, private val tokenCount: (String) -> Int) {

    /** Null for [ChatScope.General] or when nothing relevant exists. */
    suspend fun build(scope: ChatScope, question: String, budgetTokens: Int): String? = when (scope) {
        ChatScope.General -> null
        is ChatScope.Recording -> forRecording(scope.id, question, budgetTokens)
        ChatScope.AllRecordings -> forAll(question, budgetTokens)
    }

    private suspend fun forRecording(id: Long, question: String, budget: Int): String? {
        val lines = db.transcripts().get(id).map { it.text }
        if (lines.isEmpty()) return null
        val whole = lines.joinToString(" ")
        if (tokenCount(whole) <= budget) return whole
        // Too long: the summary for the big picture, then the parts of the transcript the question is about.
        val summary = db.summaries().get(id)?.bulletsText?.takeIf { it.isNotBlank() }
        val head = summary?.let { "Summary:\n$it\n\nRelevant parts:\n" } ?: ""
        return head + pick(lines.map { it to null }, question, budget - tokenCount(head))
    }

    private suspend fun forAll(question: String, budget: Int): String? {
        val keys = ChatRetrieval.keywords(question)
        val titles = HashMap<Long, String>()
        suspend fun title(id: Long) = titles.getOrPut(id) { db.conversations().get(id)?.title ?: "Recording" }
        val hits = if (keys.isEmpty()) emptyList()
        else db.transcripts().searchOnce(keys.joinToString(" OR ") { "$it*" }, 60)
        if (hits.isNotEmpty()) {
            return pick(hits.map { it.text to title(it.conversationId) }, question, budget).ifBlank { null }
        }
        // Nothing matched ("what did I record today?"): the latest summaries are the best overview.
        val recent = db.conversations().recent(5).mapNotNull { c ->
            db.summaries().get(c.id)?.bulletsText?.takeIf { it.isNotBlank() }?.let { "[${c.title}]\n$it" }
        }
        return ChatRetrieval.fit(recent, budget, tokenCount).ifBlank { null }
    }

    private fun pick(lines: List<Pair<String, String?>>, question: String, budget: Int): String {
        val ranked = ChatRetrieval.rank(lines.map { it.first }, question)
        val chosen = ArrayList<Int>()
        var used = 0
        for (i in ranked) {
            val text = lines[i].second?.let { t -> "[$t] ${lines[i].first}" } ?: lines[i].first
            val cost = tokenCount(text) + 1
            if (used + cost > budget) continue
            chosen += i
            used += cost
        }
        // Back in spoken order, so the model reads a conversation, not a shuffled list.
        return chosen.sorted().joinToString("\n") { i -> lines[i].second?.let { t -> "[$t] ${lines[i].first}" } ?: lines[i].first }
    }
}

/** Pure parts of retrieval, kept separate so they are unit-tested on the JVM. */
object ChatRetrieval {
    private val stop = setOf(
        "the", "and", "for", "what", "who", "how", "did", "does", "was", "were", "are", "you", "this", "that", "with",
        "kya", "kaun", "kab", "kaise", "kyun", "kyon", "mein", "main", "mai", "hai", "hain", "tha", "thi", "the", "ko",
        "ki", "ke", "ka", "se", "par", "pe", "aur", "bhi", "toh", "to", "ye", "yeh", "wo", "woh", "hua", "hui", "kiya",
        "kahan", "batao", "bata", "bolo", "tell", "about", "recording", "meeting", "conversation", "please",
    )

    fun keywords(question: String): List<String> =
        question.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in stop }
            .distinct()

    /** Indices of [lines], most relevant first. A shared prefix of 4+ letters counts, so "decide" meets "decided". */
    fun rank(lines: List<String>, question: String): List<Int> {
        val keys = keywords(question)
        if (keys.isEmpty()) return lines.indices.toList()
        val scores = lines.map { line ->
            val words = line.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }
            keys.count { k -> words.any { w -> w == k || (k.length >= 4 && w.length >= 4 && w.take(4) == k.take(4)) } }
        }
        return lines.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
            .filter { scores[it] > 0 }
            .ifEmpty { lines.indices.toList() }
    }

    /** As many whole entries as fit in [budget] tokens, in order. */
    fun fit(entries: List<String>, budget: Int, tokenCount: (String) -> Int): String {
        val out = StringBuilder()
        var used = 0
        for (e in entries) {
            val cost = tokenCount(e) + 2
            if (used + cost > budget) break
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(e)
            used += cost
        }
        return out.toString()
    }
}
