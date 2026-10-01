package com.nishu.app.llm

enum class FinishReason { STOP, LENGTH, CANCELLED, CONTEXT_FULL, ERROR }

data class GenerationResult(
    val text: String,
    val tokens: Int,
    val finishReason: FinishReason,
    val ttftMs: Long,
    val decodeTokPerSec: Double,
)

sealed interface SamplerProfile {
    /** Qwen3 non-thinking defaults (temp 0.7, top_p 0.8, top_k 20). No repeat penalty on purpose. */
    data class Chat(val seed: Int = -1) : SamplerProfile
    data object Greedy : SamplerProfile
    /** Greedy under a GBNF grammar whose start rule is `root`. */
    data class Grammar(val gbnf: String) : SamplerProfile
}

data class WarmResult(val cacheHit: Boolean, val millis: Long, val prefixTokens: Int)

/**
 * The runtime seam. Every engine renders through the same [PromptTemplate] and must pass the same
 * golden byte/token tests, so a benchmark compares weights and runtimes, not prompt bugs.
 */
interface LLMEngine : AutoCloseable {
    val template: PromptTemplate
    val parser: ToolCallParser
    val contextTokens: Int

    /** Measured with the real tokenizer at load time; never hard-coded. */
    val systemPrefixTokens: Int
    val turnTokenBudget: Int get() = contextTokens - systemPrefixTokens

    suspend fun warmPrefix(): WarmResult

    suspend fun generate(
        messages: List<ChatMessage>,
        sampler: SamplerProfile,
        maxTokens: Int,
        onPiece: ((String) -> Unit)? = null,
    ): GenerationResult

    /** Chat sampling until `<tool_call>` appears, then greedy under [toolGrammar]. */
    suspend fun generateWithToolSwitch(
        messages: List<ChatMessage>,
        toolGrammar: String,
        maxTokens: Int,
        onPiece: ((String) -> Unit)? = null,
    ): GenerationResult

    fun tokenCount(text: String): Int
    fun cancel()
    fun rssBytes(): Long
}
