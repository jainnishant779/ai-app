package com.nishu.app.stt

import java.io.File

/** [speaker] is 0-based by first appearance, or null when there was no speaker information. */
data class Seg(val startMs: Long, val endMs: Long, val text: String, val speaker: Int? = null)

class SttModelMissing(message: String) : Exception(message)

/** Speech-to-text behind an interface so the model (e.g. a Hinglish one) can be swapped by config. */
interface SttEngine {
    val modelId: String

    /**
     * Transcribes a 16 kHz mono PCM16 WAV, emitting one [Seg] per speech segment in order.
     * Silence produces no segments. Releases all native resources before returning.
     */
    suspend fun transcribe(
        wav: File,
        onProgress: (Float) -> Unit = {},
        speakerTurns: List<SpeakerTurn> = emptyList(),
        onSegment: suspend (Seg) -> Unit,
    )
}
