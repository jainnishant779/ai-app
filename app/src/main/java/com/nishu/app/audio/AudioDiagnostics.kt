package com.nishu.app.audio

import com.nishu.app.stt.WavReader
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Diagnostic metrics describing audio health and acoustic properties.
 */
data class AudioReport(
    val durationMs: Long,
    val sampleRate: Int,
    val rmsDb: Float,
    val peakDb: Float,
    val dcOffset: Float,
    val noiseFloorDb: Float,
    val speechLevelDb: Float,
    val snrDb: Float,
    val clippingPercent: Float,
    val hum50HzRatio: Float,
    val hum60HzRatio: Float,
    val hasHum50Hz: Boolean,
    val hasHum60Hz: Boolean,
    val targetGain: Float,
) {
    /** True if the recording has clean signal-to-noise ratio and requires minimal DSP. */
    val isCleanSpeech: Boolean get() = snrDb >= 20f && !hasHum50Hz && !hasHum60Hz && clippingPercent < 0.1f

    /** True if the recording is severely corrupted by noise or clipping. */
    val isNoisy: Boolean get() = snrDb < 10f || hasHum50Hz || hasHum60Hz
}

/**
 * Single-pass audio diagnostic engine: calculates noise floor, speech level, SNR,
 * DC offset drift, clipping percentage, and mains electrical hum (50 Hz / 60 Hz).
 */
object AudioDiagnostics {
    private const val FRAME_SIZE = 400 // 25 ms at 16 kHz
    private const val TARGET_SPEECH_DB = -20f
    private const val MAX_GAIN_DB = 15f
    private const val MIN_GAIN_DB = -6f
    private const val HUM_RATIO_THRESHOLD = 4.0f // 6 dB prominence over adjacent bands

