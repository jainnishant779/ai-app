package com.nishu.app.audio

import android.Manifest
import androidx.test.rule.GrantPermissionRule
import com.nishu.app.AppGraph
import com.nishu.app.domain.model.RecordingStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecorderTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
    )

    @Test
    fun recordsPausesResumesAndWritesAValidWav() = runBlocking {
        val repo = AppGraph.recording
        repo.start()
        delay(3_500)
        assertEquals(RecordingStatus.RECORDING, repo.state.value.status)
        assertTrue("levels should be reported", repo.state.value.levels.isNotEmpty())

        repo.pause()
        delay(500)
        val pausedAt = repo.state.value.elapsedMs
        delay(1_500)
        assertEquals(RecordingStatus.PAUSED, repo.state.value.status)
        assertTrue("elapsed must not advance while paused", repo.state.value.elapsedMs - pausedAt < 250)

        repo.resume()
        delay(1_500)
        val id = repo.stopAndProcess()
        assertTrue("stop must return the conversation id", id > 0)

        val conv = AppGraph.database.conversations().get(id)
        assertNotNull(conv)
        assertEquals("RECORDED", conv!!.status)
        val file = File(conv.audioPath!!)
        assertTrue(file.exists())

        val header = ByteBuffer.wrap(file.readBytes(0, 44)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16_000, header.getInt(24))
        assertEquals((file.length() - 44).toInt(), header.getInt(40))
        val ms = WavFile.durationMs(file)
        assertTrue("recorded ${ms}ms, expected about 5000ms (pause excluded)", ms in 4_000..6_500)
        assertEquals(ms, conv.durationMs)
        assertEquals(RecordingStatus.IDLE, repo.state.value.status)

        AppGraph.conversations.delete(id)
        assertTrue(!file.exists())
    }

    private fun File.readBytes(offset: Int, length: Int): ByteArray =
        inputStream().use { it.skip(offset.toLong()); ByteArray(length).also { b -> it.read(b) } }
}
