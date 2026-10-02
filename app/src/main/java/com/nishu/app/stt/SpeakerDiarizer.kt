package com.nishu.app.stt

import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

/**
 * Who spoke when, fully on-device: pyannote segmentation finds speech and speaker changes, a speaker-embedding
 * model describes each voice, and clustering groups the voices. Both models hear voices, not words, so Hindi,
 * English and Hinglish behave the same.
 *
 * Holds the whole recording as floats (4 bytes per sample), so very long recordings are skipped.
 */
class SpeakerDiarizer(private val modelRoot: File, private val threshold: Float = DEFAULT_THRESHOLD) {
    private val segmentationFile get() = File(modelRoot, "diar/pyannote-segmentation-3-0.int8.onnx")
    private val embeddingFile get() = File(modelRoot, "diar/3dspeaker-campplus-zh-en.onnx")

    val isInstalled: Boolean get() = segmentationFile.exists() && embeddingFile.exists()

    /** Turns with speakers numbered by first appearance, or empty when unavailable or the recording is too long. */
    suspend fun diarize(wav: File): List<SpeakerTurn> = withContext(Dispatchers.Default) {
        if (!isInstalled) return@withContext emptyList()
        // Quiet speech is under the segmentation model's detection level; the same gain as for recognition fixes that
        // (measured on real recordings: 1 detected speaker without it, 2 with it).
        val pre = AudioPreprocessor(AudioPreprocessor.measureGain(wav))
        val samples = WavReader(wav).use { r ->
            if (r.totalSamples > MAX_SAMPLES) return@withContext emptyList()
            FloatArray(r.totalSamples.toInt()).also { out ->
                var at = 0
                while (at < out.size) {
                    val chunk = r.read(minOf(65_536, out.size - at))
                    if (chunk.isEmpty()) break
                    pre.process(chunk).copyInto(out, at)
                    at += chunk.size
                }
            }
        }
        val segPath = segmentationFile.absolutePath
        val embPath = embeddingFile.absolutePath
        val config = OfflineSpeakerDiarizationConfig().apply {
            segmentation.pyannote.model = segPath
            segmentation.numThreads = 2
            embedding = SpeakerEmbeddingExtractorConfig(embPath, 2, false, "cpu")
            clustering.numClusters = -1 // unknown number of speakers; the threshold decides
            clustering.threshold = threshold
            minDurationOn = 0.5f
            minDurationOff = 0.6f
        }
        val sd = OfflineSpeakerDiarization(null, config)
        try {
            val turns = sd.process(samples).map {
                SpeakerTurn((it.start * 1000).roundToLong(), (it.end * 1000).roundToLong(), it.speaker)
            }
            SpeakerTurns.renumber(turns)
        } finally {
            sd.release()
        }
    }

    companion object {
        /**
         * Measured on real mobile recordings: 0.80 over-splits a single speaker's variations into 3+ people;
         * 0.88 merges pitch variations of the same person while still separating distinct voices.
         */
        const val DEFAULT_THRESHOLD = 0.88f
        /** Measured on a noisy 6+ person meeting: about 0.3-0.6x realtime, so longer recordings would stall the transcript for many minutes. */
        const val MAX_SAMPLES = 20L * 60 * 16_000
    }
}
