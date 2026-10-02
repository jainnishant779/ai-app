package com.nishu.app.stt

import java.io.File

/**
 * Where a whisper model lives and how to call it. Swapping the STT model is a config change, not a code change.
 * Layout under `files/models/stt/`: shared `silero_vad.onnx`, and each model in its own folder
 * (`""` for the legacy flat layout used by whisper-tiny.en).
 */
data class SttModelSpec(
    val id: String,
    val dir: String,
    val prefix: String,
    val language: String,
    val label: String,
    val engine: Engine = Engine.WHISPER,
) {
    enum class Engine { WHISPER, QWEN3_ASR }

    private val qwen get() = engine == Engine.QWEN3_ASR

    fun file(root: File, name: String): File = if (dir.isEmpty()) File(root, name) else File(File(root, dir), name)
    fun encoder(root: File) = file(root, if (qwen) "encoder.int8.onnx" else "$prefix-encoder.int8.onnx")
    fun decoder(root: File) = file(root, if (qwen) "decoder.int8.onnx" else "$prefix-decoder.int8.onnx")
    fun tokens(root: File) = file(root, "$prefix-tokens.txt")

    /** Qwen3-ASR only: the audio front end and the tokenizer folder (vocab.json, merges.txt, tokenizer_config.json). */
    fun convFrontend(root: File) = file(root, "conv_frontend.onnx")
    fun tokenizerDir(root: File) = file(root, "tokenizer")

    fun isInstalled(root: File) =
        encoder(root).exists() && decoder(root).exists() &&
            if (qwen) convFrontend(root).exists() && File(tokenizerDir(root), "vocab.json").exists() else tokens(root).exists()

    companion object {
        /** Whisper-base fine-tuned on Hindi/Hinglish speech; writes Roman Hinglish, which is what Nishu was trained on. */
        val HINGLISH_SWIFT = SttModelSpec("hinglish-swift-int8", "hinglish-swift", "hinglish-swift", "en", "Whisper Hinglish (base)")
        /** Whisper-base multilingual run in English mode for clean English meetings without Hindi hallucinations. */
        val WHISPER_BASE_EN = SttModelSpec("whisper-base-en-int8", "whisper-base", "base", "en", "Whisper Base (English)")
        /**
         * Qwen3-ASR 0.6B fine-tuned on Hinglish (~1 GB int8). Picks the language itself; writes Latin for English speech and
         * Devanagari for Hindi. Measured only so far, so [select] never chooses it.
         */
        val QWEN3_HINGLISH = SttModelSpec("qwen3-asr-hinglish-int8", "qwen3-hinglish", "", "", "Qwen3-ASR Hinglish", Engine.QWEN3_ASR)
        val TINY_EN = SttModelSpec("whisper-tiny.en-int8", "", "tiny.en", "en", "whisper-tiny.en")

        /** Best installed model, respecting preferred language mode ("hinglish" or "english"). */
        fun select(root: File, preferredLanguage: String = "hinglish"): SttModelSpec {
            return if (preferredLanguage.equals("english", ignoreCase = true) || preferredLanguage.equals("en", ignoreCase = true)) {
                listOf(WHISPER_BASE_EN, TINY_EN, HINGLISH_SWIFT).firstOrNull { it.isInstalled(root) } ?: TINY_EN
            } else {
                listOf(HINGLISH_SWIFT, WHISPER_BASE_EN, TINY_EN).firstOrNull { it.isInstalled(root) } ?: TINY_EN
            }
        }
    }
}
