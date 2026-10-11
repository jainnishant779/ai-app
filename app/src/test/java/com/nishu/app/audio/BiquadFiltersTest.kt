package com.nishu.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class BiquadFiltersTest {
    private fun tone(hz: Double, amp: Float, n: Int = 16_000) =
        FloatArray(n) { (amp * sin(2 * PI * hz * it / 16_000)).toFloat() }

    private fun peak(x: FloatArray, from: Int = 4_000) =
        (from until x.size).maxOf { abs(x[it]) }

    @Test
    fun butterworthHighPassRejectsRumbleAndPassesSpeech() {
        val hpf = BiquadFilter.createHighPass(sampleRate = 16_000, cutoffHz = 80f, q = 0.7071f)
        val rumble = tone(20.0, 0.5f)
        val speech = tone(1000.0, 0.5f)

        hpf.process(rumble)
        hpf.reset()
        hpf.process(speech)

        // 20 Hz is two octaves below 80 Hz; -12 dB/octave attenuation drops it by > 20 dB (< 0.08)
        assertTrue("20 Hz rumble should be heavily attenuated, got ${peak(rumble)}", peak(rumble) < 0.08f)
        // 1 kHz speech should pass through at > 0.45 amplitude (input was 0.5)
        assertTrue("1 kHz speech must pass with unity gain, got ${peak(speech)}", peak(speech) > 0.45f)
    }

    @Test
    fun notchFilterRejectsMainsHum() {
        val notch50 = BiquadFilter.createNotch(sampleRate = 16_000, centerHz = 50f, q = 8f)
        val hum = tone(50.0, 0.5f, 24_000)
        val speech = tone(200.0, 0.5f, 16_000)

        notch50.process(hum)
        notch50.reset()
        notch50.process(speech)

        // Settled tail of 50 Hz hum (after transient settling) should be strongly notched out (< 0.05)
        val tailHumPeak = (12_000 until hum.size).maxOf { abs(hum[it]) }
        assertTrue("50 Hz hum should be notched out, got $tailHumPeak", tailHumPeak < 0.05f)
        // 200 Hz voice fundamental must pass through cleanly
        assertTrue("200 Hz speech fundamental must pass cleanly, got ${peak(speech)}", peak(speech) > 0.45f)
    }

    @Test
    fun dcBlockerRemovesDCOffset() {
        val blocker = DcBlocker(sampleRate = 16_000, cutoffHz = 20f)
        // Input with 0.4f DC bias
        val biased = FloatArray(8_000) { 0.4f + (0.1f * sin(2 * PI * 500 * it / 16_000)).toFloat() }

        blocker.process(biased)

        // After initial settling (first 2,000 samples), mean should be near zero (< 0.02)
        val tail = biased.copyOfRange(3_000, 8_000)
        val mean = tail.average()
        assertTrue("DC bias must be removed, got mean $mean", abs(mean) < 0.02)
    }

    @Test
    fun loudnessNormalizerSoftKneeNeverExceedsCeiling() {
        val normalizer = LoudnessNormalizer(gain = 10f) // 10x massive gain
        val loud = tone(400.0, 0.8f) // 0.8 * 10 = 8.0 amplitude!

        normalizer.process(loud)

        // Must never exceed MAX_CEILING (0.98)
        val maxAmp = loud.maxOf { abs(it) }
        assertTrue("Output should be soft-knee limited <= 0.98, got $maxAmp", maxAmp <= 0.9801f)
        // Must maintain high acoustic energy without hard squaring
        assertTrue("Output should remain energetic, got $maxAmp", maxAmp > 0.90f)
    }

    @Test
    fun biquadChunkedProcessingEqualsContinuousProcessing() {
        val filter1 = BiquadFilter.createHighPass(16_000, 80f)
        val filter2 = BiquadFilter.createHighPass(16_000, 80f)

        val full = tone(300.0, 0.4f, 8_000)
        val chunk1 = full.copyOfRange(0, 3_000)
        val chunk2 = full.copyOfRange(3_000, 8_000)

        filter1.process(full)
        filter2.process(chunk1)
        filter2.process(chunk2)

        val joined = chunk1 + chunk2
        for (i in full.indices) {
            assertEquals("Chunked output must match continuous output at index $i", full[i], joined[i], 1e-5f)
        }
    }
}
