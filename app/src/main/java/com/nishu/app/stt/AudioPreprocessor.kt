package com.nishu.app.stt

import java.io.File
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Light, cheap clean-up applied before VAD and recognition: an 80 Hz high-pass (removes rumble and handling noise)
 * and one gain for the whole recording so quiet speech reaches the VAD and the model at a normal level.
 * Neither can add information the audio lacks; they only stop quiet recordings from being missed.
 */
class AudioPreprocessor(private val gain: Float, sampleRate: Int = 16_000, cutoffHz: Float = 80f) {
    private val a: Float = run {
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        val dt = 1f / sampleRate
        rc / (rc + dt)
    }
    private var prevX = 0f
    private var prevY = 0f

    /** Filters [samples] in place and returns it. Keeps state across calls so chunk boundaries are seamless. */
    fun process(samples: FloatArray): FloatArray {
        for (i in samples.indices) {
            val x = samples[i]
            val y = a * (prevY + x - prevX)
            prevX = x
            prevY = y
            samples[i] = (y * gain).coerceIn(-0.98f, 0.98f)
        }
        return samples
    }

    companion object {
        const val TARGET_SPEECH_DB = -20f
        const val MAX_GAIN_DB = 18f
        const val MIN_GAIN_DB = -6f
        private const val FRAME = 400 // 25 ms at 16 kHz

        /** Gain that brings the loud (speech) frames to [TARGET_SPEECH_DB], bounded so noise is not blown up. */
        fun gainFor(frameDb: List<Float>): Float {
            if (frameDb.isEmpty()) return 1f
            val sorted = frameDb.sorted()
            val speechDb = sorted[((sorted.size - 1) * 0.9).toInt()]
            val db = (TARGET_SPEECH_DB - speechDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            return 10f.pow(db / 20f)
        }

        /** First pass over the file: per-frame level, then the single gain. */
        fun measureGain(wav: File): Float {
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
            return gainFor(levels)
        }

        fun rms(x: FloatArray): Float = sqrt(x.fold(0.0) { a, v -> a + v * v } / x.size.coerceAtLeast(1)).toFloat()
    }
}
