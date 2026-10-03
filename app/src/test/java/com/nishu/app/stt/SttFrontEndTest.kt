package com.nishu.app.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SttFrontEndTest {
    private fun tone(hz: Double, amp: Float, n: Int = 16_000) = FloatArray(n) { (amp * sin(2 * PI * hz * it / 16_000)).toFloat() }
    private fun peak(x: FloatArray, from: Int = 4_000) = (from until x.size).maxOf { abs(x[it]) }

    @Test
    fun highPassRemovesRumbleAndKeepsSpeechBand() {
        val rumble = AudioPreprocessor(1f).process(tone(20.0, 0.5f))
        val speech = AudioPreprocessor(1f).process(tone(1000.0, 0.5f))
        // 20 Hz is well below the 80 Hz cutoff — should be strongly attenuated (< 0.15).
        assertTrue("20 Hz rumble should be strongly attenuated, got ${peak(rumble)}", peak(rumble) < 0.15f)
        // 1 kHz speech passes through cleanly without formant distortion.
        assertTrue("1 kHz speech should pass, got ${peak(speech)}", peak(speech) > 0.45f)
    }

    @Test
    fun gainRaisesQuietSpeechToTargetAndIsBounded() {
        // Frames at -40 dB need +20 dB, which is capped at the +18 dB maximum.
        val quiet = AudioPreprocessor.gainFor(List(100) { -40f })
        assertEquals(Math.pow(10.0, 18.0 / 20), quiet.toDouble(), 0.01)
        // A moderately quiet recording is brought to -20 dB.
        val mid = AudioPreprocessor.gainFor(List(100) { -30f })
        assertEquals(Math.pow(10.0, 10.0 / 20), mid.toDouble(), 0.01)
        // Loud audio is only lowered a little.
        assertEquals(Math.pow(10.0, -6.0 / 20), AudioPreprocessor.gainFor(List(100) { -5f }).toDouble(), 0.01)
        assertEquals(1f, AudioPreprocessor.gainFor(emptyList()), 0f)
    }

    @Test
    fun gainUsesTheLoudFramesNotTheSilence() {
        // 80% silence at -60 dB, 20% speech at -30 dB: the gain must follow the speech.
        val frames = List(80) { -60f } + List(20) { -30f }
        val g = AudioPreprocessor.gainFor(frames)
        assertEquals(Math.pow(10.0, 10.0 / 20), g.toDouble(), 0.01)
    }

    @Test
    fun outputNeverClips() {
        val loud = AudioPreprocessor(8f).process(tone(500.0, 0.9f))
        assertTrue(loud.all { abs(it) <= 0.98f })
    }

    @Test
    fun chunkedProcessingEqualsOneShot() {
        val x = tone(300.0, 0.3f, 4_000)
        val whole = AudioPreprocessor(2f).process(x.copyOf())
        val p = AudioPreprocessor(2f)
        val parts = p.process(x.copyOfRange(0, 1_500)) + p.process(x.copyOfRange(1_500, 4_000))
        assertTrue(whole.indices.all { abs(whole[it] - parts[it]) < 1e-6f })
    }

    @Test
    fun speechTheModelCouldNotReadBecomesAPlaceholderNotSilence() {
        assertEquals(SttText.UNCLEAR, SttText.clean("(speaking in foreign language)", 12_000))
        assertEquals(SttText.UNCLEAR, SttText.clean("[SPEAKING SPANISH]", 3_000))
        assertNull("too short to claim speech", SttText.clean("(speaking in foreign language)", 600))
    }

    @Test
    fun musicAndSilenceAnnotationsAreStillDropped() {
        assertNull(SttText.clean("[Music]", 20_000))
        assertNull(SttText.clean("[BLANK_AUDIO]", 20_000))
        assertNull(SttText.clean("(applause)", 5_000))
        assertNull(SttText.clean("[", 5_000))
    }

    @Test
    fun silenceHallucinationsDropOnlyWhenShort() {
        assertNull(SttText.clean("Thank you.", 900))
        assertEquals("Thank you.", SttText.clean("Thank you.", 6_000))
    }

    @Test
    fun repetitionLoopsAreCollapsed() {
        val loop = "It will be automatic. ".repeat(20)
        val out = SttText.clean(loop, 20_000)!!
        assertTrue("loop not collapsed: ${out.length} chars", out.length < loop.length / 3)
        assertTrue(out.startsWith("It will be automatic"))
    }

    @Test
    fun embeddedAnnotationsAreStrippedButSpeechKept() {
        assertEquals("hello there", SttText.clean("hello [pause] there", 5_000))
        assertEquals("send the deck", SttText.clean("send the deck [", 5_000))
    }

    @Test
    fun nameMistranscriptionsAreCorrected() {
        assertEquals("Hello, I am Nishant Jain", SttText.clean("Hello, I am Nisanjian", 4_000, userName = "Nishant Jain"))
        assertEquals("Hello, I am Nishant Jain", SttText.clean("Hello, I am Nissan Jain", 4_000, userName = "Nishant Jain"))
        // Another user's transcript is never rewritten to someone else's name.
        assertEquals("Hello, I am Nissan Jain", SttText.clean("Hello, I am Nissan Jain", 4_000, userName = "Priya"))
        assertEquals("Hello, I am Nissan Jain", SttText.clean("Hello, I am Nissan Jain", 4_000))
        assertEquals("My name is Rahul Sharma", SttText.clean("My name is rahul sharma", 4_000, userName = "Rahul Sharma"))
    }

    @Test
    fun spokenEmailsAreNormalized() {
        assertEquals("Send to synchik@gmail.com please", SttText.clean("Send to Synchik at the rate Gmail dot com please", 8_000))
        assertEquals("My email is test@yahoo.in", SttText.clean("My email is test at the rate yahoo dot in", 6_000))
        // Should not match partial patterns.
        assertEquals("at the rate of interest", SttText.clean("at the rate of interest", 4_000))
    }

    @Test
    fun noiseFloorMeasurementWorks() {
        // Frames spanning silence and speech: noise floor should be near the quiet median + 3 dB
        val frames = List(90) { -50f } + List(10) { -25f }
        val nf = AudioPreprocessor.measureNoiseFloor(frames)
        assertTrue("noise floor should be around -47 dB, got $nf", nf > -50f && nf < -44f)
    }

    @Test
    fun nullSpeakerPropagation() {
        // Simulate: S1 speaks, then a null segment, then S2 speaks
        val turns = listOf(
            SpeakerTurn(0, 3000, 0),
            SpeakerTurn(5000, 10000, 1),
        )
        val pieces = SpeakerTurns.split(0, 10000, turns, minPieceMs = 1000)
        // The gap between S1 and S2 (3000-5000) should be assigned to the nearest speaker.
        assertTrue("No piece should have null speaker", pieces.all { it.speaker != null })
    }

    @Test
    fun technicalTermsAndCliCommandsAreNormalized() {
        assertEquals("Type chmod +x script.sh", SttText.clean("Type c h mode +x script dot s h", 5_000))
        assertEquals("then git commit and git push", SttText.clean("then git kamit and git poosh", 5_000))
        assertEquals("Upgrade to version 4.1.10", SttText.clean("Upgrade to version 4 1 1 0", 5_000))
        assertEquals("Send via UPI or GPay", SttText.clean("Send via U P I or google pay", 4_000))
    }

    @Test
    fun everydaySpeechIsNotRewrittenAsTechTerms() {
        // Ordinary Hinglish and English that the old tutorial-specific rules used to corrupt.
        assertEquals("Main phone pe baat kar lunga", SttText.clean("Main phone pe baat kar lunga", 4_000))
        assertEquals("Main bahut se bhej deta hoon", SttText.clean("Main bahut se bhej deta hoon", 4_000))
        assertEquals("Dash mein likh do", SttText.clean("Dash mein likh do", 4_000))
        assertEquals("Revenue is not equal to profit", SttText.clean("Revenue is not equal to profit", 4_000))
        assertEquals("Two plus x is ten", SttText.clean("Two plus x is ten", 4_000))
    }

    @Test
    fun aRealShortHaanIsKeptButAFillerReadOutOfNoiseIsDropped() {
        assertEquals("Haan.", SttText.clean("Haan.", 900))
        assertEquals("Haan, theek hai.", SttText.clean("Haan, theek hai.", 6_000))
        assertNull("one filler out of 7 s of 'speech'", SttText.clean("Haan.", 7_000))
        assertNull(SttText.clean("me", 12_000))
        assertNull(SttText.clean("me", 900))
        assertEquals("Hello,", SttText.clean("Hello,", 3_000))
    }

    @Test
    fun customVocabularyAndAliasesAreResolved() {
        val vocab = "SINQIT (synchik, sinqik), Kubernetes, Docker, Supabase"
        val raw1 = "I work at Synchik on a Kubernetis deployment"
        assertEquals("I work at SINQIT on a Kubernetes deployment", SttText.clean(raw1, 5_000, customVocabulary = vocab))

        val raw2 = "push image to doker registry and connect to superbase"
        assertEquals("push image to Docker registry and connect to Supabase", SttText.clean(raw2, 5_000, customVocabulary = vocab))
    }

    @Test
    fun customVocabularyDoesNotProduceFalsePositives() {
        val vocab = "Docker, Kubernetes, Linux"
        // "doctor" must NOT be converted to "Docker"
        val raw = "I am going to consult the doctor today"
        assertEquals("I am going to consult the doctor today", SttText.clean(raw, 5_000, customVocabulary = vocab))

        // "catch up" must NOT be converted to "cache"
        val rawCatch = "I am trying to catch up with the thread"
        assertEquals("I am trying to catch up with the thread", SttText.clean(rawCatch, 5_000, customVocabulary = vocab))
    }

    @Test
    fun explicitUserVocabularyOverridesAmbiguities() {
        val vocab = "DB (DV), cache (catch, cap), pull (fool)"
        val raw = "DV mein change karna tha aur vahi catch par add kiya. Fool karenge ham."
        assertEquals("DB mein change karna tha aur vahi cache par add kiya. pull karenge ham.", SttText.clean(raw, 5_000, customVocabulary = vocab))
    }
}


