package com.nishu.app.ui.recording

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.domain.model.RecordingStatus
import com.nishu.app.domain.repo.RecordingRepository
import com.nishu.app.domain.repo.SettingsRepository
import com.nishu.app.ui.components.ConfirmDialog
import com.nishu.app.ui.components.MenuAction
import com.nishu.app.ui.components.OverflowMenu
import com.nishu.app.ui.components.RecordingRadarWaveform
import com.nishu.app.ui.components.RecordingTimer
import com.nishu.app.ui.components.pressScale
import com.nishu.app.ui.formatBytes
import com.nishu.app.ui.formatClock
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuPalette
import com.nishu.app.ui.theme.NishuTheme
import com.nishu.app.ui.vmFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecordingUiState(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val timer: String = "00:00",
    val sizeLabel: String = "0 KB",
    val levels: List<Float> = emptyList(),
    val languageLabel: String = "Hindi / Hinglish",
    val liveTranscriptPreview: String? = null,
) {
    val level: Float get() = levels.lastOrNull() ?: 0f
}

class RecordingViewModel(
    private val repo: RecordingRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<RecordingUiState> = combine(repo.state, settings.info) { rec, info ->
        val langLabel = when (info.languageCode.lowercase()) {
            "english" -> "English"
            "hindi" -> "Hindi"
            else -> "Hindi / Hinglish"
        }
        RecordingUiState(
            status = rec.status,
            timer = formatClock(rec.elapsedMs),
            sizeLabel = formatBytes(rec.bytes),
            levels = rec.levels,
            languageLabel = langLabel,
            liveTranscriptPreview = if (rec.elapsedMs > 2000L) "Real-time speech to text preview..." else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingUiState())

    init {
        if (repo.state.value.status == RecordingStatus.IDLE) {
            viewModelScope.launch { repo.start() }
        }
    }

    fun pauseOrResume() = viewModelScope.launch {
        if (repo.state.value.status == RecordingStatus.PAUSED) repo.resume() else repo.pause()
    }

    fun setLanguage(lang: String) = viewModelScope.launch {
        settings.setLanguage(lang)
    }

    fun stop(onDone: (Long) -> Unit) = viewModelScope.launch {
        onDone(repo.stopAndProcess())
    }

    fun discard(onDone: () -> Unit) = viewModelScope.launch {
        repo.discard()
        onDone()
    }
}

@Composable
fun RecordingRoute(onBack: () -> Unit, onProcessing: (Long) -> Unit) {
    val vm: RecordingViewModel = viewModel(factory = vmFactory { RecordingViewModel(AppGraph.recording, AppGraph.settings) })
    val state by vm.state.collectAsStateWithLifecycle()
    RecordingScreen(
        state = state,
        onBack = onBack,
        onPauseResume = vm::pauseOrResume,
        onStop = { vm.stop(onProcessing) },
        onDiscard = { vm.discard(onBack) },
        onSelectLanguage = vm::setLanguage,
    )
}

@Composable
fun RecordingScreen(
    state: RecordingUiState,
    onBack: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    onSelectLanguage: (String) -> Unit = {},
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    var languageMenuOpen by remember { mutableStateOf(false) }

    val paused = state.status == RecordingStatus.PAUSED

    // Forest green radial/vertical gradient background matching Screen C
    val bgGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF1B5342),
            Color(0xFF144335),
            Color(0xFF0E3026),
            Color(0xFF081E18),
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgGradient),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Dimens.ContentMaxWidth)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top Bar: Back, "Recording", Discard menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenGutter, vertical = 8.dp)
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, Modifier.size(Dimens.MinTouchTarget)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }

                Text(
                    text = "Recording",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )

                OverflowMenu(
                    actions = listOf(
                        MenuAction("Discard recording") { confirmDiscard = true },
                    ),
                    modifier = Modifier.size(Dimens.MinTouchTarget),
                )
            }

            Spacer(Modifier.weight(0.1f))

            // Center Radar ripples & waveform animation
            RecordingRadarWaveform(
                levels = state.levels,
                level = state.level,
                active = !paused,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clickable(onClick = onPauseResume),
            )

            Spacer(Modifier.height(16.dp))

            // Timer display ("00:24" + "Tap to pause")
            RecordingTimer(
                timerText = state.timer,
                subtitle = if (paused) "Paused • Tap to resume" else "Tap to pause",
                textColor = Color.White,
            )

            Spacer(Modifier.height(16.dp))

            // Language selector pill: "Hindi / Hinglish ∨"
            Box {
                Surface(
                    onClick = { languageMenuOpen = true },
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.30f)),
                    modifier = Modifier.height(38.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.languageLabel,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                            color = Color.White,
                        )
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                DropdownMenu(
                    expanded = languageMenuOpen,
                    onDismissRequest = { languageMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Hindi / Hinglish") },
                        onClick = {
                            onSelectLanguage("hinglish")
                            languageMenuOpen = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("English (Meetings)") },
                        onClick = {
                            onSelectLanguage("english")
                            languageMenuOpen = false
                        },
                    )
                }
            }

            Spacer(Modifier.weight(0.2f))

            // Control buttons row: Pause (outline), Stop (large red), Add Mark (outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Pause button
                RecordingActionCircle(
                    icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    label = if (paused) "Resume" else "Pause",
                    onClick = onPauseResume,
                    size = 54.dp,
                )

                // Large Stop button (bright red filled 72dp)
                Surface(
                    onClick = onStop,
                    shape = CircleShape,
                    color = NishuPalette.Danger,
                    modifier = Modifier.size(74.dp),
                    shadowElevation = 8.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // Square stop icon
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White),
                        )
                    }
                }

                // Keeps Stop centred. (An "Add Mark" button stood here, but marks were never saved.)
                Spacer(Modifier.width(54.dp))
            }

            Spacer(Modifier.height(20.dp))

            // Real-time speech preview banner (rounded warm ivory card at bottom)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenGutter)
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(18.dp),
                color = NishuPalette.SurfaceWarm,
                shadowElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE08A2E)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Everything stays on this phone",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = NishuPalette.TextMain,
                        )
                    }
                    Text(
                        text = if (paused) {
                            "Recording is paused. Tap resume to continue."
                        } else {
                            "When you stop, Nishu writes the transcript, summary and tasks here on the phone. Longer recordings take a few minutes."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        color = NishuPalette.TextSecondary,
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard recording?",
            message = "This active recording will be permanently deleted.",
            confirmLabel = "Discard",
            onConfirm = {
                confirmDiscard = false
                onDiscard()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun RecordingActionCircle(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    size: Dp = 54.dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.15f),
            border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.45f)),
            modifier = Modifier.size(size),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

@Preview(widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun RecordingScreenPreview() = NishuTheme(darkTheme = false) {
    RecordingScreen(
        state = RecordingUiState(
            status = RecordingStatus.RECORDING,
            timer = "00:24",
            sizeLabel = "2.4 MB",
            levels = PreviewData.levels,
        ),
        onBack = {},
        onPauseResume = {},
        onStop = {},
        onDiscard = {},
    )
}
