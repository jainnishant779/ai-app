package com.nishu.app.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import com.nishu.app.data.db.NishuDatabase
import com.nishu.app.data.db.SpeakerNameEntity
import com.nishu.app.data.db.SpeakerProfileEntity
import com.nishu.app.data.db.TranscriptSegmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
  * On-device biometric voice identification and memory matching.
  * Uses 3D-Speaker CAM++ (zh/en) to extract 512-dimensional voiceprints and matches them
  * across multiple recordings so speakers are remembered automatically.
  */
object SpeakerVoiceMatcher {
    private const val TAG = "SpeakerVoiceMatcher"
    private const val SAMPLE_RATE = 16_000
    private const val MIN_SAMPLES = 8_000 // At least 0.5 second of speech
    const val MATCH_THRESHOLD = 0.50f

    fun modelFile(modelRoot: File): File {
        val direct = File(modelRoot, "diar/3dspeaker-campplus-zh-en.onnx")
        if (direct.exists()) return direct
        val sub = File(modelRoot, "models/stt/diar/3dspeaker-campplus-zh-en.onnx")
        if (sub.exists()) return sub
        return direct
    }

    fun isInstalled(modelRoot: File): Boolean = modelFile(modelRoot).exists()

    /**
     * Extracts a 512-dimensional speaker embedding vector from raw 16kHz PCM samples.
     */
    fun extractEmbedding(modelRoot: File, samples: FloatArray): FloatArray? {
        val model = modelFile(modelRoot)
        if (!model.exists()) {
            Log.w(TAG, "extractEmbedding: model missing at ${model.absolutePath}")
            return null
        }
        val effectiveSamples = if (samples.size in 4_000 until MIN_SAMPLES) {
            padToMin(samples, MIN_SAMPLES)
        } else if (samples.size < 4_000) {
            Log.w(TAG, "extractEmbedding: samples too short (${samples.size})")
            return null
        } else {
            samples
        }

        return try {
            val config = SpeakerEmbeddingExtractorConfig(model.absolutePath, 2, false, "cpu")
            val extractor = SpeakerEmbeddingExtractor(null, config)
            try {
                val stream = extractor.createStream()
                try {
                    stream.acceptWaveform(effectiveSamples, SAMPLE_RATE)
                    stream.inputFinished()
                    l2Normalize(extractor.compute(stream))
                } finally {
                    stream.release()
                }
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract speaker embedding", e)
            null
        }
    }

    private fun padToMin(samples: FloatArray, minSize: Int = 8_000): FloatArray {
        if (samples.isEmpty()) return FloatArray(minSize)
        val out = FloatArray(minSize)
        var pos = 0
        while (pos < minSize) {
            val copyLen = minOf(samples.size, minSize - pos)
            samples.copyInto(out, pos, 0, copyLen)
            pos += copyLen
        }
        return out
    }

    /**
     * Selects the highest-energy audio windows (up to [maxDurationSec] seconds) from the waveform.
     * Prevents silence or low-level background noise from corrupting speaker embeddings.
     */
    fun selectBestSpeechSamples(samples: FloatArray, maxDurationSec: Int = 15): FloatArray {
        if (samples.size <= maxDurationSec * SAMPLE_RATE) {
            return if (samples.size >= MIN_SAMPLES) samples else padToMin(samples, MIN_SAMPLES)
        }
        val windowSize = SAMPLE_RATE / 2 // 500 ms windows
        val numWindows = samples.size / windowSize
        val windowEnergies = (0 until numWindows).map { w ->
            var energy = 0f
            val start = w * windowSize
            for (i in start until start + windowSize) {
                val s = samples[i]
                energy += s * s
            }
            w to energy
        }
        val targetWindows = maxDurationSec * 2
        val selectedWindowIndices = windowEnergies
            .sortedByDescending { it.second }
            .take(targetWindows)
            .map { it.first }
            .sorted()

        val out = mutableListOf<Float>()
        for (w in selectedWindowIndices) {
            val start = w * windowSize
            for (i in start until start + windowSize) {
                out.add(samples[i])
            }
        }
        return if (out.size >= MIN_SAMPLES) out.toFloatArray() else padToMin(out.toFloatArray(), MIN_SAMPLES)
    }

    fun l2Normalize(v: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in v) sumSq += (x * x).toDouble()
        val norm = sqrt(sumSq)
        if (norm <= 1e-12) return v
        return FloatArray(v.size) { i -> (v[i] / norm).toFloat() }
    }

