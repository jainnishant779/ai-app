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
 */
class SherpaOnnxStt(private val modelDir: File, private val numThreads: Int = 2) : SttEngine {
    override val modelId = "whisper-tiny.en-int8"

    private fun model(name: String) = File(modelDir, name).also {
        if (!it.exists()) throw SttModelMissing("missing STT model file: ${it.path} (run tools/push_models.ps1)")
    }.absolutePath

    override suspend fun transcribe(wav: File, onProgress: (Float) -> Unit, onSegment: suspend (Seg) -> Unit) {
        val vadPath = model("silero_vad.onnx")
        val encoder = model("tiny.en-encoder.int8.onnx")
        val decoder = model("tiny.en-decoder.int8.onnx")
        val tokens = model("tiny.en-tokens.txt")

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
                    language = "en"
                    task = "transcribe"
                }
                modelConfig.tokens = tokens
                modelConfig.numThreads = numThreads
                modelConfig.provider = "cpu"
                modelConfig.modelType = "whisper"
            })
            try {
                WavReader(wav).use { reader ->
                    require(reader.sampleRate == SAMPLE_RATE) { "expected 16 kHz audio, got ${reader.sampleRate}" }
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val samples = reader.read(WINDOW)
                        if (samples.isEmpty()) break
                        vad.acceptWaveform(samples)
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
                val text = SttText.clean(recognizer.getResult(stream).text)
                if (text != null) {
                    val start = seg.start * 1000L / SAMPLE_RATE
                    onSegment(Seg(start, start + seg.samples.size * 1000L / SAMPLE_RATE, text))
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
