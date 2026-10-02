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
        assertTrue("20 Hz rumble should be strongly attenuated, got ${peak(rumble)}", peak(rumble) < 0.2f)
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
        assertEquals("Hello, I am Nishant Jain", SttText.clean("Hello, I am Nisanjian", 4_000))
        assertEquals("Hello, I am Nishant Jain", SttText.clean("Hello, I am Nissan Jain", 4_000))
        assertEquals("My name is Rahul Sharma", SttText.clean("My name is rahul sharma", 4_000, userName = "Rahul Sharma"))
    }
}