    /**
     * Computes cosine similarity between two embedding vectors.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0.0) (dot / denom).toFloat() else 0f
    }

    /**
     * Matches speaker turns in a new recording against all stored biometric voice profiles in Room DB.
     * When a match is found, automatically records the name in speaker_name table for both "S1" and "Speaker 1".
     */
    suspend fun matchAndAssignSpeakers(
        db: NishuDatabase,
        modelRoot: File,
        wav: File,
        turns: List<SpeakerTurn>,
        conversationId: Long,
    ) = withContext(Dispatchers.IO) {
        if (!isInstalled(modelRoot) || !wav.exists()) {
            Log.w(TAG, "matchAndAssignSpeakers skipped: installed=${isInstalled(modelRoot)}, wavExists=${wav.exists()}")
            return@withContext
        }
        val profiles = db.speakerProfiles().getAll()
        if (profiles.isEmpty()) {
            Log.d(TAG, "No enrolled voice profiles in database yet")
            return@withContext
        }

        val allSamples = WavReader(wav).use { r ->
            FloatArray(r.totalSamples.toInt()).also { arr ->
                var pos = 0
                while (pos < arr.size) {
                    val read = r.read(minOf(65_536, arr.size - pos))
                    if (read.isEmpty()) break
                    read.copyInto(arr, pos)
                    pos += read.size
                }
            }
        }
        val durationMs = (allSamples.size * 1000L / SAMPLE_RATE)
        val effectiveTurns = if (turns.isNotEmpty()) turns else listOf(SpeakerTurn(0, durationMs, 0))
        val distinctSpeakers = effectiveTurns.map { it.speaker }.distinct()

        val requiredThreshold = if (profiles.size == 1) 0.46f else MATCH_THRESHOLD

        for (spk in distinctSpeakers) {
            val label = "S${spk + 1}"
            val friendlyLabel = "Speaker ${spk + 1}"
            val spkTurns = effectiveTurns.filter { it.speaker == spk }

            val rawSamples = mutableListOf<Float>()
            for (turn in spkTurns) {
                val startIdx = (turn.startMs * SAMPLE_RATE / 1000).toInt().coerceIn(0, allSamples.size)
                val endIdx = (turn.endMs * SAMPLE_RATE / 1000).toInt().coerceIn(0, allSamples.size)
                if (endIdx > startIdx) {
                    for (i in startIdx until endIdx) {
                        rawSamples.add(allSamples[i])
                        if (rawSamples.size >= 15 * SAMPLE_RATE) break
                    }
                }
                if (rawSamples.size >= 15 * SAMPLE_RATE) break
            }

            val samplesToUse = if (rawSamples.size >= MIN_SAMPLES) {
                rawSamples.toFloatArray()
            } else {
                selectBestSpeechSamples(allSamples)
            }

            val embedding = extractEmbedding(modelRoot, samplesToUse)
            if (embedding == null) {
                Log.w(TAG, "Could not extract embedding for speaker $label")
                continue
            }

            var bestMatch: String? = null
            var bestScore = requiredThreshold

            for (p in profiles) {
                val enrolledVec = parseFloats(p.embedding) ?: continue
                val score = cosineSimilarity(embedding, enrolledVec)
                Log.i(TAG, "Biometric check: Speaker $label vs '${p.name}' similarity = $score (needed > $requiredThreshold)")
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = p.name
                }
            }

            if (bestMatch != null) {
                Log.i(TAG, "VOICE MATCH SUCCESS! Assigned speaker $label as '$bestMatch' (sim: $bestScore)")
                db.speakers().upsert(SpeakerNameEntity(conversationId, label, bestMatch))
                db.speakers().upsert(SpeakerNameEntity(conversationId, friendlyLabel, bestMatch))
            } else {
                Log.d(TAG, "No match above threshold for $label (best sim: $bestScore)")
                if (db.speakers().get(conversationId, label) == null) {
                    db.speakers().upsert(SpeakerNameEntity(conversationId, label, friendlyLabel))
                }
            }
        }
    }

    /**
     * Enrolls or updates a voiceprint into the persistent biometric vault.
     */
    suspend fun enrollVoice(
        db: NishuDatabase,
        modelRoot: File,
        wav: File,
        turns: List<SpeakerTurn>,
        speakerLabel: String,
        name: String,
    ) = withContext(Dispatchers.IO) {
        val trimmedName = name.trim()
        if (!isInstalled(modelRoot) || !wav.exists() || trimmedName.isBlank()) {
            Log.w(TAG, "enrollVoice skipped: installed=${isInstalled(modelRoot)}, wavExists=${wav.exists()}, name='$trimmedName'")
            return@withContext
        }

        val allSamples = WavReader(wav).use { r ->
            FloatArray(r.totalSamples.toInt()).also { arr ->
                var pos = 0
                while (pos < arr.size) {
                    val read = r.read(minOf(65_536, arr.size - pos))
                    if (read.isEmpty()) break
                    read.copyInto(arr, pos)
                    pos += read.size
                }
            }
        }
        val durationMs = (allSamples.size * 1000L / SAMPLE_RATE)
        val effectiveTurns = if (turns.isNotEmpty()) turns else listOf(SpeakerTurn(0, durationMs, 0))

        val normalized = normalizeLabel(speakerLabel)
        val spkIndex = effectiveTurns.map { it.speaker }.distinct().firstOrNull {
            SpeakerTurns.label(it) == normalized || "Speaker ${it + 1}" == speakerLabel
        } ?: 0

        val spkTurns = effectiveTurns.filter { it.speaker == spkIndex }
        val rawSamples = mutableListOf<Float>()
        for (turn in spkTurns) {
            val startIdx = (turn.startMs * SAMPLE_RATE / 1000).toInt().coerceIn(0, allSamples.size)
            val endIdx = (turn.endMs * SAMPLE_RATE / 1000).toInt().coerceIn(0, allSamples.size)
            if (endIdx > startIdx) {
                for (i in startIdx until endIdx) {
                    rawSamples.add(allSamples[i])
                    if (rawSamples.size >= 15 * SAMPLE_RATE) break
                }
            }
            if (rawSamples.size >= 15 * SAMPLE_RATE) break
        }

        val samplesToUse = if (rawSamples.size >= MIN_SAMPLES) {
            rawSamples.toFloatArray()
        } else {
            selectBestSpeechSamples(allSamples)
        }

        val newVec = extractEmbedding(modelRoot, samplesToUse)
        if (newVec == null) {
            Log.w(TAG, "Failed to extract embedding for enrollment of '$trimmedName'")
            return@withContext
        }

        val existing = db.speakerProfiles().findByName(trimmedName)
        if (existing != null) {
            val oldVec = parseFloats(existing.embedding)
            val mergedVec = if (oldVec != null && oldVec.size == newVec.size) {
                val averaged = FloatArray(newVec.size) { i -> (oldVec[i] * existing.sampleCount + newVec[i]) / (existing.sampleCount + 1) }
                l2Normalize(averaged)
            } else {
                l2Normalize(newVec)
            }
            db.speakerProfiles().upsert(
                existing.copy(
                    embedding = mergedVec.joinToString(","),
                    sampleCount = existing.sampleCount + 1,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            Log.i(TAG, "UPDATED voiceprint for '$trimmedName' (total samples: ${existing.sampleCount + 1})")
        } else {
            db.speakerProfiles().upsert(
                SpeakerProfileEntity(
                    name = trimmedName,
                    embedding = l2Normalize(newVec).joinToString(","),
                    sampleCount = 1,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            Log.i(TAG, "ENROLLED new voiceprint for '$trimmedName'")
        }
    }

    /**
     * Enrolls voiceprint using stored transcript segments for a speaker label (e.g. "S1" or "Speaker 1").
     */
    suspend fun enrollVoiceFromSegments(
        db: NishuDatabase,
        modelRoot: File,
        wav: File,
        segments: List<TranscriptSegmentEntity>,
        speakerLabel: String,
        name: String,
    ) = withContext(Dispatchers.IO) {
        val norm = normalizeLabel(speakerLabel)
        val matchingSegments = segments.filter {
            val l = it.speakerLabel?.takeIf { s -> s.isNotBlank() } ?: "S1"
            normalizeLabel(l) == norm
        }.ifEmpty {
            if (norm == "S1") segments else emptyList()
        }
        val fakeTurns = if (matchingSegments.isNotEmpty()) {
            matchingSegments.map { SpeakerTurn(it.startMs, it.endMs, 0) }
        } else {
            listOf(SpeakerTurn(0, com.nishu.app.audio.WavFile.durationMs(wav), 0))
        }
        enrollVoice(db, modelRoot, wav, fakeTurns, norm, name)
    }

    fun normalizeLabel(label: String): String = when {
        label.startsWith("Speaker ", ignoreCase = true) -> "S" + label.substring(8).trim()
        label.startsWith("S", ignoreCase = true) -> "S" + label.substring(1).trim()
        else -> label.trim()
    }

    private fun parseFloats(raw: String): FloatArray? = try {
        raw.split(",").map { it.trim().toFloat() }.toFloatArray()
    } catch (_: Exception) {
        null
    }
}
