package com.nishu.app.stt

/** Whisper emits non-speech annotations such as "[BLANK_AUDIO]", "(music)" or a dangling "[". They are not transcript. */
object SttText {
    private val closed = Regex("\\[[^\\]]*]|\\([^)]*\\)|\\*[^*]*\\*")
    private val dangling = Regex("[\\[(*][^\\])*]*$")

    /** Returns the cleaned text, or null when nothing speech-like is left. */
    fun clean(raw: String): String? {
        val text = raw.replace(closed, " ").replace(dangling, " ").replace(Regex("\\s+"), " ").trim()
        return text.takeIf { t -> t.any { it.isLetterOrDigit() } }
    }
}
