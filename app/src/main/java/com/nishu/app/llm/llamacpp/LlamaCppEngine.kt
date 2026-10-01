package com.nishu.app.llm.llamacpp

import com.nishu.app.llm.ChatMessage
import com.nishu.app.llm.FinishReason
import com.nishu.app.llm.GenerationResult
import com.nishu.app.llm.LLMEngine
import com.nishu.app.llm.PromptTemplate
import com.nishu.app.llm.Qwen3PromptTemplate
import com.nishu.app.llm.SamplerProfile
import com.nishu.app.llm.ToolCallParser
import com.nishu.app.llm.WarmResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class LlamaCppEngine private constructor(
    private val handle: Long,
    override val template: PromptTemplate,
    override val contextTokens: Int,
    override val systemPrefixTokens: Int,
    private val prefixTokens: IntArray,
    private val prefixCache: PrefixCache?,
) : LLMEngine {
    override val parser = ToolCallParser()
    private val lock = Mutex()
    private var closed = false

    override suspend fun warmPrefix(): WarmResult = lock.withLock {
        withContext(Dispatchers.Default) {
            val t0 = System.nanoTime()
            val hit = prefixCache?.tryLoad(handle, prefixTokens) == true
            if (!hit) {
                check(LlamaBridge.evalPrefix(handle, prefixTokens) == 0) { "prefix evaluation failed" }
                prefixCache?.store(handle, prefixTokens)
            }
            WarmResult(hit, (System.nanoTime() - t0) / 1_000_000, prefixTokens.size)
        }
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        sampler: SamplerProfile,
        maxTokens: Int,
        onPiece: ((String) -> Unit)?,
    ): GenerationResult {
        val (mode, grammar, seed) = when (sampler) {
            is SamplerProfile.Chat -> Triple(LlamaBridge.MODE_CHAT, null, sampler.seed)
            SamplerProfile.Greedy -> Triple(LlamaBridge.MODE_GREEDY, null, 0)
            is SamplerProfile.Grammar -> Triple(LlamaBridge.MODE_GRAMMAR, sampler.gbnf, 0)
        }
        return run(messages, mode, grammar, seed, maxTokens, onPiece)
    }

    override suspend fun generateWithToolSwitch(
        messages: List<ChatMessage>,
        toolGrammar: String,
        maxTokens: Int,
        onPiece: ((String) -> Unit)?,
    ): GenerationResult = run(messages, LlamaBridge.MODE_CHAT_WITH_TOOL_SWITCH, toolGrammar, -1, maxTokens, onPiece)

    private suspend fun run(
        messages: List<ChatMessage>,
        mode: Int,
        grammar: String?,
        seed: Int,
        maxTokens: Int,
        onPiece: ((String) -> Unit)?,
    ): GenerationResult = lock.withLock {
        withContext(Dispatchers.Default) {
            check(!closed) { "engine closed" }
            val turnTokens = LlamaBridge.tokenize(handle, template.renderTurns(messages))
            when (LlamaBridge.evalTurn(handle, turnTokens)) {
                0 -> Unit
                1 -> return@withContext GenerationResult("", 0, FinishReason.CONTEXT_FULL, 0, 0.0)
                else -> return@withContext GenerationResult("", 0, FinishReason.ERROR, 0, 0.0)
            }
            val text = StringBuilder()
            val stats = LlamaBridge.generate(handle, mode, maxTokens, grammar, seed) { bytes ->
                val piece = String(bytes, Charsets.UTF_8)
                text.append(piece)
                onPiece?.invoke(piece)
                true
            }
            val tokens = stats[1].toInt()
            val decodeMicros = (stats[3] - stats[2]).coerceAtLeast(1)
            GenerationResult(
                text = text.toString(),
                tokens = tokens,
                finishReason = FinishReason.entries[stats[0].toInt().coerceIn(0, FinishReason.entries.lastIndex)],
                ttftMs = stats[2] / 1000,
                decodeTokPerSec = if (tokens > 1) (tokens - 1) * 1_000_000.0 / decodeMicros else 0.0,
            )
        }
    }

    override fun tokenCount(text: String): Int = LlamaBridge.tokenize(handle, text.toByteArray(Charsets.UTF_8)).size

    override fun cancel() {
        if (!closed) LlamaBridge.cancel(handle)
    }

    override fun rssBytes(): Long = ProcessMemory.rssBytes()

    override fun close() {
        if (closed) return
        closed = true
        LlamaBridge.release(handle)
    }

    companion object {
        /** Loads the model and measures the real system-prefix token count. Throws on failure. */
        fun load(
            modelFile: File,
            systemPrompt: ByteArray,
            nCtx: Int = 1024,
            nThreads: Int = 4,
            prefixCache: PrefixCache? = null,
        ): LlamaCppEngine {
            val error = arrayOfNulls<String>(1)
            val handle = LlamaBridge.load(modelFile.absolutePath, nCtx, nThreads, error)
            check(handle != 0L) { "model load failed: ${error[0]}" }
            val template = Qwen3PromptTemplate(String(systemPrompt, Charsets.UTF_8))
            val prefix = LlamaBridge.tokenize(handle, template.renderPrefix())
            return LlamaCppEngine(handle, template, LlamaBridge.contextSize(handle), prefix.size, prefix, prefixCache)
        }
    }
}

object ProcessMemory {
    fun rssBytes(): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.filter { it.isDigit() }?.toLongOrNull()?.times(1024) ?: 0L
        }
    }.getOrDefault(0L)
}
