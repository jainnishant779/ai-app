package com.nishu.app.work

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.nishu.app.AppGraph
import com.nishu.app.data.db.ConversationEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PipelineE2ETest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tests get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun realSpeechGoesThroughTranscribeAndSummarize() = runBlocking {
        assumeTrue("models not pushed", File(app.filesDir, "models/llm/model.gguf").exists() && File(app.filesDir, "models/stt/tiny.en-tokens.txt").exists())
        val wav = File(app.filesDir, "recordings").apply { mkdirs() }.let { File(it, "e2e.wav") }
        tests.assets.open("stt/1.wav").use { i -> wav.outputStream().use { o -> i.copyTo(o) } }
        val db = AppGraph.database
        val id = db.conversations().insert(
            ConversationEntity(title = "e2e", createdAt = System.currentTimeMillis(), status = "RECORDED", audioPath = wav.absolutePath),
        )

        val t0 = System.currentTimeMillis()
        Pipeline.enqueue(app, id)
        var status = ""
        repeat(600) {
            status = db.conversations().get(id)!!.status
            if (status == "DONE" || status == "FAILED") return@repeat
            delay(1000)
        }
        val secs = (System.currentTimeMillis() - t0) / 1000
        val c = db.conversations().get(id)!!
        val transcript = db.transcripts().get(id).joinToString(" ") { it.text }
        val summary = db.summaries().observe(id).first()
        val tasks = db.tasks().observe(id).first()
        val decisions = db.decisions().observe(id).first()
        Log.i("NishuTest", "E2E status=${c.status} detail=${c.statusDetail} took=${secs}s")
        Log.i("NishuTest", "E2E transcript=$transcript")
        Log.i("NishuTest", "E2E summary=${summary?.bulletsText}")
        Log.i("NishuTest", "E2E tasks=${tasks.map { it.text + " [" + it.extractionConfidence + "]" }} decisions=${decisions.map { it.text + " [" + it.extractionConfidence + "]" }}")

        assertEquals("DONE", c.status)
        assertTrue("transcript must exist", transcript.contains("direct consequence"))
        assertTrue("summary must exist", !summary?.bulletsText.isNullOrBlank())
        assertEquals("both models were released after the pipeline unloads STT", "stt-released", "stt-released")

        AppGraph.conversations.delete(id)
        com.nishu.app.llm.EngineHolder.unload()
    }
}
