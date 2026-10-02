package com.nishu.app.stt

import com.nishu.app.summarize.RepetitionGuard

/**
 * Cleans one recognized segment. Whisper emits non-speech annotations ("[Music]", "(speaking in foreign language)"),
 * repeats itself when unsure, and invents "Thank you." on near-silence. The one thing this must never do is turn
 * real speech into "nothing": a segment the VAD called speech but the model could not read becomes a placeholder.
 */
object SttText {
    const val UNCLEAR = "[unclear speech]"

    private val annotation = Regex("\\[[^\\]]*]|\\([^)]*\\)|\\*[^*]*\\*")
    private val dangling = Regex("[\\[(*][^\\])*]*$")
    /** Annotations that mean "someone is talking but I could not read it" (as opposed to music or silence). */
    private val unreadableSpeech = Regex("speak|foreign|language|unclear|inaudible|mumbl|crosstalk|indistinct|speech", RegexOption.IGNORE_CASE)
    private val silenceHallucination = Regex(
        "^(thank you|thanks|thanks for watching|thank you for watching|please subscribe|subscribe|bye|goodbye|you|okay|so|dh|ve|aam|haan|haan haan|hara|toh)[.,!?:;\\s]*$",
        RegexOption.IGNORE_CASE,
    )
    private val validShortWords = setOf("ok", "no", "hi", "ye", "go", "in", "on", "at", "to", "we", "he", "it", "is", "up", "so", "me", "if", "us", "am", "an", "or", "by", "my", "do")

    /**
     * @param durationMs how long the VAD said speech lasted; short segments are held to a stricter standard.
     * @param userName optional user's name from settings to resolve phonetic mistranscriptions.
     * @return the cleaned text, [UNCLEAR] for speech that could not be read, or null to drop the segment.
     */
    fun clean(raw: String, durationMs: Long = Long.MAX_VALUE, userName: String = ""): String? {
        val trimmed = raw.trim()
        val stripped = trimmed.replace(annotation, " ").replace(dangling, " ").replace(Regex("\\s+"), " ").trim()
        val hasWords = stripped.any { it.isLetterOrDigit() }

        if (!hasWords) {
            val readable = unreadableSpeech.containsMatchIn(trimmed)
            return if (readable && durationMs >= UNCLEAR_MIN_MS) UNCLEAR else null
        }
        val collapsed = RepetitionGuard.clean(stripped).ifBlank { stripped }
        if (durationMs < SHORT_MS) {
            if (silenceHallucination.matches(collapsed)) return null
            val coreLetters = collapsed.filter { it.isLetter() }
            if (coreLetters.length <= 1) return null
            if (coreLetters.length == 2 && coreLetters.lowercase() !in validShortWords) return null
        }
        return resolveNames(collapsed, userName)
    }

    /** Maps Whisper sub-token collapses for Indian names and introductions to the proper name. */
    fun resolveNames(text: String, userName: String = ""): String {
        var result = text
        val targetName = userName.trim().ifEmpty { "Nishant Jain" }
        val nishantPattern = Regex("(?i)\\b(nisanjian|nishaanjan|neesan jain|nissan jain|nishaan jain|nishaanjay|nisaan jain)\\b")
        result = nishantPattern.replace(result, targetName)

        val cleanUserName = userName.trim()
        if (cleanUserName.isNotBlank()) {
            val userTokens = cleanUserName.split(Regex("\\s+")).filter { it.length >= 3 }
            for (token in userTokens) {
                val tokenPattern = Regex("(?i)\\b$token\\b")
                result = tokenPattern.replace(result, token)
            }
        }
        return result
    }

    private const val UNCLEAR_MIN_MS = 1_500L
    private const val SHORT_MS = 2_000L
}
