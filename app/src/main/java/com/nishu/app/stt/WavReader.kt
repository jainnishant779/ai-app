package com.nishu.app.stt

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streams PCM16 mono samples out of a WAV as floats in [-1, 1], locating the `data` chunk properly. */
class WavReader(file: File) : Closeable {
    private val input = BufferedInputStream(file.inputStream(), 64 * 1024)
    private var pos = 0L
    private var remaining = 0L

    var sampleRate = 16_000
        private set
    var totalSamples = 0L
        private set

    init {
        val head = ByteArray(12)
        readFully(head)
        require(String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") { "not a WAV file" }
        while (true) {
            val chunk = ByteArray(8)
            readFully(chunk)
            val id = String(chunk, 0, 4)
            val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(size.toInt())
                    readFully(fmt)
                    val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    require(b.getShort(0).toInt() == 1) { "only PCM is supported" }
                    require(b.getShort(2).toInt() == 1) { "only mono is supported" }
                    require(b.getShort(14).toInt() == 16) { "only 16-bit is supported" }
                    sampleRate = b.getInt(4)
                }
                "data" -> {
                    // A recording cut short may carry a placeholder size; the real file length wins.
                    val available = file.length() - pos
                    remaining = if (size == 0L || size > available) available else size
                    totalSamples = remaining / 2
                    break
                }
                else -> skipFully(size)
            }
        }
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("truncated WAV header")
            off += n
        }
        pos += buf.size
    }

    private fun skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) throw EOFException("truncated WAV")
            left -= n
        }
        pos += count
    }

    /** Reads up to [maxSamples] samples; returns an empty array at the end. */
    fun read(maxSamples: Int): FloatArray {
        val want = minOf(maxSamples.toLong() * 2, remaining).toInt()
        if (want <= 0) return FloatArray(0)
        val bytes = ByteArray(want)
        var off = 0
        while (off < want) {
            val n = input.read(bytes, off, want - off)
            if (n < 0) break
            off += n
        }
        remaining -= off
        val out = FloatArray(off / 2)
        for (i in out.indices) {
            val s = (bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)
            out[i] = s.toShort() / 32768f
        }
        return out
    }

    override fun close() = input.close()
}
