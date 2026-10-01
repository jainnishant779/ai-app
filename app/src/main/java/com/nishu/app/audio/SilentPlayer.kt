package com.nishu.app.audio

import com.nishu.app.domain.repo.AudioPlayer
import com.nishu.app.domain.repo.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stand-in for conversations whose audio was deleted or never existed. */
class SilentPlayer : AudioPlayer {
    override val state: StateFlow<PlayerState> = MutableStateFlow(PlayerState())
    override val envelope: StateFlow<List<Float>> = MutableStateFlow(emptyList())
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(ms: Long) = Unit
    override fun cycleSpeed() = Unit
    override fun release() = Unit
}
