package com.nishu.app.stt

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Light clean-up applied before VAD and recognition, in this order:
 *
 * 1. **80 Hz high-pass** (one-pole IIR): cuts rumble, AC hum and handling noise.
 * 2. **7.5 kHz low-pass** (one-pole IIR): trims hiss near the 8 kHz Nyquist edge.
 * 3. **One gain for the whole recording**: brings speech frames to about -20 dBFS, at most +18 dB, never clipping.
 *
 * Pre-emphasis and an in-speech noise gate were tried and removed (they cut soft consonants). Measured with
 * tools/stt_ablation.py: these filters change the transcript very little; the recognition model is what matters.
 * [measureNoiseFloor] is computed but not used for gating.
 */
class AudioPreprocessor(
    private val gain: Float,
    private val noiseFloorDb: Float = DEFAULT_NOISE_FLOOR_DB,
    sampleRate: Int = 16_000,
    highPassHz: Float = 80f,
    lowPassHz: Float = 7500f,
) {
    // --- High-pass (1st-order IIR, cuts AC hum and mic handling rumble without hurting speech fundamentals) ---
    private val aHP: Float = run {
        val rc = 1f / (2f * PI.toFloat() * highPassHz)
        val dt = 1f / sampleRate
        rc / (rc + dt)
    }
    private var hpPrevX = 0f
    private var hpPrevY = 0f

    // --- Low-pass (1st-order IIR, removes Nyquist boundary hiss) ---
    private val aLP: Float = run {
        val rc = 1f / (2f * PI.toFloat() * lowPassHz)
        val dt = 1f / sampleRate
        dt / (rc + dt)
    }
    private var lpPrevY = 0f

    /** Filters [samples] in place and returns it. Keeps state across calls so chunk boundaries are seamless. */
    fun process(samples: FloatArray): FloatArray {
        for (i in samples.indices) {
            // Stage 1: High-pass filter (80 Hz rumble cut)
            val x = samples[i]
            val hp = aHP * (hpPrevY + x - hpPrevX)
            hpPrevX = x
            hpPrevY = hp

            // Stage 2: Low-pass filter (7.5 kHz hiss cut)
            val lp = lpPrevY + aLP * (hp - lpPrevY)
            lpPrevY = lp

            // Stage 3: Gain + clipping
            // Preserves natural speech formant spectrum required by Whisper
            samples[i] = (lp * gain).coerceIn(-0.98f, 0.98f)
        }
        return samples
    }

    /**
     * Preserves audio intact. Silero VAD already provides clean speech boundaries;
     * internal gating is a no-op to prevent cutting soft consonants and quiet word endings.
     */
    fun softGate(samples: FloatArray, sampleRate: Int = 16_000): FloatArray = samples

    companion object {
        const val TARGET_SPEECH_DB = -20f
        const val MAX_GAIN_DB = 18f
        const val MIN_GAIN_DB = -6f
        const val DEFAULT_NOISE_FLOOR_DB = -45f
        const val GATE_ATTENUATION_DB = -12f
        private const val FRAME = 400 // 25 ms at 16 kHz

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
         * Used to configure the soft noise gate threshold.
         */
        fun measureNoiseFloor(frameLevels: List<Float>): Float {
            if (frameLevels.isEmpty()) return DEFAULT_NOISE_FLOOR_DB
            val sorted = frameLevels.sorted()
            // Noise floor = median of quietest 10% of frames, plus 3 dB margin
            val noiseMedian = sorted[sorted.size / 10]
            return (noiseMedian + 3f).coerceIn(-55f, -30f)
        }

        /** First pass over the file: per-frame level, then the single gain and noise floor. */
        fun measureGain(wav: File): Float {
            return measure(wav).first
        }

        /** Returns (gain, noiseFloorDb) pair. */
        fun measure(wav: File): Pair<Float, Float> {
            val levels = ArrayList<Float>()
            WavReader(wav).use { r ->
                while (true) {
                    val s = r.read(FRAME * 40)
                    if (s.isEmpty()) break
                    var i = 0
                    while (i + FRAME <= s.size) {
                        var sum = 0.0
                        for (j in i until i + FRAME) sum += s[j].toDouble() * s[j]
                        levels += (10 * log10(sum / FRAME + 1e-12)).toFloat()
                        i += FRAME
                    }
                }
            }
            return gainFor(levels) to measureNoiseFloor(levels)
        }

        fun rms(x: FloatArray): Float = sqrt(x.fold(0.0) { a, v -> a + v * v } / x.size.coerceAtLeast(1)).toFloat()
    }
}

