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
    private val customVocabulary: String = "",
) : SttEngine {
    override val modelId = spec.id
    @Volatile private var isFirstSegment = true

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
        val (gain, noiseFloorDb) = if (preprocess) AudioPreprocessor.measure(wav) else (1f to AudioPreprocessor.DEFAULT_NOISE_FLOOR_DB)

        withContext(Dispatchers.Default) {
            val vad = Vad(null, VadModelConfig().apply {
                sileroVadModelConfig = SileroVadModelConfig().apply {
                    model = vadPath
                    // Measured with tools/stt_pipeline_sweep.py (meeting WER vs Qwen3-ASR, noisy and Hinglish clips):
                    // 0.30 keeps soft speech at a recording's start that 0.50 drops; long chunks gave the lowest WER
                    // (0.19/0.24 vs 0.21/0.26 at 12 s) with ~2.3x fewer recognizer calls.
                    threshold = 0.30f
                    minSilenceDuration = 0.45f
                    minSpeechDuration = 0.25f
                    maxSpeechDuration = 27f // with the lead and tail padding this stays under MAX_WHISPER_SAMPLES
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
            val pre = AudioPreprocessor(gain, noiseFloorDb)
            isFirstSegment = true
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

            // Prepend 320 ms of silence before the very first VAD segment so Whisper's encoder
            // sees a clean silence→speech onset, matching its training distribution.
            // Also append 250 ms tail silence padding so trailing syllables (e.g. "-ta hai", "-gi")
            // are cleanly resolved before Whisper hits the EOT token.
            val lead = if (isFirstSegment) SILENCE_PREFIX else 0
            isFirstSegment = false
            // [lead] silence + speech + TAIL_PAD silence. Indices into the speech are shifted by [lead].
            val segSamples = FloatArray(lead + seg.samples.size + TAIL_PAD).also { seg.samples.copyInto(it, lead) }
            val speechEnd = lead + seg.samples.size

            // One piece per speaker; without speaker info this is the whole segment, as before.
            val pieces = SpeakerTurns.split(segStartMs, segEndMs, turns)
            for ((pieceIdx, piece) in pieces.withIndex()) {
                val from = lead + ((piece.startMs - segStartMs) * SAMPLE_RATE / 1000).toInt().coerceIn(0, seg.samples.size)
                val to = lead + ((piece.endMs - segStartMs) * SAMPLE_RATE / 1000).toInt().coerceIn(from - lead, seg.samples.size)
                if (to - from < MIN_SAMPLES) continue

                // 200 ms of the neighbouring piece on each side, so a word on a speaker boundary keeps its onset;
                // the first piece also gets the lead silence and the last one the tail silence.
                val padFrom = if (pieceIdx > 0) maxOf(lead, from - CONTEXT_PAD) else 0
                val padTo = if (pieceIdx < pieces.lastIndex) minOf(speechEnd, to + CONTEXT_PAD) else segSamples.size

                // Whisper reads 30 s. The padding must never push a segment over that limit and cut it mid-word.
                for (part in SpeechChunks.split(padTo - padFrom, MAX_WHISPER_SAMPLES)) {
                    val a = padFrom + part.first
                    val b = padFrom + part.last + 1
                    // Each part keeps its own time span, clamped to the piece (padding is not speech time).
                    val startMs = segStartMs + (maxOf(a, from) - lead) * 1000L / SAMPLE_RATE
                    val endMs = segStartMs + (minOf(b, to) - lead) * 1000L / SAMPLE_RATE
                    batcher.add(startMs, maxOf(startMs, endMs), piece.speaker, segSamples.copyOfRange(a, b))
                        ?.let { recognize(recognizer, it, onSegment) }
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
            return SttText.clean(raw, durationMs, userName, customVocabulary)
        } finally {
            stream.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW = 512
        const val MIN_SAMPLES = SAMPLE_RATE / 4 // under 0.25 s there is nothing to recognize
        /**
         * Whisper reads 30 s; stay clear of the edge. This only guards the model's input size. How long a segment is
         * allowed to be is the VAD's max speech setting: at 12 s here, a 12 s segment plus its padding was split in
         * half mid-word and both halves got the same timestamp (seen on the phone, conversations 46 and 65).
         */
        const val MAX_WHISPER_SAMPLES = 28 * SAMPLE_RATE
        const val BATCH_SAMPLES = 25 * SAMPLE_RATE
        const val SILENCE_PREFIX = SAMPLE_RATE * 320 / 1000 // 320 ms of silence before first segment
        const val TAIL_PAD = SAMPLE_RATE * 250 / 1000       // 250 ms tail hangover padding
        const val CONTEXT_PAD = SAMPLE_RATE * 200 / 1000    // 200 ms context overlap at speaker boundaries
    }
}
