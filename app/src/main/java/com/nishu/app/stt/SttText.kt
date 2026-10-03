package com.nishu.app.stt

import com.nishu.app.summarize.RepetitionGuard
import kotlin.math.min

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
        "^(thank you|thanks|thanks for watching|thank you for watching|please subscribe|subscribe|bye|goodbye|you|okay|so|dh|ve|aam|hara|me)[.,!?:;\\s]*$",
        RegexOption.IGNORE_CASE,
    )
    /**
     * One filler word read out of a long stretch of "speech" is the model guessing on noise: on the phone, 5-12 s
     * segments came back as just "Haan." or "me" where a second model heard nothing. A real "haan" is short, so it
     * is kept when the segment is short.
     */
    private val noiseFiller = setOf("haan", "ha", "han", "me", "mein", "so", "toh", "to", "okay", "ok", "hmm", "hm", "aur", "ki", "ke")
    private val validShortWords = setOf("ok", "no", "hi", "ye", "go", "in", "on", "at", "to", "we", "he", "it", "is", "up", "so", "me", "if", "us", "am", "an", "or", "by", "my", "do")

    /** Spoken email: "abc at the rate xyz dot com" → "abc@xyz.com" */
    private val spokenEmail = Regex(
        """(\w+)\s+at\s+the\s+rate\s+(\w+)\s+dot\s+(com|in|org|net|co|io)\b""",
        RegexOption.IGNORE_CASE,
    )

    // CLI, File extensions, and Commands
    private val chmodRegex = Regex("""(?i)\b(c\s*h\s*mode|ch\s*mode|c\s*h\s*mod|see\s*h\s*mode)\b""")
    private val sudoRegex = Regex("""(?i)\b(s\s*u\s*d\s*o|soo\s*doo)\b""")
    private val gitCommitRegex = Regex("""(?i)\bgit\s+(kamit|kameet)\b""")
    private val gitPushRegex = Regex("""(?i)\bgit\s+(poosh|pus)\b""")
    private val pipInstallRegex = Regex("""(?i)\bp\s*i\s*p\s+install\b""")
    private val npmInstallRegex = Regex("""(?i)\bn\s*p\s*m\s+install\b""")

    // File extensions: e.g. "script dot s h" → "script.sh"
    private val dotExtensionRegexes = listOf(
        Regex("""(?i)\bdot\s+(s\s*h|sh)\b""") to ".sh",
        Regex("""(?i)\bdot\s+(p\s*y|py|pie)\b""") to ".py",
        Regex("""(?i)\bdot\s+(j\s*s|js)\b""") to ".js",
        Regex("""(?i)\bdot\s+(t\s*s|ts)\b""") to ".ts",
        Regex("""(?i)\bdot\s+(c\s*s\s*s|css)\b""") to ".css",
        Regex("""(?i)\bdot\s+(h\s*t\s*m\s*l|html)\b""") to ".html",
        Regex("""(?i)\bdot\s+(j\s*s\s*o\s*n|json)\b""") to ".json",
        Regex("""(?i)\bdot\s+(y\s*a\s*m\s*l|yaml|yml)\b""") to ".yaml",
        Regex("""(?i)\bdot\s+(t\s*x\s*t|txt)\b""") to ".txt",
        Regex("""(?i)\bdot\s+(c\s*s\s*v|csv)\b""") to ".csv",
    )
    private val attachExtensionRegex = Regex("""\s+(\.[a-z]{2,4})\b""")

    private val versionNumbersRegex = Regex("""(?i)\bversion\s+(\d)\s+(\d)\s+(\d)(?:\s+(\d))?\b""")


    private val indianApps = listOf(
        Regex("""(?i)\b(g\s*pay|google\s*pay)\b""") to "GPay",
        Regex("""(?i)\b(pay\s*tm)\b""") to "Paytm",
        Regex("""(?i)\b(u\s*p\s*i)\b""") to "UPI",
    )

    /**
     * @param durationMs how long the VAD said speech lasted; short segments are held to a stricter standard.
     * @param userName optional user's name from settings to resolve phonetic mistranscriptions.
     * @param customVocabulary comma- or newline-separated user terms (e.g. "Docker, Kubernetes, SINQIT (synchik)").
     * @return the cleaned text, [UNCLEAR] for speech that could not be read, or null to drop the segment.
     */
    fun clean(
        raw: String,
        durationMs: Long = Long.MAX_VALUE,
        userName: String = "",
        customVocabulary: String = "",
    ): String? {
        val trimmed = raw.trim()
        val stripped = trimmed.replace(annotation, " ").replace(dangling, " ").replace(Regex("\\s+"), " ").trim()
        val hasWords = stripped.any { it.isLetterOrDigit() }

        if (!hasWords) {
            val readable = unreadableSpeech.containsMatchIn(trimmed)
            return if (readable && durationMs >= UNCLEAR_MIN_MS) UNCLEAR else null
        }
        val collapsed = RepetitionGuard.clean(stripped).ifBlank { stripped }
        val tokens = collapsed.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (durationMs >= LONG_FILLER_MS && tokens.size == 1 && tokens[0] in noiseFiller) return null
        if (durationMs < SHORT_MS) {
            if (silenceHallucination.matches(collapsed)) return null
            val coreLetters = collapsed.filter { it.isLetter() }
            if (coreLetters.length <= 1) return null
            if (coreLetters.length == 2 && coreLetters.lowercase() !in validShortWords) return null
        }
        val withEmails = normalizeEmails(collapsed)
        val withNames = resolveNames(withEmails, userName)
        val withTech = normalizeTechnicalTerms(withNames)
        return resolveCustomVocabulary(withTech, customVocabulary)
    }

    /** Converts spoken email dictation ("abc at the rate xyz dot com") to standard email format. */
    fun normalizeEmails(text: String): String =
        spokenEmail.replace(text) { m ->
            "${m.groupValues[1].lowercase()}@${m.groupValues[2].lowercase()}.${m.groupValues[3].lowercase()}"
        }

    /**
     * Normalizes dictated developer terms, file extensions and payment app names. Only rules that are unambiguous in
     * everyday speech belong here: fixes tuned on one recording or one tutorial ("dash mein" -> "bash mein",
     * "phone pe" -> "PhonePe", "not equal to" -> "!=") break ordinary Hinglish and English sentences.
     */
    fun normalizeTechnicalTerms(text: String): String {
        var res = text
        res = chmodRegex.replace(res, "chmod")
        res = sudoRegex.replace(res, "sudo")
        res = gitCommitRegex.replace(res, "git commit")
        res = gitPushRegex.replace(res, "git push")
        res = pipInstallRegex.replace(res, "pip install")
        res = npmInstallRegex.replace(res, "npm install")
        for ((pattern, ext) in dotExtensionRegexes) {
            res = pattern.replace(res, ext)
        }
        res = attachExtensionRegex.replace(res, "$1")
        // "version 4 1 0" -> "version 4.1.0"
        res = versionNumbersRegex.replace(res) { m ->
            "version ${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}${m.groupValues[4]}"
        }
        for ((pattern, replacement) in indianApps) {
            res = pattern.replace(res, replacement)
        }
        return res.replace(Regex("\\s+"), " ").trim()
    }

    /** Maps Whisper sub-token collapses for Indian names and introductions to the proper name. */
    fun resolveNames(text: String, userName: String = ""): String {
        var result = text
        val cleanUserName = userName.trim()
        // These are how whisper mishears "Nishant Jain"; they must not rename anyone for a user with another name.
        if (cleanUserName.contains("nishant", ignoreCase = true)) {
            val nishantPattern = Regex("(?i)\\b(nisanjian|nishaanjan|neesan jain|nissan jain|nishaan jain|nishaanjay|nisaan jain)\\b")
            result = nishantPattern.replace(result, cleanUserName)
        }

        if (cleanUserName.isNotBlank()) {
            val userTokens = cleanUserName.split(Regex("\\s+")).filter { it.length >= 3 }
            for (token in userTokens) {
                val tokenPattern = Regex("(?i)\\b${Regex.escape(token)}\\b")
                result = tokenPattern.replace(result, token)
            }
        }
        return result
    }

    /**
     * Resolves user-configured custom keywords, project jargon, and phonetic aliases.
     * Supports comma/newline separated entries, and alias syntax like "Target (alias1, alias2)".
     * Safe Levenshtein distance is strictly bounded (same initial letter, maxDist 1 for 5-7 chars, 2 for >=8 chars)
     * to eliminate false positive collisions with common English/Hindi words.
     */
    fun resolveCustomVocabulary(text: String, customVocabulary: String): String {
        if (customVocabulary.isBlank()) return text
        val lines = customVocabulary.split(Regex("[\\r\\n]+")).filter { it.isNotBlank() }
        val entries = mutableListOf<String>()
        val commaSplitNoParens = Regex(""",\s*(?![^()]*\))""")
        for (line in lines) {
            for (item in line.split(commaSplitNoParens)) {
                if (item.isNotBlank()) entries.add(item.trim())
            }
        }
        if (entries.isEmpty()) return text

        var result = text
        for (entry in entries) {
            val aliasMatch = Regex("""^([^(:=]+)(?:[(:=]([^)]+)\)?)?$""").find(entry)
            val target: String
            val aliases: List<String>
            if (aliasMatch != null) {
                target = aliasMatch.groupValues[1].trim()
                val parsedAliases = aliasMatch.groupValues[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                aliases = if (parsedAliases.contains(target)) parsedAliases else parsedAliases + target
            } else {
                target = entry
                aliases = listOf(entry)
            }

            for (alias in aliases) {
                // 1. Exact case-insensitive match -> replace with target's exact casing
                val pattern = Regex("(?i)\\b${Regex.escape(alias)}\\b")
                result = pattern.replace(result, target)

                // 2. Safe Levenshtein fuzzy match:
                // Only if alias is a single word >= 5 letters, starts with same letter,
                // and distance <= 1 (or <= 2 if >= 8 letters). This prevents "doctor" -> "Docker".
                if (alias.length >= 5 && alias.all { it.isLetter() }) {
                    val words = Regex("\\b[A-Za-z]{4,}\\b").findAll(result)
                    for (match in words) {
                        val word = match.value
                        if (word.equals(target, ignoreCase = true)) continue
                        if (word.first().lowercaseChar() != alias.first().lowercaseChar()) continue
                        val maxDist = if (alias.length >= 8) 2 else 1
                        if (levenshtein(word.lowercase(), alias.lowercase()) <= maxDist) {
                            result = result.replace(Regex("\\b${Regex.escape(word)}\\b"), target)
                        }
                    }
                }
            }
        }
        return result
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                dp[j] = if (s1[i - 1] == s2[j - 1]) prev else min(prev, min(dp[j], dp[j - 1])) + 1
                prev = temp
            }
        }
        return dp[s2.length]
    }

    private const val UNCLEAR_MIN_MS = 1_500L
    private const val SHORT_MS = 2_000L
    private const val LONG_FILLER_MS = 2_500L
}
