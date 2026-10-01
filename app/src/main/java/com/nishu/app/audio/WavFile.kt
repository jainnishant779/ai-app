package com.nishu.app.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono PCM16 WAV helpers. The format matches sherpa-onnx natively, so nothing is ever resampled. */
object WavFile {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    const val HEADER_SIZE = 44

    fun header(dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        b.putInt(SAMPLE_RATE).putInt(BYTES_PER_SECOND).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }

    /** Rewrites the RIFF and data sizes from the real file length. Safe to call on an orphaned recording. */
    fun patchHeader(file: File) {
        if (file.length() < HEADER_SIZE) return
        val data = file.length() - HEADER_SIZE
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(header(data)) }
    }

    fun durationMs(file: File): Long =
        ((file.length() - HEADER_SIZE).coerceAtLeast(0)) * 1000 / BYTES_PER_SECOND
}

/** Appends PCM live so that a killed process loses at most the unflushed tail, never the file. */
class PcmWriter(private val file: File) {
    private val out = FileOutputStream(file).also { it.write(WavFile.header(0)) }
    var bytesWritten: Long = 0
        private set

    fun write(buffer: ByteArray, length: Int) {
        out.write(buffer, 0, length)
        bytesWritten += length
    }

    fun flush() = out.flush()

    fun finish() {
        out.flush()
        out.close()
        WavFile.patchHeader(file)
    }
}
