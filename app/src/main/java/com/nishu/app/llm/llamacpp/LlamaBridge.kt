package com.nishu.app.llm.llamacpp

/** Receives decoded UTF-8 pieces as raw bytes (never NewStringUTF: it corrupts 4-byte characters). */
fun interface PieceSink {
    /** Return false to stop generation. */
    fun onPiece(bytes: ByteArray): Boolean
}

object LlamaBridge {
    const val MODE_CHAT = 0
    const val MODE_GREEDY = 1
    const val MODE_GRAMMAR = 2
    const val MODE_CHAT_WITH_TOOL_SWITCH = 3

    init {
        System.loadLibrary("nishu_llama")
    }

    external fun buildInfo(): String

    /** Returns a native handle, or 0 on failure with the reason in errorOut[0]. */
    external fun load(path: String, nCtx: Int, nThreads: Int, errorOut: Array<String?>): Long
    external fun release(handle: Long)
    external fun cancel(handle: Long)
    external fun contextSize(handle: Long): Int
    external fun tokenize(handle: Long, text: ByteArray): IntArray

    /** 0 on success. Clears the KV cache first. */
    external fun evalPrefix(handle: Long, tokens: IntArray): Int

    /** 0 ok, 1 context full, -1 error. */
    external fun evalTurn(handle: Long, tokens: IntArray): Int

    /** Returns [finishReason, tokens, ttftMicros, totalMicros]. */
    external fun generate(handle: Long, mode: Int, maxTokens: Int, grammar: String?, seed: Int, sink: PieceSink): LongArray

    external fun saveState(handle: Long, path: String, tokens: IntArray): Boolean
    external fun loadState(handle: Long, path: String, expected: IntArray): Boolean
}
