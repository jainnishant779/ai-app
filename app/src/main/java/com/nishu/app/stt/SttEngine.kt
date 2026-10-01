package com.nishu.app.stt

import java.io.File

data class Seg(val startMs: Long, val endMs: Long, val text: String)

class SttModelMissing(message: String) : Exception(message)

/** Speech-to-text behind an interface so the model (e.g. a Hinglish one) can be swapped by config. */
interface SttEngine {
    val modelId: String

    /**
     * Transcribes a 16 kHz mono PCM16 WAV, emitting one [Seg] per speech segment in order.
     * Silence produces no segments. Releases all native resources before returning.
     */
    suspend fun transcribe(wav: File, onProgress: (Float) -> Unit = {}, onSegment: suspend (Seg) -> Unit)
}
