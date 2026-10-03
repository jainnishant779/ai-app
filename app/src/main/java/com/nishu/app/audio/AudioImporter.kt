package com.nishu.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder

class ImportFailed(message: String) : Exception(message)

/**
 * Turns any audio file Android can decode (mp3, m4a/aac, ogg/opus, flac, wav, amr) into the 16 kHz mono PCM16 WAV
 * the pipeline reads. Copying the original bytes under a .wav name is not enough: the recognizer only reads PCM.
 */
object AudioImporter {
    private const val TIMEOUT_US = 10_000L

    fun import(context: Context, uri: Uri, dest: File) {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { extractor.setDataSource(it.fileDescriptor) }
                ?: throw ImportFailed("Could not open the file")
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw ImportFailed("This file has no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            decode(extractor, format, mime, dest)
        } catch (e: ImportFailed) {
            dest.delete()
            throw e
        } catch (e: Exception) {
            dest.delete()
            throw ImportFailed("This audio format could not be decoded (${e.javaClass.simpleName})")
        } finally {
            extractor.release()
        }
        if (WavFile.durationMs(dest) < 500) {
            dest.delete()
            throw ImportFailed("The file contains no audio")
        }
    }

    private fun decode(extractor: MediaExtractor, format: MediaFormat, mime: String, dest: File) {
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val writer = PcmWriter(dest)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var floatPcm = false
        var resampler = Resampler(rate)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The decoder's real output can differ from the container's claim (e.g. HE-AAC doubles the rate).
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler = Resampler(rate)
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val samples = if (floatPcm) {
                            FloatArray(info.size / 4).also { buf.asFloatBuffer().get(it) }
                        } else {
                            val s = ShortArray(info.size / 2).also { buf.asShortBuffer().get(it) }
                            FloatArray(s.size) { s[it] / 32768f }
                        }
                        codec.releaseOutputBuffer(o, false)
                        val pcm = resampler.process(Resampler.downmix(samples, channels))
                        val bytes = ByteArray(pcm.size * 2)
                        for (k in pcm.indices) {
                            val v = (pcm[k].coerceIn(-1f, 1f) * 32767f).toInt()
                            bytes[2 * k] = v.toByte()
                            bytes[2 * k + 1] = (v shr 8).toByte()
                        }
                        writer.write(bytes, bytes.size)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            writer.finish()
            codec.stop()
            codec.release()
        }
    }
}
