package com.nishu.app.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.domain.model.RecordingStatus
import com.nishu.app.domain.repo.RecordingRepository
import com.nishu.app.ui.components.AudioWaveform
import com.nishu.app.ui.components.BadgeKind
import com.nishu.app.ui.components.ConfirmDialog
import com.nishu.app.ui.components.MenuAction
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.OverflowMenu
import com.nishu.app.ui.components.RecordingOrb
import com.nishu.app.ui.components.StatusBadge
import com.nishu.app.ui.components.nishuCard
import com.nishu.app.ui.formatBytes
import com.nishu.app.ui.formatClock
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuTheme
import com.nishu.app.ui.vmFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecordingUiState(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val timer: String = "00:00",
    val sizeLabel: String = "0 KB",
    val levels: List<Float> = emptyList(),
) {
    val level: Float get() = levels.lastOrNull() ?: 0f
}

class RecordingViewModel(private val repo: RecordingRepository) : ViewModel() {
    val state: StateFlow<RecordingUiState> = repo.state.map {
        RecordingUiState(it.status, formatClock(it.elapsedMs), formatBytes(it.bytes), it.levels)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingUiState())

    init {
        if (repo.state.value.status == RecordingStatus.IDLE) viewModelScope.launch { repo.start() }
    }

    fun pauseOrResume() = viewModelScope.launch {
        if (repo.state.value.status == RecordingStatus.PAUSED) repo.resume() else repo.pause()
    }

    fun stop(onDone: (Long) -> Unit) = viewModelScope.launch { onDone(repo.stopAndProcess()) }
    fun discard(onDone: () -> Unit) = viewModelScope.launch { repo.discard(); onDone() }
}

@Composable
fun RecordingRoute(onBack: () -> Unit, onProcessing: (Long) -> Unit) {
    val vm: RecordingViewModel = viewModel(factory = vmFactory { RecordingViewModel(AppGraph.recording) })
    val state by vm.state.collectAsStateWithLifecycle()
    RecordingScreen(
        state = state,
        onBack = onBack,
        onPauseResume = vm::pauseOrResume,
        onStop = { vm.stop(onProcessing) },
        onDiscard = { vm.discard(onBack) },
    )
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, onClick: () -> Unit, container: Color, content: Color, size: Dp = 56.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(container).clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(size * 0.45f))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RecordingScreen(
    state: RecordingUiState,
    onBack: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val paused = state.status == RecordingStatus.PAUSED
    val scheme = MaterialTheme.colorScheme

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize().navigationBarsPadding()) {
            NishuTopBar(
                title = "Recording...", onBack = onBack,
                actions = { OverflowMenu(listOf(MenuAction("Discard recording") { confirmDiscard = true })) },
            )
            Spacer(Modifier.weight(0.6f))
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenGutter), verticalAlignment = Alignment.CenterVertically) {
                AudioWaveform(
                    levels = state.levels.reversed(), modifier = Modifier.weight(1f).height(56.dp),
                    activeColor = scheme.primary.copy(alpha = if (paused) 0.3f else 0.8f),
                )
                RecordingOrb(level = state.level, active = !paused, size = 140.dp)
                AudioWaveform(
                    levels = state.levels, modifier = Modifier.weight(1f).height(56.dp),
                    activeColor = scheme.primary.copy(alpha = if (paused) 0.3f else 0.8f),
                )
            }
            Text(state.timer, style = MaterialTheme.typography.displaySmall, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
                if (paused) StatusBadge("Paused", BadgeKind.WARNING) else StatusBadge("● Listening...", BadgeKind.SUCCESS)
            }
            Text(
                state.sizeLabel, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(0.5f))
            Column(
                Modifier.padding(horizontal = Dimens.ScreenGutter).fillMaxWidth().nishuCard().padding(Dimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Transcript", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    StatusBadge("On-device", BadgeKind.INFO)
                }
                Text(
                    "Your transcript will appear after you stop. Nishu transcribes on-device.",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                ControlButton(
                    if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Resume" else "Pause",
                    onPauseResume, scheme.primaryContainer, scheme.primary,
                )
                ControlButton(Icons.Rounded.Stop, "Stop", onStop, scheme.error, scheme.onError, size = 72.dp)
                ControlButton(Icons.Rounded.Check, "Save", onStop, scheme.primaryContainer, scheme.primary)
            }
        }
    }
    if (confirmDiscard) {
        ConfirmDialog("Discard recording?", "The audio will be deleted.", "Discard", { confirmDiscard = false; onDiscard() }, { confirmDiscard = false })
    }
}

@Preview(widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun RecordingScreenPreview() = NishuTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
        RecordingScreen(RecordingUiState(RecordingStatus.RECORDING, "02:14", "4.2 MB", PreviewData.levels), {}, {}, {}, {})
    }
}
