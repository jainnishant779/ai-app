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
) {
    fun file(root: File, name: String): File = if (dir.isEmpty()) File(root, name) else File(File(root, dir), name)
    fun encoder(root: File) = file(root, "$prefix-encoder.int8.onnx")
    fun decoder(root: File) = file(root, "$prefix-decoder.int8.onnx")
    fun tokens(root: File) = file(root, "$prefix-tokens.txt")
    fun isInstalled(root: File) = encoder(root).exists() && decoder(root).exists() && tokens(root).exists()

    companion object {
        /** Whisper-base fine-tuned on Hindi/Hinglish speech; writes Roman Hinglish, which is what Nishu was trained on. */
        val HINGLISH_SWIFT = SttModelSpec("hinglish-swift-int8", "hinglish-swift", "hinglish-swift", "en", "Whisper Hinglish (base)")
        val TINY_EN = SttModelSpec("whisper-tiny.en-int8", "", "tiny.en", "en", "whisper-tiny.en")

        /** Best installed model, falling back to tiny.en so transcription never stops working. */
        fun select(root: File): SttModelSpec = listOf(HINGLISH_SWIFT, TINY_EN).firstOrNull { it.isInstalled(root) } ?: TINY_EN
    }
}
