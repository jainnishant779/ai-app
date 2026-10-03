package com.nishu.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Streaming mono resampler to [outRate]. Low-passes below the new Nyquist first (a windowed-sinc FIR), so music,
 * hiss and sibilance above 8 kHz do not fold back into the speech band, then interpolates linearly.
 * Feed blocks with [process]; the filter and phase carry over, so block boundaries leave no clicks.
 */
class Resampler(private val inRate: Int, private val outRate: Int = WavFile.SAMPLE_RATE) {
    private val step = inRate.toDouble() / outRate
    private val taps: FloatArray = if (inRate > outRate) lowPass(0.45 * outRate / inRate) else floatArrayOf(1f)
    private val history = FloatArray(taps.size)
    private var hPos = 0
    private var prev = 0f
    private var pos = 0.0 // position of the next output sample, in input samples after `prev`

    fun process(input: FloatArray): FloatArray {
        val out = FloatArray((input.size / step).toInt() + 2)
        var n = 0
        for (x in input) {
            val y = filter(x)
            // Emit every output sample that falls between prev (position 0) and y (position 1).
            while (pos <= 1.0) {
                out[n++] = prev + ((y - prev) * pos).toFloat()
                pos += step
            }
            pos -= 1.0
            prev = y
        }
        return out.copyOf(n)
    }

    private fun filter(x: Float): Float {
        if (taps.size == 1) return x
        history[hPos] = x
        var acc = 0f
        var i = hPos
        for (t in taps) {
            acc += t * history[i]
            i = if (i == 0) history.size - 1 else i - 1
        }
        hPos = (hPos + 1) % history.size
        return acc
    }

    companion object {
        /** Hann-windowed sinc, cutoff as a fraction of the input rate, unity gain at DC. */
        fun lowPass(cutoff: Double, n: Int = 63): FloatArray {
            val m = (n - 1) / 2.0
            val h = DoubleArray(n) { i ->
                val t = i - m
                val sinc = if (t == 0.0) 2 * cutoff else sin(2 * PI * cutoff * t) / (PI * t)
                sinc * (0.5 - 0.5 * cos(2 * PI * i / (n - 1)))
            }
            val sum = h.sum()
            return FloatArray(n) { (h[it] / sum).toFloat() }
        }

        /** Interleaved samples of [channels] channels to mono by averaging. */
        fun downmix(interleaved: FloatArray, channels: Int): FloatArray {
            if (channels <= 1) return interleaved
            val frames = interleaved.size / channels
            return FloatArray(frames) { f ->
                var s = 0f
                for (c in 0 until channels) s += interleaved[f * channels + c]
                s / channels
            }
        }
    }
}
