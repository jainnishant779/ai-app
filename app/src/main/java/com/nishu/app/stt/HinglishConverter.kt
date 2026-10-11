package com.nishu.app.stt

/**
 * Converts Devanagari Hindi text to natural conversational Hinglish (Roman English script).
 * Users chatting and taking notes prefer readable Hinglish (e.g. "to usse koi dikkat hogi to")
 * rather than mixed Devanagari script ("तो उससे कोई दिक्कत होगी तो").
 */
object HinglishConverter {

    private val devanagariRegex = Regex("""[\u0900-\u097F]""")

    fun hasDevanagari(text: String): Boolean = devanagariRegex.containsMatchIn(text)

    // Common Hindi words mapped to natural conversational Roman Hinglish
    private val commonWords = mapOf(
        "तो" to "to",
        "उससे" to "usse",
        "कोई" to "koi",
        "दिक्कत" to "dikkat",
        "होगी" to "hogi",
        "होगा" to "hoga",
        "होगी" to "hogi",
        "तू" to "tu",
        "गैंड" to "gaind",
        "ओएसआर" to "OSR",
        "कितना" to "kitna",
        "कितनी" to "kitni",
        "कितने" to "kitne",
        "पीकलोड" to "peakload",
        "ले" to "le",
        "रही" to "rahi",
        "रहा" to "raha",
        "रहे" to "rahe",
        "है" to "hai",
        "हैं" to "hain",
        "ये" to "ye",
        "वो" to "wo",
        "वह" to "woh",
        "यह" to "yeh",
        "तुम" to "tum",
        "चेक" to "check",
        "कर" to "kar",
        "करो" to "karo",
        "लोगे" to "loge",
        "लूँगा" to "loonga",
        "लूंगा" to "loonga",
        "अभी" to "abhi",
        "ना" to "na",
        "नहीं" to "nahin",
        "क्या" to "kya",
        "क्यों" to "kyun",
        "कैसे" to "kaise",
        "कब" to "kab",
        "कहाँ" to "kahan",
        "कहा" to "kaha",
        "कहा था" to "kaha tha",
        "मैं" to "main",
        "हम" to "hum",
        "आप" to "aap",
        "और" to "aur",
        "था" to "tha",
        "थी" to "thi",
        "थे" to "the",
        "दोस्तों" to "doston",
        "दोस्त" to "dost",
        "के" to "ke",
        "में" to "mein",
        "का" to "ka",
        "की" to "ki",
        "को" to "ko",
        "से" to "se",
        "पर" to "par",
        "स्वागत" to "swagat",
        "बारे" to "baare",
        "सकते" to "sakte",
        "सकता" to "sakta",
        "सकती" to "sakti",
        "अच्छा" to "achha",
        "अच्छी" to "achhi",
        "अच्छे" to "achhe",
        "बात" to "baat",
        "बोल" to "bol",
        "बोलो" to "bolo",
        "बताओ" to "batao",
        "बता" to "bata",
        "लोड" to "load",
        "कॉल" to "call",
        "टाइम" to "time",
        "फोन" to "phone",
        "काम" to "kaam",
        "करना" to "karna",
        "जाना" to "jaana",
        "जा" to "ja",
        "रहा है" to "raha hai",
        "रही है" to "rahi hai",
        "रहे हैं" to "rahe hain",
        "आ" to "aa",
        "आया" to "aaya",
        "आए" to "aaye",
        "गया" to "gaya",
        "गए" to "gaye",
        "गई" to "gayi",
        "हो" to "ho",
        "हुई" to "hui",
        "हुआ" to "hua",
        "हुए" to "hue",
        "एक" to "ek",
        "दो" to "do",
        "तीन" to "teen",
        "चार" to "char",
        "पाँच" to "paanch",
        "पांच" to "paanch",
        "नमस्ते" to "namaste",
        "धन्यवाद" to "dhanyawad",
        "शुक्रिया" to "shukriya",
    )

