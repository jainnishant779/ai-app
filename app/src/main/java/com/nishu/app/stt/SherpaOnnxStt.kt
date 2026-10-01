package com.nishu.app.stt

import com.k2fsa.sherpa.onnx.OfflineRecognizer
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
) : SttEngine {
    override val modelId = spec.id

    private fun need(f: File): String {
        if (!f.exists()) throw SttModelMissing("missing STT model file: ${f.path} (run tools/push_models.ps1)")
        return f.absolutePath
    }

    override suspend fun transcribe(wav: File, onProgress: (Float) -> Unit, onSegment: suspend (Seg) -> Unit) {
        val vadPath = need(File(modelRoot, "silero_vad.onnx"))
        val encoder = need(spec.encoder(modelRoot))
        val decoder = need(spec.decoder(modelRoot))
        val tokens = need(spec.tokens(modelRoot))
        val gain = if (preprocess) AudioPreprocessor.measureGain(wav) else 1f

        withContext(Dispatchers.Default) {
            val vad = Vad(null, VadModelConfig().apply {
                sileroVadModelConfig = SileroVadModelConfig().apply {
                    model = vadPath
                    threshold = 0.5f
                    minSilenceDuration = 0.25f
                    minSpeechDuration = 0.25f
                    maxSpeechDuration = 28f
                    windowSize = WINDOW
                }
                sampleRate = SAMPLE_RATE
                numThreads = 1
                provider = "cpu"
            })
            val recognizer = OfflineRecognizer(null, OfflineRecognizerConfig().apply {
                modelConfig.whisper = OfflineWhisperModelConfig().apply {
                    this.encoder = encoder
                    this.decoder = decoder
                    language = spec.language
                    task = "transcribe"
                }
                modelConfig.tokens = tokens
                modelConfig.numThreads = numThreads
                modelConfig.provider = provider
                modelConfig.modelType = "whisper"
            })
            val pre = AudioPreprocessor(gain)
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
                        drain(vad, recognizer, onSegment)
                        if (reader.totalSamples > 0) onProgress(done.toFloat() / reader.totalSamples)
                    }
                    vad.flush()
                    drain(vad, recognizer, onSegment)
                    onProgress(1f)
                }
            } finally {
                // Release before returning: the LLM must not be loaded alongside onnxruntime.
                recognizer.release()
                vad.release()
            }
        }
    }

    private suspend fun drain(vad: Vad, recognizer: OfflineRecognizer, onSegment: suspend (Seg) -> Unit) {
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(seg.samples, SAMPLE_RATE)
                recognizer.decode(stream)
                val durationMs = seg.samples.size * 1000L / SAMPLE_RATE
                val text = SttText.clean(recognizer.getResult(stream).text, durationMs)
                if (text != null) {
                    val start = seg.start * 1000L / SAMPLE_RATE
                    onSegment(Seg(start, start + durationMs, text))
                }
            } finally {
                stream.release()
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW = 512
    }
}
