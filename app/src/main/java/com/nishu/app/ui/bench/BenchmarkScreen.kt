package com.nishu.app.ui.bench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.domain.model.BenchmarkResultUiModel
import com.nishu.app.domain.model.DiagnosticsInfo
import com.nishu.app.domain.repo.BenchmarkRepository
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.PrimaryButton
import com.nishu.app.ui.components.nishuCard
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuTheme
import com.nishu.app.ui.vmFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

data class BenchmarkUiState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val result: BenchmarkResultUiModel? = null,
)

class BenchmarkViewModel(private val repo: BenchmarkRepository) : ViewModel() {
    private val progress = MutableStateFlow(BenchmarkUiState())
    private var job: Job? = null

    val state: StateFlow<BenchmarkUiState> = combine(progress, repo.last()) { p, last ->
        p.copy(result = last)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BenchmarkUiState())

    fun run() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            progress.value = BenchmarkUiState(running = true)
            repo.run().collect { progress.value = BenchmarkUiState(running = it.result == null, done = it.done, total = it.total) }
        }
    }
}

@Composable
fun BenchmarkRoute(onBack: () -> Unit) {
    val vm: BenchmarkViewModel = viewModel(factory = vmFactory { BenchmarkViewModel(AppGraph.benchmark) })
    val state by vm.state.collectAsStateWithLifecycle()
    BenchmarkScreen(state, vm::run, onBack)
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End)
    }
}

@Composable
fun BenchmarkScreen(state: BenchmarkUiState, onRun: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize()) {
            NishuTopBar("Benchmark", onBack = onBack)
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text("  Test model performance on your device", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            text = if (state.running) "Running…" else "Run Benchmark",
                            onClick = onRun, icon = Icons.Rounded.PlayArrow, enabled = !state.running,
                        )
                        if (state.running) {
                            LinearProgressIndicator(
                                progress = { if (state.total == 0) 0f else state.done / state.total.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("Row ${state.done} of ${state.total}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                state.result?.let { r ->
                    item { Text("Results (Last Run)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                    item {
                        Column(Modifier.fillMaxWidth().nishuCard().padding(horizontal = Dimens.CardPadding, vertical = 8.dp)) {
                            ResultRow("Model", r.model)
                            ResultRow("Runtime", r.runtime)
                            ResultRow("SoC", r.soc)
                            ResultRow("TTFT", "${r.ttftMs} ms")
                            ResultRow("Tokens / sec", String.format(Locale.US, "%.1f", r.tokensPerSec))
                            ResultRow("Peak Memory", "${r.peakMemoryMb} MB")
                            ResultRow("Avg Power", r.avgPowerW?.let { String.format(Locale.US, "~%.1f W", it) } ?: "Unplug to measure")
                            ResultRow("JSON Success", "${r.jsonSuccessPct}%")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsRoute(onBack: () -> Unit) {
    val info by AppGraph.benchmark.diagnostics().collectAsStateWithLifecycle(DiagnosticsInfo(emptyList()))
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize()) {
            NishuTopBar("Diagnostics", onBack = onBack)
            Column(Modifier.padding(horizontal = Dimens.ScreenGutter).fillMaxWidth().nishuCard().padding(Dimens.CardPadding)) {
                info.rows.forEach { (k, v) -> ResultRow(k, v) }
            }
        }
    }
}

@Preview(widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun BenchmarkPreview() = NishuTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
        BenchmarkScreen(BenchmarkUiState(result = PreviewData.benchmark), {}, {})
    }
}