    /**
     * Analyzes a WAV file completely in streaming chunks without loading the whole file into RAM.
     */
    fun analyze(wav: File): AudioReport {
        if (!wav.exists() || wav.length() < WavFile.HEADER_SIZE) {
            return emptyReport()
        }

        var totalSamples = 0L
        var sumSquares = 0.0
        var sumSamples = 0.0
        var maxPeak = 0f
        var clippedSamples = 0L
        var sampleRate = 16_000

        val frameEnergies = ArrayList<Float>()
        val goertzel50 = GoertzelDetector(50f, 16_000)
        val goertzel45 = GoertzelDetector(45f, 16_000)
        val goertzel55 = GoertzelDetector(55f, 16_000)
        val goertzel60 = GoertzelDetector(60f, 16_000)
        val goertzel65 = GoertzelDetector(65f, 16_000)

        WavReader(wav).use { reader ->
            sampleRate = reader.sampleRate
            val chunk = FloatArray(FRAME_SIZE * 40) // 1 second buffer
            while (true) {
                val samples = reader.read(chunk.size)
                if (samples.isEmpty()) break

                for (s in samples) {
                    val absVal = abs(s)
                    if (absVal > maxPeak) maxPeak = absVal
                    if (absVal >= 0.98f) clippedSamples++
                    sumSquares += (s.toDouble() * s)
                    sumSamples += s.toDouble()
                    totalSamples++

                    // Feed hum detectors
                    goertzel50.process(s)
                    goertzel45.process(s)
                    goertzel55.process(s)
                    goertzel60.process(s)
                    goertzel65.process(s)
                }

                var i = 0
                while (i + FRAME_SIZE <= samples.size) {
                    var frameSum = 0.0
                    for (j in i until i + FRAME_SIZE) {
                        frameSum += samples[j].toDouble() * samples[j]
                    }
                    val frameRms = (frameSum / FRAME_SIZE) + 1e-12
                    frameEnergies.add((10.0 * log10(frameRms)).toFloat())
                    i += FRAME_SIZE
                }
            }
        }

        if (totalSamples == 0L) return emptyReport()

        val rms = sqrt(sumSquares / totalSamples).toFloat()
        val rmsDb = (20.0 * log10((rms + 1e-9).toDouble())).toFloat()
        val peakDb = (20.0 * log10((maxPeak + 1e-9).toDouble())).toFloat()
        val dcOffset = (sumSamples / totalSamples).toFloat()
        val clippingPercent = (clippedSamples.toFloat() / totalSamples) * 100f
        val durationMs = (totalSamples * 1000L / sampleRate)

        // Noise floor: median of quietest 10% of frames
        val (noiseFloorDb, speechLevelDb, snrDb) = calculateLevels(frameEnergies)

        // Goertzel Hum Power & Ratios
        val power50 = goertzel50.power()
        val power45 = goertzel45.power()
        val power55 = goertzel55.power()
        val power60 = goertzel60.power()
        val power65 = goertzel65.power()

        val adj50 = ((power45 + power55) / 2.0).coerceAtLeast(1e-9)
        val adj60 = ((power55 + power65) / 2.0).coerceAtLeast(1e-9)
        val hum50Ratio = (power50 / adj50).toFloat()
        val hum60Ratio = (power60 / adj60).toFloat()

        // Absolute power check ensures hum is audible and not just quiet room noise
        val minHumPower = 1e-6
        val hasHum50 = hum50Ratio >= HUM_RATIO_THRESHOLD && power50 > minHumPower
        val hasHum60 = hum60Ratio >= HUM_RATIO_THRESHOLD && power60 > minHumPower

        // Target gain targeting -20 dBFS for speech
        val gainDb = (TARGET_SPEECH_DB - speechLevelDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val targetGain = 10f.pow(gainDb / 20f)

        return AudioReport(
            durationMs = durationMs,
            sampleRate = sampleRate,
            rmsDb = rmsDb,
            peakDb = peakDb,
            dcOffset = dcOffset,
            noiseFloorDb = noiseFloorDb,
            speechLevelDb = speechLevelDb,
            snrDb = snrDb,
            clippingPercent = clippingPercent,
            hum50HzRatio = hum50Ratio,
            hum60HzRatio = hum60Ratio,
            hasHum50Hz = hasHum50,
            hasHum60Hz = hasHum60,
            targetGain = targetGain,
        )
    }

    /**
     * Fast in-memory array analysis for buffered chunks or audio tests.
     */
    fun analyze(samples: FloatArray, sampleRate: Int = 16_000): AudioReport {
        if (samples.isEmpty()) return emptyReport()

        var sumSquares = 0.0
        var sumSamples = 0.0
        var maxPeak = 0f
        var clippedSamples = 0L

        val goertzel50 = GoertzelDetector(50f, sampleRate)
        val goertzel45 = GoertzelDetector(45f, sampleRate)
        val goertzel55 = GoertzelDetector(55f, sampleRate)
        val goertzel60 = GoertzelDetector(60f, sampleRate)
        val goertzel65 = GoertzelDetector(65f, sampleRate)

        val frameEnergies = ArrayList<Float>()

        for (s in samples) {
            val absVal = abs(s)
            if (absVal > maxPeak) maxPeak = absVal
            if (absVal >= 0.98f) clippedSamples++
            sumSquares += (s.toDouble() * s)
            sumSamples += s.toDouble()

            goertzel50.process(s)
            goertzel45.process(s)
            goertzel55.process(s)
            goertzel60.process(s)
            goertzel65.process(s)
        }

        var i = 0
        while (i + FRAME_SIZE <= samples.size) {
            var frameSum = 0.0
            for (j in i until i + FRAME_SIZE) frameSum += samples[j].toDouble() * samples[j]
            val frameRms = (frameSum / FRAME_SIZE) + 1e-12
            frameEnergies.add((10.0 * log10(frameRms)).toFloat())
            i += FRAME_SIZE
        }

        val total = samples.size
        val rms = sqrt(sumSquares / total).toFloat()
        val rmsDb = (20.0 * log10((rms + 1e-9).toDouble())).toFloat()
        val peakDb = (20.0 * log10((maxPeak + 1e-9).toDouble())).toFloat()
        val dcOffset = (sumSamples / total).toFloat()
        val clippingPercent = (clippedSamples.toFloat() / total) * 100f
        val durationMs = (total.toLong() * 1000L / sampleRate)

        val (noiseFloorDb, speechLevelDb, snrDb) = calculateLevels(frameEnergies)

        val power50 = goertzel50.power()
        val power45 = goertzel45.power()
        val power55 = goertzel55.power()
        val power60 = goertzel60.power()
        val power65 = goertzel65.power()

        val adj50 = ((power45 + power55) / 2.0).coerceAtLeast(1e-9)
        val adj60 = ((power55 + power65) / 2.0).coerceAtLeast(1e-9)
        val hum50Ratio = (power50 / adj50).toFloat()
        val hum60Ratio = (power60 / adj60).toFloat()

        val minHumPower = 1e-6
        val hasHum50 = hum50Ratio >= HUM_RATIO_THRESHOLD && power50 > minHumPower
        val hasHum60 = hum60Ratio >= HUM_RATIO_THRESHOLD && power60 > minHumPower

        val gainDb = (TARGET_SPEECH_DB - speechLevelDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val targetGain = 10f.pow(gainDb / 20f)

        return AudioReport(
            durationMs = durationMs,
            sampleRate = sampleRate,
            rmsDb = rmsDb,
            peakDb = peakDb,
            dcOffset = dcOffset,
            noiseFloorDb = noiseFloorDb,
            speechLevelDb = speechLevelDb,
            snrDb = snrDb,
            clippingPercent = clippingPercent,
            hum50HzRatio = hum50Ratio,
            hum60HzRatio = hum60Ratio,
            hasHum50Hz = hasHum50,
            hasHum60Hz = hasHum60,
            targetGain = targetGain,
        )
    }

    private fun calculateLevels(frameEnergies: List<Float>): Triple<Float, Float, Float> {
        if (frameEnergies.isEmpty()) return Triple(-45f, -20f, 25f)
        val sorted = frameEnergies.sorted()

        // Noise floor = median of quietest 10%
        val noiseIdx = (sorted.size * 0.10).toInt().coerceIn(0, sorted.lastIndex)
        val noiseFloorDb = sorted[noiseIdx].coerceIn(-65f, -25f)

        // Speech level = 75th percentile of frames that exceed noise floor
        val activeFrames = sorted.filter { it > noiseFloorDb + 3f }
        val framesForSpeech = if (activeFrames.size >= sorted.size / 10) activeFrames else sorted
        val speechIdx = (framesForSpeech.size * 0.75).toInt().coerceIn(0, framesForSpeech.lastIndex)
        val speechLevelDb = framesForSpeech[speechIdx].coerceIn(-50f, -6f)

        val snrDb = max(0f, speechLevelDb - noiseFloorDb)
        return Triple(noiseFloorDb, speechLevelDb, snrDb)
    }

    private fun emptyReport() = AudioReport(
        durationMs = 0L,
        sampleRate = 16_000,
        rmsDb = -90f,
        peakDb = -90f,
        dcOffset = 0f,
        noiseFloorDb = -45f,
        speechLevelDb = -20f,
        snrDb = 25f,
        clippingPercent = 0f,
        hum50HzRatio = 1f,
        hum60HzRatio = 1f,
        hasHum50Hz = false,
        hasHum60Hz = false,
        targetGain = 1f,
    )

    /**
     * Discrete Goertzel filter for evaluating power at an exact target frequency.
     */
    private class GoertzelDetector(freq: Float, sampleRate: Int) {
        private val coeff = 2.0 * cos(2.0 * PI * freq / sampleRate)
        private var s1 = 0.0
        private var s2 = 0.0

        fun process(x: Float) {
            val s0 = x.toDouble() + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }

        fun power(): Double {
            return s1 * s1 + s2 * s2 - coeff * s1 * s2
        }
    }
}
