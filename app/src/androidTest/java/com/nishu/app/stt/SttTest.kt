package com.nishu.app.stt

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.nishu.app.audio.PcmWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SttTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tests get() = InstrumentationRegistry.getInstrumentation().context
    private val modelDir get() = File(app.filesDir, "models/stt")

    private fun engine(): SherpaOnnxStt {
        assumeTrue("push STT models first: tools/push_models.ps1", File(modelDir, "tiny.en-tokens.txt").exists())
        return SherpaOnnxStt(modelDir)
    }

    private fun asset(name: String): File {
        val out = File(app.cacheDir, name.substringAfterLast('/'))
        tests.assets.open(name).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        return out
    }

    private fun words(s: String) = s.lowercase().replace(Regex("[^a-z' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Word error rate by edit distance. */
    private fun wer(ref: List<String>, hyp: List<String>): Double {
        val d = Array(ref.size + 1) { IntArray(hyp.size + 1) }
        for (i in 0..ref.size) d[i][0] = i
        for (j in 0..hyp.size) d[0][j] = j
        for (i in 1..ref.size) for (j in 1..hyp.size) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1)
        }
        return d[ref.size][hyp.size].toDouble() / ref.size
    }

    private fun reference(wavName: String): List<String> =
        tests.assets.open("stt/trans.txt").bufferedReader().readLines()
            .first { it.startsWith(wavName) }.substringAfter(' ').let(::words)

    private fun transcribe(wav: File): List<Seg> = runBlocking {
        val segs = mutableListOf<Seg>()
        engine().transcribe(wav) { segs += it }
        segs
    }

    @Test
    fun transcribesRealSpeechAccurately() {
        for (name in listOf("0.wav", "1.wav")) {
            val segs = transcribe(asset("stt/$name"))
            val text = segs.joinToString(" ") { it.text }
            val error = wer(reference(name), words(text))
            Log.i("NishuTest", "$name WER=${"%.2f".format(error)} segments=${segs.size} text=$text")
            assertTrue("$name WER $error too high: $text", error <= 0.30)
            segs.zipWithNext().forEach { (a, b) ->
                assertTrue("segments must be ordered and non-overlapping", a.startMs < a.endMs && a.endMs <= b.startMs)
            }
        }
    }

    @Test
    fun silenceProducesNoSegments() {
        val wav = File(app.cacheDir, "silence.wav")
        val w = PcmWriter(wav)
        val chunk = ByteArray(32_000)
        repeat(10) { w.write(chunk, chunk.size) }
        w.finish()
        assertEquals("whisper must not hallucinate on silence", 0, transcribe(wav).size)
    }
}
