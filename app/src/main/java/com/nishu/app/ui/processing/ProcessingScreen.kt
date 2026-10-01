package com.nishu.app.ui.processing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.domain.model.ProcessingStage
import com.nishu.app.domain.model.StepState
import com.nishu.app.domain.repo.ConversationRepository
import com.nishu.app.ui.components.NishuRobot
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.PrimaryButton
import com.nishu.app.ui.components.ProcessingStep
import com.nishu.app.ui.components.nishuCard
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuTheme
import com.nishu.app.ui.vmFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProcessingUiState(
    val steps: List<Pair<ProcessingStage, StepState>> = emptyList(),
) {
    val isComplete: Boolean get() = steps.isNotEmpty() && steps.all { it.second == StepState.COMPLETED }
    val hasFailed: Boolean get() = steps.any { it.second == StepState.FAILED }
}

private fun label(stage: ProcessingStage) = when (stage) {
    ProcessingStage.TRANSCRIBING -> "Transcribing audio..."
    ProcessingStage.SUMMARIZING -> "Generating summary..."
    ProcessingStage.EXTRACTING_TASKS -> "Extracting tasks..."
    ProcessingStage.IDENTIFYING_DECISIONS -> "Identifying decisions..."
    ProcessingStage.SAVING -> "Saving to memory..."
}

class ProcessingViewModel(private val id: Long, private val repo: ConversationRepository) : ViewModel() {
    val state: StateFlow<ProcessingUiState> = repo.processing(id)
        .map { m -> ProcessingUiState(ProcessingStage.entries.map { it to (m[it] ?: StepState.PENDING) }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingUiState())

    fun cancel(onDone: () -> Unit) = viewModelScope.launch { repo.cancelProcessing(id); onDone() }
}

@Composable
fun ProcessingRoute(id: Long, onBack: () -> Unit, onComplete: (Long) -> Unit, onViewTranscript: (Long) -> Unit) {
    val vm: ProcessingViewModel = viewModel(key = "processing_$id", factory = vmFactory { ProcessingViewModel(id, AppGraph.conversations) })
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isComplete) { if (state.isComplete) onComplete(id) }
    ProcessingScreen(state, onBack, onCancel = { vm.cancel(onBack) }, onViewTranscript = { onViewTranscript(id) })
}

@Composable
fun ProcessingScreen(state: ProcessingUiState, onBack: () -> Unit, onCancel: () -> Unit, onViewTranscript: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize()) {
            NishuTopBar("Processing", onBack = onBack)
            Column(
                Modifier.padding(horizontal = Dimens.ScreenGutter).fillMaxWidth().nishuCard().padding(vertical = 8.dp, horizontal = 4.dp),
            ) {
                state.steps.forEach { (stage, s) -> ProcessingStep(label(stage), s) }
            }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.hasFailed) {
                    Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Transcript saved — summary unavailable",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    PrimaryButton("View transcript", onViewTranscript)
                } else {
                    NishuRobot()
                    Text("Nishu is working...", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Processing on your device. This may take a few minutes.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    Text(
                        "You can leave this screen — we'll notify you.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onCancel) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Preview(widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun ProcessingScreenPreview() = NishuTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
        ProcessingScreen(ProcessingUiState(PreviewData.steps.toList()), {}, {}, {})
    }
}
