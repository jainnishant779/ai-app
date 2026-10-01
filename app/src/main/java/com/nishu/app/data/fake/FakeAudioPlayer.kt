package com.nishu.app.data.fake

import com.nishu.app.domain.repo.AudioPlayer
import com.nishu.app.domain.repo.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

class FakeAudioPlayer(durationMs: Long = 134_000) : AudioPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(PlayerState(durationMs = durationMs))
    override val state: StateFlow<PlayerState> = _state
    override val envelope: List<Float> = List(120) { i -> (0.2f + 0.8f * abs(sin(i * 0.37f) * sin(i * 0.11f + 1f))).coerceIn(0.1f, 1f) }
    private var job: Job? = null

    override fun play() {
        _state.update { it.copy(isPlaying = true) }
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(100)
                _state.update { s ->
                    val next = s.positionMs + (100 * s.speed).toLong()
                    if (next >= s.durationMs) s.copy(isPlaying = false, positionMs = s.durationMs) else s.copy(positionMs = next)
                }
                if (!_state.value.isPlaying) break
            }
        }
    }

    override fun pause() {
        job?.cancel()
        _state.update { it.copy(isPlaying = false) }
    }

    override fun seekTo(ms: Long) = _state.update { it.copy(positionMs = ms.coerceIn(0, it.durationMs)) }

    override fun cycleSpeed() = _state.update {
        it.copy(speed = when (it.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f })
    }

    override fun release() {
        scope.cancel()
    }
}
