package com.nishu.app.summarize

import com.nishu.app.data.db.DecisionEntity
import com.nishu.app.data.db.NishuDatabase
import com.nishu.app.data.db.SummaryEntity
import com.nishu.app.data.db.TaskEntity
import com.nishu.app.domain.model.ProcessingStage
import com.nishu.app.llm.ChatMessage
import com.nishu.app.llm.FinishReason
import com.nishu.app.llm.LLMEngine
import com.nishu.app.llm.Role
import com.nishu.app.llm.SamplerProfile
import org.json.JSONException
import org.json.JSONObject

/**
 * Summarizes a recording that is far longer than the model's 1024-token context: map each chunk, reduce the results,
 * then extract tasks and decisions through a 3-tier ladder (grammar JSON, salvaged JSON, heuristic rules).
 */
class MapReduceSummarizer(
    private val db: NishuDatabase,
    private val engine: LLMEngine,
    private val extractGrammar: String,
    private val modelId: String? = null,
    private val maxChunks: Int = MAX_CHUNKS,
) {
    private companion object {
        const val GEN_RESERVE = 160
        const val SLACK = 30
        const val MAX_CHUNKS = 12
        const val TARGET_FRACTION = 0.875
    }

    private fun user(prompt: String) = listOf(ChatMessage(Role.USER, prompt))

    /** Tokens the chat template adds around a user message of the given prompt text. */
    private fun wrapperTokens(prompt: (String) -> String): Int {
        val bytes = engine.template.renderTurns(user(prompt("")))
        return engine.tokenCount(String(bytes, Charsets.UTF_8))
    }

    suspend fun run(conversationId: Long, onStage: suspend (ProcessingStage) -> Unit = {}) {
        val conversations = db.conversations()
        val segments = db.transcripts().get(conversationId).map { it.text }
        if (segments.isEmpty()) {
            conversations.setStatus(conversationId, "DONE", null, "No speech was detected")
            return
        }

        onStage(ProcessingStage.SUMMARIZING)
        conversations.setStatus(conversationId, "SUMMARIZING", ProcessingStage.SUMMARIZING.name)

        val mapCap = engine.turnTokenBudget - wrapperTokens(Prompts::map) - GEN_RESERVE - SLACK
        val allChunks = Chunker(engine::tokenCount).chunk(segments, mapCap, (mapCap * TARGET_FRACTION).toInt())
        val chunks = allChunks.take(maxChunks)

        var failed = 0
        val mapOutputs = mutableListOf<String>()
        for (chunk in chunks) {
            val out = generateClean(Prompts.map(chunk), 160)
                .ifBlank { generateClean(Prompts.map(chunk), 160) } // one retry on empty output
            if (out.isBlank()) failed++ else mapOutputs += out
        }
        if (mapOutputs.isEmpty()) {
            conversations.setStatus(conversationId, "FAILED", ProcessingStage.SUMMARIZING.name, "Summary unavailable")
            return
        }

        val bullets = reduce(mapOutputs)
        db.summaries().upsert(
            SummaryEntity(conversationId, bullets, chunks.size, failed, System.currentTimeMillis(), modelId),
        )

        onStage(ProcessingStage.EXTRACTING_TASKS)
        conversations.setStatus(conversationId, "SUMMARIZING", ProcessingStage.EXTRACTING_TASKS.name)
        val (extraction, confidence) = extract(bullets, segments)
        db.tasks().clear(conversationId)
        db.tasks().insertAll(
            extraction.tasks.map { TaskEntity(conversationId = conversationId, text = it.text, owner = it.owner, dueHint = it.dueHint, extractionConfidence = confidence) },
        )

        onStage(ProcessingStage.IDENTIFYING_DECISIONS)
        conversations.setStatus(conversationId, "SUMMARIZING", ProcessingStage.IDENTIFYING_DECISIONS.name)
        db.decisions().clear(conversationId)
        db.decisions().insertAll(
            extraction.decisions.map { DecisionEntity(conversationId = conversationId, text = it.text, extractionConfidence = confidence) },
        )

        onStage(ProcessingStage.SAVING)
        conversations.setStatus(conversationId, "SUMMARIZING", ProcessingStage.SAVING.name)
        val detail = if (allChunks.size > chunks.size) "Summary covers the first ~${chunks.size * 2} minutes" else null
        conversations.setStatus(conversationId, "DONE", null, detail)
    }

    /** Hierarchical reduce: never truncates; groups outputs until they fit one prompt. */
    private suspend fun reduce(outputs: List<String>): String {
        var level = outputs
        val cap = engine.turnTokenBudget - wrapperTokens(Prompts::reduce) - GEN_RESERVE - SLACK
        while (level.size > 1) {
            val joined = level.joinToString("\n")
            if (engine.tokenCount(joined) <= cap) {
                return generateClean(Prompts.reduce(joined), 200).ifBlank { joined }
            }
            val groups = mutableListOf<MutableList<String>>(mutableListOf())
            var size = 0
            for (o in level) {
                val t = engine.tokenCount(o)
                if (size + t > cap && groups.last().isNotEmpty()) { groups.add(mutableListOf()); size = 0 }
                groups.last().add(o)
                size += t
            }
            level = groups.map { g ->
                val text = g.joinToString("\n")
                if (g.size == 1) text else generateClean(Prompts.reduce(text), 200).ifBlank { text }
            }
        }
        return level.single()
    }

    private suspend fun extract(bullets: String, segments: List<String>): Pair<Extraction, String> {
        val r = engine.generate(user(Prompts.extract(bullets)), SamplerProfile.Grammar(extractGrammar), 220)
        parse(r.text, bullets)?.takeIf { !it.isEmpty && r.finishReason != FinishReason.LENGTH }?.let { return it to "MODEL_JSON" }
        JsonSalvage.repair(r.text)?.let { parse(it, bullets) }?.takeIf { !it.isEmpty }?.let { return it to "MODEL_SALVAGED" }
        return HeuristicExtractor.extract(segments) to "HEURISTIC"
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()

    /** An untuned 0.6B model pads the JSON with restated summary lines and one-word "decisions"; drop those. */
    private fun plausible(item: String, bullets: String): Boolean {
        val n = norm(item)
        if (n.split(' ').size < 3) return false
        return bullets.lines().none { norm(it.trimStart('-', '*', '•')) == n }
    }

    private fun parse(text: String, bullets: String): Extraction? = try {
        val obj = JSONObject(text.trim())
        val tasks = obj.optJSONArray("tasks")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { t ->
                t.optString("text").takeIf { it.isNotBlank() }?.let { s ->
                    ExtractedItem(s, dueHint = t.optString("due").ifBlank { null }, owner = t.optString("owner").ifBlank { null })
                }
            }
        }.orEmpty()
        val decisions = obj.optJSONArray("decisions")?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }.map { ExtractedItem(it) }
        }.orEmpty()
        Extraction(tasks.filter { plausible(it.text, bullets) }.take(8), decisions.filter { plausible(it.text, bullets) }.take(8))
    } catch (e: JSONException) {
        null
    }

    /** Greedy generation that stops and truncates when the model starts looping. */
    private suspend fun generateClean(prompt: String, maxTokens: Int): String {
        val sb = StringBuilder()
        val r = engine.generate(user(prompt), SamplerProfile.Greedy, maxTokens) { piece ->
            sb.append(piece)
            if (RepetitionGuard.isLooping(sb.toString())) engine.cancel()
        }
        return SummaryText.clean(RepetitionGuard.clean(r.text))
    }
}
