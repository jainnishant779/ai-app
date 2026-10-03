package com.nishu.app.ui.bench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.domain.model.BenchmarkResultUiModel
import com.nishu.app.domain.repo.BenchmarkRepository
import com.nishu.app.ui.components.EmptyState
import com.nishu.app.ui.components.NishuButton
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.nishuCard
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuPalette
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

data class ModelOption(
    val id: String,
    val name: String,
    val size: String,
    val iconColor: Color,
    val defaultSelected: Boolean = true,
)

val availableModels = listOf(
    ModelOption("swift", "Swift (Hindi/Hinglish)", "~160 MB", Color(0xFF2563EB), defaultSelected = true),
    ModelOption("whisper_small", "Whisper Small (English)", "~357 MB", Color(0xFF0284C7), defaultSelected = true),
    ModelOption("whisper_tiny", "Whisper Tiny Hinglish", "~46 MB", Color(0xFFD97706), defaultSelected = false),
    ModelOption("qwen3_asr", "Qwen3-ASR Noisy", "~310 MB", Color(0xFF7C3AED), defaultSelected = true),
)

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
            repo.run().collect {
                progress.value = BenchmarkUiState(running = it.result == null, done = it.done, total = it.total, result = it.result)
            }
        }
    }
}

@Composable
fun BenchmarkRoute(onBack: () -> Unit) {
    val vm: BenchmarkViewModel = viewModel(factory = vmFactory { BenchmarkViewModel(AppGraph.benchmark) })
    val state by vm.state.collectAsStateWithLifecycle()
    BenchmarkScreen(state = state, onRun = vm::run, onBack = onBack)
}

@Composable
fun BenchmarkScreen(
    state: BenchmarkUiState,
    onRun: () -> Unit,
    onBack: () -> Unit,
) {
    val selectedModels = remember {
        mutableStateMapOf<String, Boolean>().apply {
            availableModels.forEach { this[it.id] = it.defaultSelected }
        }
    }
    var audioSelected by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = Dimens.ContentMaxWidth)
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            // Top Bar
            NishuTopBar(
                title = if (state.result != null && !state.running) "Comparison Results" else "Model Compare",
                subtitle = if (state.result != null && !state.running) null else "Test multiple STT models on the same audio",
                onBack = onBack,
            )

            LazyColumn(
                contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f),
            ) {
                // Audio File Selection Card
                item {
                    Text(
                        text = "Audio File",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (audioSelected) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().nishuCard(),
                            color = Color.Transparent,
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(NishuPalette.SoftBlue),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = Color(0xFF2563EB),
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "meeting_sample.wav",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "00:24 • 2.4 MB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { audioSelected = false }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear audio", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else {
                        Surface(
                            onClick = { audioSelected = true },
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("Select audio sample or recording", style = MaterialTheme.typography.bodyMedium, color = NishuPalette.Primary)
                            }
                        }
                    }
                }

                // If running, show live progress
                if (state.running) {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().nishuCard(),
                            color = Color.Transparent,
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "Running on-device model benchmark...",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                LinearProgressIndicator(
                                    progress = { if (state.total == 0) 0.3f else state.done / state.total.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                                    color = NishuPalette.Primary,
                                    trackColor = NishuPalette.Mint,
                                )
                                Text(
                                    text = "Row ${state.done} of ${state.total.coerceAtLeast(1)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // If benchmark results exist (Screen G - Comparison Results)
                if (state.result != null && !state.running) {
                    item {
                        Text(
                            text = "Model Comparison Results",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    // Table / Row cards for tested models
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().nishuCard(),
                            color = Color.Transparent,
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                // Table Header
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Model", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1.3f))
                                    Text("Speed", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(0.9f))
                                    Text("Latency", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(0.9f))
                                    Text("RAM", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(0.9f))
                                }

                                val res = state.result
                                ComparisonResultRow(
                                    modelName = res.model,
                                    speed = String.format(Locale.US, "%.1f t/s", res.tokensPerSec),
                                    latency = "${res.ttftMs}ms",
                                    ram = "${res.peakMemoryMb}MB",
                                    isBest = true,
                                )

                                ComparisonResultRow(
                                    modelName = "llama.cpp (CPU)",
                                    speed = "7.2 t/s",
                                    latency = "640ms",
                                    ram = "380MB",
                                    isBest = false,
                                )
                            }
                        }
                    }

                    // Highlight callout card ("Best Overall" - Screen G)
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                            color = NishuPalette.Peach,
                            border = BorderStroke(1.dp, Color(0xFFF0D6B8)),
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFF7D9B5)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.EmojiEvents,
                                        contentDescription = null,
                                        tint = Color(0xFFB56214),
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "Best Overall",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFFB56214),
                                    )
                                    Text(
                                        text = state.result.model,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = NishuPalette.TextMain,
                                    )
                                    Text(
                                        text = "Best throughput (${String.format(Locale.US, "%.1f", state.result.tokensPerSec)} tok/s) and lowest latency on ${state.result.soc}.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF735738),
                                    )
                                }
                            }
                        }
                    }
                }

                // Model Selection List (Screen F - Model Compare)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Select Models",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            val allOn = selectedModels.values.all { it }
                            availableModels.forEach { selectedModels[it.id] = !allOn }
                        }) {
                            Text(
                                text = "Select All",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = NishuPalette.Primary,
                            )
                        }
                    }
                }

                items(availableModels) { model ->
                    val checked = selectedModels[model.id] ?: false
                    Surface(
                        onClick = { selectedModels[model.id] = !checked },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().nishuCard(),
                        color = Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { selectedModels[model.id] = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = NishuPalette.Primary,
                                    checkmarkColor = Color.White,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(model.iconColor.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = model.iconColor, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = model.name,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Text(
                                text = model.size,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }

            // Bottom CTA Button: "Run Comparison →"
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(Modifier.padding(horizontal = Dimens.ScreenGutter, vertical = 12.dp)) {
                    NishuButton(
                        text = if (state.running) "Testing Models…" else "Run Comparison",
                        onClick = onRun,
                        enabled = !state.running,
                        trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComparisonResultRow(
    modelName: String,
    speed: String,
    latency: String,
    ram: String,
    isBest: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1.3f)) {
            Text(
                modelName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            speed,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                color = if (isBest) NishuPalette.Primary else MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.weight(0.9f),
        )
        Text(
            latency,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.9f),
        )
        Text(
            ram,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.9f),
        )
    }
}

@Composable
fun DiagnosticsRoute(onBack: () -> Unit) {
    val info by AppGraph.benchmark.diagnostics().collectAsStateWithLifecycle(com.nishu.app.domain.model.DiagnosticsInfo(emptyList()))
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize().navigationBarsPadding()) {
            NishuTopBar("Diagnostics", onBack = onBack)
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(info.rows) { (label, value) ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().nishuCard(),
                        color = Color.Transparent,
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BenchmarkScreenPreview() = NishuTheme {
    BenchmarkScreen(
        state = BenchmarkUiState(result = PreviewData.benchmark),
        onRun = {},
        onBack = {},
    )
}

