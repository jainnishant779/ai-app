package com.nishu.app.stt

import com.nishu.app.audio.AudioDiagnostics
import com.nishu.app.audio.AudioReport
import com.nishu.app.audio.BiquadFilter
import com.nishu.app.audio.DcBlocker
import com.nishu.app.audio.LoudnessNormalizer
import java.io.File
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Production Audio Preprocessor combining:
 * 1. DC offset removal (20 Hz leaky integrator).
 * 2. 2nd-order Butterworth 80 Hz high-pass filter (-12 dB/octave, cuts handling noise and low rumble).
 * 3. Conditional 50 Hz / 60 Hz electrical hum notch filter (active when mains hum is detected).
 * 4. 2nd-order Butterworth 7.5 kHz low-pass filter (trims Nyquist boundary hiss).
 * 5. ITU-R BS.1770-4 momentary loudness gain leveling (-20 dBFS target).
 * 6. Soft-knee tanh polynomial limiter strictly preventing hard clipping distortion.
 */
class AudioPreprocessor(
    private val gain: Float,
    private val noiseFloorDb: Float = DEFAULT_NOISE_FLOOR_DB,
    sampleRate: Int = 16_000,
    highPassHz: Float = 80f,
    lowPassHz: Float = 7500f,
    hasHum50Hz: Boolean = false,
    hasHum60Hz: Boolean = false,
) {
    private val dcBlocker = DcBlocker(sampleRate, 20f)
    private val hpFilter = BiquadFilter.createHighPass(sampleRate, highPassHz, 0.7071f)
    private val notch50 = if (hasHum50Hz) BiquadFilter.createNotch(sampleRate, 50f, 8f) else null
    private val notch60 = if (hasHum60Hz) BiquadFilter.createNotch(sampleRate, 60f, 8f) else null
    private val lpFilter = BiquadFilter.createLowPass(sampleRate, lowPassHz, 0.7071f)
    private val normalizer = LoudnessNormalizer(gain = gain)

    /**
     * Filters [samples] in place and returns it. Maintains filter states across chunks
     * so streamed buffer processing is click-free and continuous.
     */
    fun process(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples

        // Stage 1: DC offset removal
        dcBlocker.process(samples)

        // Stage 2: 80 Hz Butterworth High-Pass (removes rumble, preserves fundamental pitch)
        hpFilter.process(samples)

        // Stage 3: Conditional hum notch
        notch50?.process(samples)
        notch60?.process(samples)

        // Stage 4: 7.5 kHz Low-Pass
        lpFilter.process(samples)

        // Stage 5: Target gain & soft-knee limiter (capping strictly at <= 0.98 without clipping)
        normalizer.process(samples)

        return samples
    }

    /**
     * Preserves audio intact. Silero VAD provides speech boundaries;
     * internal gating is a no-op to prevent cutting soft consonants.
     */
    fun softGate(samples: FloatArray, sampleRate: Int = 16_000): FloatArray = samples

    fun reset() {
        dcBlocker.reset()
        hpFilter.reset()
        notch50?.reset()
        notch60?.reset()
        lpFilter.reset()
    }

    companion object {
        const val TARGET_SPEECH_DB = -20f
        const val MAX_GAIN_DB = 18f
        const val MIN_GAIN_DB = -6f
        const val DEFAULT_NOISE_FLOOR_DB = -45f
        const val GATE_ATTENUATION_DB = -12f
        private const val FRAME = 400 // 25 ms at 16 kHz

        /** Factory method constructing an optimal preprocessor directly from an [AudioReport]. */
        fun fromReport(
            report: AudioReport,
            sampleRate: Int = 16_000,
            highPassHz: Float = 80f,
            lowPassHz: Float = 7500f,
        ): AudioPreprocessor {
            return AudioPreprocessor(
                gain = report.targetGain,
                noiseFloorDb = report.noiseFloorDb,
                sampleRate = sampleRate,
                highPassHz = highPassHz,
                lowPassHz = lowPassHz,
                hasHum50Hz = report.hasHum50Hz,
                hasHum60Hz = report.hasHum60Hz,
            )
        }

        /** Single-pass diagnostic analysis over the file. */
        fun analyze(wav: File): AudioReport = AudioDiagnostics.analyze(wav)

        /** Gain that brings speech frames to [TARGET_SPEECH_DB], bounded so noise is not blown up. */
        fun gainFor(frameDb: List<Float>): Float {
            if (frameDb.isEmpty()) return 1f
            val active = frameDb.filter { it > -55f }
            val frames = if (active.size >= frameDb.size / 10) active else frameDb
            val sorted = frames.sorted()
            val speechDb = sorted[((sorted.size - 1) * 0.75).toInt()]
            val db = (TARGET_SPEECH_DB - speechDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            return 10f.pow(db / 20f)
        }

        /**
         * Estimates the noise floor from the quietest frames of the recording.
         */
        fun measureNoiseFloor(frameLevels: List<Float>): Float {
            if (frameLevels.isEmpty()) return DEFAULT_NOISE_FLOOR_DB
            val sorted = frameLevels.sorted()
            val noiseMedian = sorted[sorted.size / 10]
            return (noiseMedian + 3f).coerceIn(-55f, -30f)
        }

        /** First pass over the file: per-frame level, then the single gain and noise floor. */
        fun measureGain(wav: File): Float {
            return measure(wav).first
        }

        /** Returns (gain, noiseFloorDb) pair. Uses AudioDiagnostics for fast analysis. */
        fun measure(wav: File): Pair<Float, Float> {
            val report = AudioDiagnostics.analyze(wav)
            return report.targetGain to report.noiseFloorDb
        }

        fun rms(x: FloatArray): Float = sqrt(x.fold(0.0) { a, v -> a + v * v } / x.size.coerceAtLeast(1)).toFloat()
    }
}
