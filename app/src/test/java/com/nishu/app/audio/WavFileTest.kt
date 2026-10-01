package com.nishu.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileTest {
    @Test
    fun headerDescribes16kMonoPcm16() {
        val b = ByteBuffer.wrap(WavFile.header(32_000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, b.capacity())
        assertEquals(32_036, b.getInt(4))
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(32_000, b.getInt(40))
    }

    @Test
    fun writerPatchesSizesAndDurationFromRealLength() {
        val f = File.createTempFile("pcm", ".wav")
        val w = PcmWriter(f)
        w.write(ByteArray(WavFile.BYTES_PER_SECOND * 3), WavFile.BYTES_PER_SECOND * 3)
        w.finish()
        assertEquals(44L + 96_000, f.length())
        assertEquals(3_000, WavFile.durationMs(f))
        assertEquals(96_000, ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        f.delete()
    }

    @Test
    fun orphanedFileWithPlaceholderHeaderIsRecovered() {
        val f = File.createTempFile("orphan", ".wav")
        f.writeBytes(WavFile.header(0) + ByteArray(64_000))
        WavFile.patchHeader(f)
        assertEquals(64_000, ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        assertEquals(2_000, WavFile.durationMs(f))
        f.delete()
    }
}
