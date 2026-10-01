package com.nishu.app.domain.repo

import kotlinx.coroutines.flow.StateFlow

data class PlayerState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)

interface AudioPlayer {
    val state: StateFlow<PlayerState>
    /** Bar heights in 0..1 for the waveform; empty when unknown. */
    val envelope: List<Float>
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun cycleSpeed()
    fun release()
}
