package com.nishu.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioDiagnosticsTest {
    private fun tone(hz: Double, amp: Float, n: Int = 16_000) =
        FloatArray(n) { (amp * sin(2 * PI * hz * it / 16_000)).toFloat() }

    @Test
    fun detectsDCOffsetAccurately() {
        val n = 16_000
        val targetDc = 0.28f
        val biased = FloatArray(n) { (targetDc + 0.1f * sin(2 * PI * 400 * it / 16_000)).toFloat() }

        val report = AudioDiagnostics.analyze(biased)
        assertEquals(targetDc, report.dcOffset, 0.01f)
    }

    @Test
    fun detects50HzMainsHumWhenPresent() {
        val n = 16_000
        // Speech (400 Hz) + loud 50 Hz electrical hum
        val audioWithHum = FloatArray(n) {
            (0.3f * sin(2 * PI * 400 * it / 16_000) + 0.25f * sin(2 * PI * 50 * it / 16_000)).toFloat()
        }

        val report = AudioDiagnostics.analyze(audioWithHum)
        assertTrue("Should detect 50 Hz mains hum", report.hasHum50Hz)
        assertTrue("Hum ratio should exceed threshold (4.0), got ${report.hum50HzRatio}", report.hum50HzRatio >= 4.0f)
        assertFalse("Should not detect 60 Hz hum", report.hasHum60Hz)
    }

    @Test
    fun doesNotDetectFalseHumOnCleanVoiceHarmonics() {
        val n = 16_000
        // Realistic speech segment: 200ms pause/silence, followed by active speech
        val voice = FloatArray(n) { i ->
            if (i < 3_200) {
                0f
            } else {
                (0.3f * sin(2 * PI * 250 * i / 16_000) +
                 0.2f * sin(2 * PI * 800 * i / 16_000) +
                 0.1f * sin(2 * PI * 2400 * i / 16_000)).toFloat()
            }
        }

        val report = AudioDiagnostics.analyze(voice)
        assertFalse("Clean voice should not trigger 50 Hz hum", report.hasHum50Hz)
        assertFalse("Clean voice should not trigger 60 Hz hum", report.hasHum60Hz)
        assertTrue("Clean voice should have healthy SNR, got ${report.snrDb}", report.snrDb >= 15f)
    }

    @Test
    fun detectsClippingPercentage() {
        val n = 10_000
        // 500 clipped samples (5%)
        val clipped = FloatArray(n) { i ->
            if (i < 500) 0.99f else (0.2f * sin(2 * PI * 500 * i / 16_000)).toFloat()
        }

        val report = AudioDiagnostics.analyze(clipped)
        assertEquals(5.0f, report.clippingPercent, 0.1f)
    }

    @Test
    fun calculatesTargetGainBounded() {
        val n = 16_000
        // Quiet signal (~ -40 dBFS)
        val quiet = tone(400.0, 0.01f, n)
        val report = AudioDiagnostics.analyze(quiet)

        // Target gain must be bounded between MIN_GAIN_DB and MAX_GAIN_DB
        assertTrue("Target gain should be positive, got ${report.targetGain}", report.targetGain > 1.0f)
        assertTrue("Target gain should be bounded <= 10^(15/20) ~ 5.62", report.targetGain <= 5.63f)
    }
}