    private val vowels = mapOf(
        '\u0905' to "a", '\u0906' to "aa", '\u0907' to "i", '\u0908' to "i",
        '\u0909' to "u", '\u090A' to "u", '\u090B' to "ri",
        '\u090F' to "e", '\u0910' to "ai", '\u0913' to "o", '\u0914' to "au",
    )

    private val matras = mapOf(
        '\u093E' to "a", '\u093F' to "i", '\u0940' to "i", '\u0941' to "u",
        '\u0942' to "u", '\u0943' to "ri", '\u0947' to "e", '\u0948' to "ai",
        '\u094B' to "o", '\u094C' to "au",
    )

    private val consonants = mapOf(
        '\u0915' to "k", '\u0916' to "kh", '\u0917' to "g", '\u0918' to "gh", '\u0919' to "ng",
        '\u091A' to "ch", '\u091B' to "chh", '\u091C' to "j", '\u091D' to "jh", '\u091E' to "ny",
        '\u091F' to "t", '\u0920' to "th", '\u0921' to "d", '\u0922' to "dh", '\u0923' to "n",
        '\u0924' to "t", '\u0925' to "th", '\u0926' to "d", '\u0927' to "dh", '\u0928' to "n",
        '\u092A' to "p", '\u092B' to "ph", '\u092C' to "b", '\u092D' to "bh", '\u092E' to "m",
        '\u092F' to "y", '\u0930' to "r", '\u0932' to "l", '\u0933' to "l", '\u0935' to "v",
        '\u0936' to "sh", '\u0937' to "sh", '\u0938' to "s", '\u0939' to "h",
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "gh", '\u095B' to "z",
        '\u095C' to "d", '\u095D' to "dh", '\u095E' to "f", '\u095F' to "y",
    )

    private val tokenRegex = Regex("""[\u0900-\u097F]+|[^\u0900-\u097F]+""")

    /**
     * Converts any Devanagari text in [text] to natural Hinglish.
     * English words, numbers, and symbols are left untouched.
     */
    fun toHinglish(text: String): String {
        if (!hasDevanagari(text)) return text

        val tokens = tokenRegex.findAll(text).map { it.value }
        val sb = StringBuilder(text.length)

        for (token in tokens) {
            if (!hasDevanagari(token)) {
                sb.append(token)
                continue
            }

            val mapped = commonWords[token]
            if (mapped != null) {
                sb.append(mapped)
                continue
            }

            // Phonetic rule conversion with schwa deletion
            val out = StringBuilder()
            var i = 0
            val n = token.length
            while (i < n) {
                val ch = token[i]
                when {
                    ch in vowels -> {
                        out.append(vowels[ch])
                        i++
                    }
                    ch in consonants -> {
                        val cons = consonants[ch] ?: ""
                        if (i + 1 < n && token[i + 1] == '\u094D') { // Halant / Virama
                            out.append(cons)
                            i += 2
                        } else if (i + 1 < n && token[i + 1] in matras) { // Matra
                            out.append(cons).append(matras[token[i + 1]])
                            i += 2
                        } else { // Inherent vowel with schwa deletion
                            if (i + 1 < n && (token[i + 1] in consonants || token[i + 1] in vowels)) {
                                if (i + 2 < n && token[i + 2] in matras) {
                                    out.append(cons) // e.g. kit-na, log-e
                                } else {
                                    out.append(cons).append('a')
                                }
                            } else {
                                out.append(cons)
                            }
                            i++
                        }
                    }
                    ch == '\u0901' || ch == '\u0902' -> { // Chandrabindu / Anusvara
                        out.append('n')
                        i++
                    }
                    ch == '\u0903' -> { // Visarga
                        out.append('h')
                        i++
                    }
                    else -> {
                        i++
                    }
                }
            }
            sb.append(out.toString())
        }
        return sb.toString()
    }
}
