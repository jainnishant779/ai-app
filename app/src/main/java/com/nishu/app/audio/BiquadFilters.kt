package com.nishu.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 2nd-order Direct Form II Transposed IIR Biquad Filter.
 *
 * Implements:
 *   y[n] = b0 * x[n] + s1[n-1]
 *   s1[n] = b1 * x[n] - a1 * y[n] + s2[n-1]
 *   s2[n] = b2 * x[n] - a2 * y[n]
 *
 * Direct Form II Transposed is optimal for 32-bit floating point arithmetic on ARM,
 * minimizing round-off noise and maintaining filter state seamlessly across chunk boundaries.
 */
class BiquadFilter(
    val b0: Float,
    val b1: Float,
    val b2: Float,
    val a1: Float,
    val a2: Float,
) {
    private var s1 = 0f
    private var s2 = 0f

    /**
     * Filters a single sample, updating internal state.
     */
    fun process(x: Float): Float {
        val y = b0 * x + s1
        s1 = b1 * x - a1 * y + s2
        s2 = b2 * x - a2 * y
        return y
    }

    /**
     * Filters an array of samples in-place.
     */
    fun process(samples: FloatArray): FloatArray {
        for (i in samples.indices) {
            val x = samples[i]
            val y = b0 * x + s1
            s1 = b1 * x - a1 * y + s2
            s2 = b2 * x - a2 * y
            samples[i] = y
        }
        return samples
    }

    /**
     * Resets the internal state registers to zero.
     */
    fun reset() {
        s1 = 0f
        s2 = 0f
    }

    companion object {
        /**
         * 2nd-order Butterworth High-Pass Filter with maximally flat passband (Q = 0.7071)
         * and -12 dB/octave attenuation slope below cutoff.
         */
        fun createHighPass(
            sampleRate: Int = 16_000,
            cutoffHz: Float = 80f,
            q: Float = 0.7071f,
        ): BiquadFilter {
            val omega = (2.0 * PI * cutoffHz / sampleRate).toFloat()
            val cosOmega = cos(omega.toDouble()).toFloat()
            val sinOmega = sin(omega.toDouble()).toFloat()
            val alpha = sinOmega / (2f * q)

            val b0 = (1f + cosOmega) / 2f
            val b1 = -(1f + cosOmega)
            val b2 = (1f + cosOmega) / 2f
            val a0 = 1f + alpha
            val a1 = -2f * cosOmega
            val a2 = 1f - alpha

            return BiquadFilter(
                b0 = b0 / a0,
                b1 = b1 / a0,
                b2 = b2 / a0,
                a1 = a1 / a0,
                a2 = a2 / a0,
            )
        }

        /**
         * 2nd-order IIR Narrowband Notch Filter.
         * Attenuates mains electrical hum (50 Hz or 60 Hz) by >30 dB over a sharp bandwidth.
         */
        fun createNotch(
            sampleRate: Int = 16_000,
            centerHz: Float = 50f,
            q: Float = 8f,
        ): BiquadFilter {
            val omega = (2.0 * PI * centerHz / sampleRate).toFloat()
            val cosOmega = cos(omega.toDouble()).toFloat()
            val sinOmega = sin(omega.toDouble()).toFloat()
            val alpha = sinOmega / (2f * q)

            val b0 = 1f
            val b1 = -2f * cosOmega
            val b2 = 1f
            val a0 = 1f + alpha
            val a1 = -2f * cosOmega
            val a2 = 1f - alpha

            return BiquadFilter(
                b0 = b0 / a0,
                b1 = b1 / a0,
                b2 = b2 / a0,
                a1 = a1 / a0,
                a2 = a2 / a0,
            )
        }

        /**
         * 2nd-order Butterworth Low-Pass Filter.
         */
        fun createLowPass(
            sampleRate: Int = 16_000,
            cutoffHz: Float = 7500f,
            q: Float = 0.7071f,
        ): BiquadFilter {
            val omega = (2.0 * PI * cutoffHz / sampleRate).toFloat()
            val cosOmega = cos(omega.toDouble()).toFloat()
            val sinOmega = sin(omega.toDouble()).toFloat()
            val alpha = sinOmega / (2f * q)

            val b0 = (1f - cosOmega) / 2f
            val b1 = 1f - cosOmega
            val b2 = (1f - cosOmega) / 2f
            val a0 = 1f + alpha
            val a1 = -2f * cosOmega
            val a2 = 1f - alpha

            return BiquadFilter(
                b0 = b0 / a0,
                b1 = b1 / a0,
                b2 = b2 / a0,
                a1 = a1 / a0,
                a2 = a2 / a0,
            )
        }
    }
}

/**
 * 1st-order IIR Leaky Integrator DC Blocker:
 *   y[n] = x[n] - x[n-1] + R * y[n-1]
 */
class DcBlocker(
    sampleRate: Int = 16_000,
    cutoffHz: Float = 20f,
) {
    private val r = (1.0 - (2.0 * PI * cutoffHz / sampleRate)).toFloat().coerceIn(0.90f, 0.999f)
    private var prevX = 0f
    private var prevY = 0f

    fun process(samples: FloatArray): FloatArray {
        for (i in samples.indices) {
            val x = samples[i]
            val y = x - prevX + r * prevY
            prevX = x
            prevY = y
            samples[i] = y
        }
        return samples
    }

    fun reset() {
        prevX = 0f
        prevY = 0f
    }
}

/**
 * Cascades multiple filters in series while maintaining independent state registers.
 */
class BiquadCascade(private val filters: List<BiquadFilter>) {
    fun process(samples: FloatArray): FloatArray {
        for (filter in filters) {
            filter.process(samples)
        }
        return samples
    }

    fun reset() {
        for (filter in filters) {
            filter.reset()
        }
    }
}
