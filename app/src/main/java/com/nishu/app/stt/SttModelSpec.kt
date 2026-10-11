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
    fun encoder(root: File): File {
        if (qwen) return file(root, "encoder.int8.onnx")
        val prefixed = file(root, "$prefix-encoder.int8.onnx")
        return if (prefixed.exists()) prefixed else file(root, "encoder.int8.onnx")
    }
    fun decoder(root: File): File {
        if (qwen) return file(root, "decoder.int8.onnx")
        val prefixed = file(root, "$prefix-decoder.int8.onnx")
        return if (prefixed.exists()) prefixed else file(root, "decoder.int8.onnx")
    }
    fun tokens(root: File): File {
        val prefixed = file(root, "$prefix-tokens.txt")
        return if (prefixed.exists()) prefixed else file(root, "tokens.txt")
    }

    /** Qwen3-ASR only: the audio front end and the tokenizer folder (vocab.json, merges.txt, tokenizer_config.json). */
    fun convFrontend(root: File) = file(root, "conv_frontend.onnx")
    fun tokenizerDir(root: File) = file(root, "tokenizer")

    fun isInstalled(root: File) =
        encoder(root).exists() && decoder(root).exists() &&
            if (qwen) convFrontend(root).exists() && File(tokenizerDir(root), "vocab.json").exists() else tokens(root).exists()

    companion object {
        /** Whisper-small fine-tuned on Hinglish; 244M params, 3x acoustic capacity over base, optimal for product-grade accuracy. */
        val WHISPER_SMALL_HINGLISH = SttModelSpec("whisper-small-hinglish-int8", "whisper-small", "small", "en", "Whisper Small (Hinglish)")
        /** Whisper-small multilingual in English/Hinglish mode; high accuracy for noisy audio, accents and long meetings. */
        val WHISPER_SMALL_EN = SttModelSpec("whisper-small-en-int8", "whisper-small", "small", "en", "Whisper Small (High Accuracy)")
        /** Swift / Whisper-base fine-tuned on Hindi/Hinglish speech; writes Roman Hinglish. */
        val HINGLISH_SWIFT = SttModelSpec("hinglish-swift-int8", "hinglish-swift", "hinglish-swift", "en", "Swift (Hinglish)")
        /** Whisper-base multilingual run in English mode for clean English meetings without Hindi hallucinations. */
        val WHISPER_BASE_EN = SttModelSpec("whisper-base-en-int8", "whisper-base", "base", "en", "Whisper Base (English)")
        /**
         * Qwen3-ASR 0.6B fine-tuned on Hinglish (~1 GB int8). Picks the language itself; writes Latin for English speech and
         * Devanagari for Hindi. Measured only so far, so [select] never chooses it.
         */
        val QWEN3_HINGLISH = SttModelSpec("qwen3-asr-hinglish-int8", "qwen3-hinglish", "", "", "Qwen3-ASR Hinglish", Engine.QWEN3_ASR)
        val TINY_EN = SttModelSpec("whisper-tiny.en-int8", "", "tiny.en", "en", "whisper-tiny.en")

        /** Best installed model, respecting manual override or preferred language mode ("hinglish" or "english"). */
        fun select(root: File, preferredLanguage: String = "hinglish", overrideModelId: String = "auto"): SttModelSpec {
            val all = listOf(QWEN3_HINGLISH, WHISPER_SMALL_EN, WHISPER_SMALL_HINGLISH, HINGLISH_SWIFT, WHISPER_BASE_EN, TINY_EN)
            if (overrideModelId != "auto" && overrideModelId.isNotBlank()) {
                val matched = all.firstOrNull {
                    it.id.equals(overrideModelId, ignoreCase = true) ||
                    it.dir.equals(overrideModelId, ignoreCase = true) ||
                    (overrideModelId.contains("small", ignoreCase = true) && it.dir == "whisper-small") ||
                    (overrideModelId.contains("swift", ignoreCase = true) && it.dir == "hinglish-swift") ||
                    (overrideModelId.contains("qwen", ignoreCase = true) && it.dir == "qwen3-hinglish")
                }
                if (matched != null && matched.isInstalled(root)) return matched
            }
            return if (preferredLanguage.equals("english", ignoreCase = true) || preferredLanguage.equals("en", ignoreCase = true)) {
                listOf(WHISPER_SMALL_EN, WHISPER_BASE_EN, TINY_EN, HINGLISH_SWIFT, QWEN3_HINGLISH, WHISPER_SMALL_HINGLISH).firstOrNull { it.isInstalled(root) } ?: TINY_EN
            } else {
                listOf(HINGLISH_SWIFT, WHISPER_SMALL_EN, QWEN3_HINGLISH, WHISPER_SMALL_HINGLISH, WHISPER_BASE_EN, TINY_EN).firstOrNull { it.isInstalled(root) } ?: TINY_EN
            }
        }
    }
}
