package com.nishu.app.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class SpeakerVoiceMatcherTest {

    @Test
    fun testLabelNormalization() {
        assertEquals("S1", SpeakerVoiceMatcher.normalizeLabel("Speaker 1"))
        assertEquals("S2", SpeakerVoiceMatcher.normalizeLabel("Speaker 2"))
        assertEquals("S1", SpeakerVoiceMatcher.normalizeLabel("S1"))
        assertEquals("S3", SpeakerVoiceMatcher.normalizeLabel("S3"))
        assertEquals("S5", SpeakerVoiceMatcher.normalizeLabel("Speaker 5"))
    }

    @Test
    fun testCosineSimilarityIdentical() {
        val v1 = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f)
        val v2 = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f)
        val sim = SpeakerVoiceMatcher.cosineSimilarity(v1, v2)
        assertEquals(1.0f, sim, 1e-4f)
    }

    @Test
    fun testCosineSimilarityOrthogonal() {
        val v1 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val v2 = floatArrayOf(0.0f, 1.0f, 0.0f)
        val sim = SpeakerVoiceMatcher.cosineSimilarity(v1, v2)
        assertEquals(0.0f, sim, 1e-4f)
    }

    @Test
    fun testCosineSimilarityOpposite() {
        val v1 = floatArrayOf(1.0f, 2.0f, 3.0f)
        val v2 = floatArrayOf(-1.0f, -2.0f, -3.0f)
        val sim = SpeakerVoiceMatcher.cosineSimilarity(v1, v2)
        assertEquals(-1.0f, sim, 1e-4f)
    }

    @Test
    fun testCosineSimilarityEmptyOrMismatched() {
        assertEquals(0f, SpeakerVoiceMatcher.cosineSimilarity(floatArrayOf(), floatArrayOf()), 1e-4f)
        assertEquals(0f, SpeakerVoiceMatcher.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f)), 1e-4f)
    }

    @Test
    fun testL2Normalization() {
        val raw = floatArrayOf(3.0f, 4.0f) // norm = 5.0
        val norm = SpeakerVoiceMatcher.l2Normalize(raw)
        assertEquals(0.6f, norm[0], 1e-4f)
        assertEquals(0.8f, norm[1], 1e-4f)

        val len = sqrt(norm[0] * norm[0] + norm[1] * norm[1])
        assertEquals(1.0f, len, 1e-4f)
    }

    @Test
    fun testSelectBestSpeechSamples() {
        // Create 20 seconds of audio: 10s silent, 10s high energy
        val sampleRate = 16_000
        val totalSamples = 20 * sampleRate
        val samples = FloatArray(totalSamples)
        for (i in (10 * sampleRate) until totalSamples) {
            samples[i] = 0.5f * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / sampleRate).toFloat()
        }

        val best = SpeakerVoiceMatcher.selectBestSpeechSamples(samples, maxDurationSec = 5)
        // Ensure selected audio contains speech energy
        val energy = best.sumOf { (it * it).toDouble() }
        assertTrue("Selected samples should have substantial energy", energy > 100.0)
    }
}
