package com.nishu.app.ui.transcript

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.domain.model.TranscriptLine
import com.nishu.app.domain.repo.AudioPlayer
import com.nishu.app.domain.repo.ConversationRepository
import com.nishu.app.domain.repo.MemoryRepository
import com.nishu.app.domain.repo.PlayerState
import com.nishu.app.ui.components.AudioWaveform
import com.nishu.app.ui.components.EmptyState
import com.nishu.app.ui.components.NishuSearchBar
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.formatClock
import com.nishu.app.ui.highlight
import com.nishu.app.ui.memory.AddFactSheet
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuTheme
import com.nishu.app.ui.vmFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TranscriptUiState(
    val query: String = "",
    val lines: List<TranscriptLine> = emptyList(),
    val hasAudio: Boolean = false,
)

class TranscriptViewModel(
    private val id: Long,
    conversations: ConversationRepository,
    private val memory: MemoryRepository,
    val player: AudioPlayer,
) : ViewModel() {
    private val query = MutableStateFlow("")

    val state: StateFlow<TranscriptUiState> = combine(conversations.transcript(id), query, conversations.detail(id)) { lines, q, detail ->
        TranscriptUiState(q, if (q.isBlank()) lines else lines.filter { it.text.contains(q.trim(), ignoreCase = true) }, detail?.hasAudio == true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscriptUiState())

    fun setQuery(q: String) { query.value = q }
    fun saveToMemory(text: String, kind: com.nishu.app.domain.model.MemoryKind, startMs: Long?) =
        viewModelScope.launch { memory.add(text, kind, id, startMs) }

    override fun onCleared() = player.release()
}

@Composable
fun TranscriptRoute(id: Long, onBack: () -> Unit) {
    val vm: TranscriptViewModel = viewModel(
        key = "transcript_$id",
        factory = vmFactory { TranscriptViewModel(id, AppGraph.conversations, AppGraph.memory, AppGraph.newPlayer(id)) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.player.pause() } }
    TranscriptScreen(
        state = state, player = player, envelope = vm.player.envelope, onBack = onBack,
        onQuery = vm::setQuery, onSeek = vm.player::seekTo, onPlayPause = { if (player.isPlaying) vm.player.pause() else vm.player.play() },
        onSpeed = vm.player::cycleSpeed, onSave = vm::saveToMemory,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptScreen(
    state: TranscriptUiState,
    player: PlayerState,
    envelope: List<Float>,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onSpeed: () -> Unit,
    onSave: (String, com.nishu.app.domain.model.MemoryKind, Long?) -> Unit,
) {
    var saving by remember { mutableStateOf<TranscriptLine?>(null) }
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize()) {
            NishuTopBar("Full Transcript", onBack = onBack)
            NishuSearchBar(state.query, onQuery, "Search in transcript...", Modifier.padding(horizontal = Dimens.ScreenGutter))
            if (state.lines.isEmpty()) {
                EmptyState(Icons.AutoMirrored.Rounded.Subject, "No matching lines", "Try a different word.")
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.lines, key = { it.startMs }) { line ->
                    val active = state.hasAudio && player.positionMs >= line.startMs &&
                        state.lines.firstOrNull { it.startMs > line.startMs }?.let { player.positionMs < it.startMs } != false
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(if (active && player.isPlaying) highlightColor.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent)
                            .combinedClickable(onClick = { onSeek(line.startMs) }, onLongClick = { saving = line })
                            .padding(8.dp),
                    ) {
                        Text(
                            formatClock(line.startMs), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp),
                        )
                        Text(highlight(line.text, state.query, highlightColor), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (state.hasAudio) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Dimens.ScreenGutter, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(role = Role.Button, onClick = onPlayPause),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (player.isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    AudioWaveform(
                        envelope, Modifier.weight(1f).height(36.dp).padding(horizontal = 12.dp),
                        progress = if (player.durationMs > 0) player.positionMs / player.durationMs.toFloat() else 0f,
                    )
                    Surface(
                        onClick = onSpeed, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.height(36.dp),
                    ) {
                        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Text("${player.speed.toString().removeSuffix(".0")}x", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
    saving?.let { line ->
        AddFactSheet(line.text, { text, kind -> onSave(text, kind, line.startMs); saving = null }, { saving = null })
    }
}

@Preview(widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun TranscriptPreview() = NishuTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
        TranscriptScreen(
            TranscriptUiState("", PreviewData.transcript, true), PlayerState(false, 30_000, 134_000), PreviewData.levels,
            {}, {}, {}, {}, {}, { _, _, _ -> },
        )
    }
}
