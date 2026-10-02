package com.nishu.app.stt

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Offline whisper over Silero-VAD segments. Offline (not streaming) because transcription runs after the
 * recording stops, and batching whole speech segments is more accurate. VAD is required: whisper hallucinates
 * confidently on silence, and the VAD segments give real timestamps for free.
 *
 * @param modelRoot `files/models/stt`
 * @param spec which whisper model to use; defaults to the best one installed.
 * @param provider onnxruntime execution provider: "cpu" or "nnapi".
 */
class SherpaOnnxStt(
    private val modelRoot: File,
    private val spec: SttModelSpec = SttModelSpec.select(modelRoot),
    private val numThreads: Int = 4,
    private val provider: String = "cpu",
    private val preprocess: Boolean = true,
    private val userName: String = "",
) : SttEngine {
    override val modelId = spec.id

    private fun need(f: File): String {
        if (!f.exists()) throw SttModelMissing("missing STT model file: ${f.path} (run tools/push_models.ps1)")
        return f.absolutePath
    }

    override suspend fun transcribe(
        wav: File,
        onProgress: (Float) -> Unit,
        speakerTurns: List<SpeakerTurn>,
        onSegment: suspend (Seg) -> Unit,
    ) {
        val vadPath = need(File(modelRoot, "silero_vad.onnx"))
        val encoder = need(spec.encoder(modelRoot))
        val decoder = need(spec.decoder(modelRoot))
        val qwen = spec.engine == SttModelSpec.Engine.QWEN3_ASR
        val tokens = if (qwen) "" else need(spec.tokens(modelRoot))
        val convFrontend = if (qwen) need(spec.convFrontend(modelRoot)) else ""
        val tokenizer = if (qwen) File(need(File(spec.tokenizerDir(modelRoot), "vocab.json"))).parent!! else ""
        val gain = if (preprocess) AudioPreprocessor.measureGain(wav) else 1f

        withContext(Dispatchers.Default) {
            val vad = Vad(null, VadModelConfig().apply {
                sileroVadModelConfig = SileroVadModelConfig().apply {
                    model = vadPath
                    threshold = 0.30f
                    minSilenceDuration = 0.30f
                    minSpeechDuration = 0.25f
                    maxSpeechDuration = 12f
                    windowSize = WINDOW
                }
                sampleRate = SAMPLE_RATE
                numThreads = 1
                provider = "cpu"
            })
            val recognizer = OfflineRecognizer(null, OfflineRecognizerConfig().apply {
                if (qwen) {
                    modelConfig.qwen3Asr = OfflineQwen3AsrModelConfig().apply {
                        this.convFrontend = convFrontend
                        this.encoder = encoder
                        this.decoder = decoder
                        this.tokenizer = tokenizer
                        // A 25 s chunk of Hindi in Devanagari needs far more than the default 128 new tokens.
                        maxTotalLen = 1024
                        maxNewTokens = 448
                    }
                } else {
                    modelConfig.whisper = OfflineWhisperModelConfig().apply {
                        this.encoder = encoder
                        this.decoder = decoder
                        language = spec.language
                        task = "transcribe"
                    }
                    modelConfig.tokens = tokens
                    modelConfig.modelType = "whisper"
                }
                modelConfig.numThreads = numThreads
                modelConfig.provider = provider
            })
            val pre = AudioPreprocessor(gain)
            val batcher = SpeechBatcher(BATCH_SAMPLES)
            try {
                WavReader(wav).use { reader ->
                    require(reader.sampleRate == SAMPLE_RATE) { "expected 16 kHz audio, got ${reader.sampleRate}" }
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val samples = reader.read(WINDOW)
                        if (samples.isEmpty()) break
                        vad.acceptWaveform(if (preprocess) pre.process(samples) else samples)
                        done += samples.size
                        drain(vad, recognizer, speakerTurns, batcher, onSegment)
                        if (reader.totalSamples > 0) onProgress(done.toFloat() / reader.totalSamples)
                    }
                    vad.flush()
                    drain(vad, recognizer, speakerTurns, batcher, onSegment)
                    batcher.flush()?.let { recognize(recognizer, it, onSegment) }
                    onProgress(1f)
                }
            } finally {
                // Release before returning: the LLM must not be loaded alongside onnxruntime.
                recognizer.release()
                vad.release()
            }
        }
    }

    private suspend fun drain(
        vad: Vad,
        recognizer: OfflineRecognizer,
        turns: List<SpeakerTurn>,
        batcher: SpeechBatcher,
        onSegment: suspend (Seg) -> Unit,
    ) {
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            val segStartMs = seg.start * 1000L / SAMPLE_RATE
            val segEndMs = segStartMs + seg.samples.size * 1000L / SAMPLE_RATE
            // One piece per speaker; without speaker info this is the whole segment, as before.
            for (piece in SpeakerTurns.split(segStartMs, segEndMs, turns)) {
                val from = ((piece.startMs - segStartMs) * SAMPLE_RATE / 1000).toInt().coerceIn(0, seg.samples.size)
                val to = ((piece.endMs - segStartMs) * SAMPLE_RATE / 1000).toInt().coerceIn(from, seg.samples.size)
                if (to - from < MIN_SAMPLES) continue
                for (part in SpeechChunks.split(to - from, MAX_WHISPER_SAMPLES)) {
                    val a = from + part.first
                    val b = from + part.last + 1
                    val startMs = segStartMs + a * 1000L / SAMPLE_RATE
                    val endMs = segStartMs + b * 1000L / SAMPLE_RATE
                    val samples = if (a == 0 && b == seg.samples.size) seg.samples else seg.samples.copyOfRange(a, b)
                    batcher.add(startMs, endMs, piece.speaker, samples)?.let { recognize(recognizer, it, onSegment) }
                }
            }
        }
    }

    private suspend fun recognize(recognizer: OfflineRecognizer, batch: SpeechBatch, onSegment: suspend (Seg) -> Unit) {
        val text = recognize(recognizer, batch.samples, batch.endMs - batch.startMs)
        if (text != null) onSegment(Seg(batch.startMs, batch.endMs, text, batch.speaker))
    }

    private fun recognize(recognizer: OfflineRecognizer, samples: FloatArray, durationMs: Long): String? {
        val stream = recognizer.createStream()
        try {
            val t0 = System.nanoTime()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val raw = recognizer.getResult(stream).text
            val ms = (System.nanoTime() - t0) / 1_000_000
            android.util.Log.i("NishuStt", "segment ${durationMs} ms of audio -> decode $ms ms, ${raw.length} chars")
            return SttText.clean(raw, durationMs, userName)
        } finally {
            stream.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW = 512
        const val MIN_SAMPLES = SAMPLE_RATE / 4 // under 0.25 s there is nothing to recognize
        const val MAX_WHISPER_SAMPLES = 12 * SAMPLE_RATE // 12 seconds max: avoids Whisper premature EOT on internal pauses
        const val BATCH_SAMPLES = 10 * SAMPLE_RATE       // 10 seconds max batch
    }
}
