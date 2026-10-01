package com.nishu.app.audio

import android.media.MediaPlayer
import android.media.PlaybackParams
import com.nishu.app.domain.repo.AudioPlayer
import com.nishu.app.domain.repo.PlayerState
import com.nishu.app.stt.WavReader
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
import java.io.File
import kotlin.math.abs

/** MediaPlayer over the recorded WAV. The waveform envelope is computed once in the background and cached. */
class RealAudioPlayer(private val file: File) : AudioPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player = MediaPlayer()
    private val _state = MutableStateFlow(PlayerState())
    private val _envelope = MutableStateFlow<List<Float>>(emptyList())
    private var ticker: Job? = null
    private var prepared = false

    override val state: StateFlow<PlayerState> = _state
    override val envelope: StateFlow<List<Float>> = _envelope

    init {
        runCatching {
            player.setDataSource(file.absolutePath)
            player.prepare()
            prepared = true
            _state.value = PlayerState(durationMs = player.duration.toLong())
            player.setOnCompletionListener {
                ticker?.cancel()
                _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
            }
        }
        scope.launch(Dispatchers.IO) { _envelope.value = loadEnvelope(file) }
    }

    override fun play() {
        if (!prepared) return
        if (_state.value.positionMs >= _state.value.durationMs) player.seekTo(0)
        applySpeed()
        player.start()
        _state.update { it.copy(isPlaying = true) }
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                _state.update { it.copy(positionMs = player.currentPosition.toLong()) }
                delay(100)
            }
        }
    }

    override fun pause() {
        if (!prepared) return
        ticker?.cancel()
        if (player.isPlaying) player.pause()
        _state.update { it.copy(isPlaying = false, positionMs = player.currentPosition.toLong()) }
    }

    override fun seekTo(ms: Long) {
        if (!prepared) return
        player.seekTo(ms.toInt().coerceIn(0, player.duration))
        _state.update { it.copy(positionMs = ms.coerceIn(0, it.durationMs)) }
    }

    override fun cycleSpeed() {
        _state.update { it.copy(speed = when (it.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }) }
        if (_state.value.isPlaying) applySpeed()
    }

    private fun applySpeed() {
        // Setting speed on a stopped MediaPlayer starts it, so only touch it while playing or right before start().
        runCatching { player.playbackParams = PlaybackParams().setSpeed(_state.value.speed) }
        if (!_state.value.isPlaying) player.pause()
    }

    override fun release() {
        ticker?.cancel()
        scope.cancel()
        runCatching { player.release() }
    }

    companion object {
        const val BARS = 120

        /** Peak amplitude per bar, normalized to the loudest bar. Cached beside the WAV as `<name>.env`. */
        fun loadEnvelope(wav: File): List<Float> {
            val cache = File(wav.parentFile, wav.nameWithoutExtension + ".env")
            if (cache.exists() && cache.length() == BARS.toLong()) return cache.readBytes().map { (it.toInt() and 0xFF) / 255f }
            val bars = computeEnvelope(wav)
            if (bars.isNotEmpty()) runCatching { cache.writeBytes(ByteArray(BARS) { (bars[it] * 255).toInt().toByte() }) }
            return bars
        }

        fun computeEnvelope(wav: File): List<Float> = runCatching {
            WavReader(wav).use { reader ->
                val total = reader.totalSamples
                if (total <= 0) return emptyList()
                val perBar = (total / BARS).coerceAtLeast(1)
                val peaks = FloatArray(BARS)
                var index = 0L
                while (true) {
                    val samples = reader.read(4096)
                    if (samples.isEmpty()) break
                    for (s in samples) {
                        val bar = (index / perBar).toInt().coerceAtMost(BARS - 1)
                        peaks[bar] = maxOf(peaks[bar], abs(s))
                        index++
                    }
                }
                val max = peaks.max().coerceAtLeast(0.01f)
                peaks.map { (it / max).coerceIn(0.05f, 1f) }
            }
        }.getOrDefault(emptyList())
    }
}
