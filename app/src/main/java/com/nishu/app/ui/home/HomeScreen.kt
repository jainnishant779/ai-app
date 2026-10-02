package com.nishu.app.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.domain.model.ConversationUiModel
import com.nishu.app.domain.model.RecordingStatus
import com.nishu.app.domain.repo.ConversationRepository
import com.nishu.app.domain.repo.RecordingRepository
import com.nishu.app.domain.repo.SettingsRepository
import com.nishu.app.ui.components.ConversationCard
import com.nishu.app.ui.components.NishuFilterChip
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.NishuIconButton
import com.nishu.app.ui.components.PrimaryButton
import com.nishu.app.ui.components.SectionHeader
import com.nishu.app.ui.components.StatCard
import com.nishu.app.ui.formatClock
import com.nishu.app.ui.greetingFor
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuTheme
import com.nishu.app.ui.vmFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val greeting: String = "",
    val recordingCount: Int = 0,
    val taskCount: Int = 0,
    val memoryCount: Int = 0,
    val recentConversations: List<ConversationUiModel> = emptyList(),
    val isRecording: Boolean = false,
    val recordingElapsed: String? = null,
    val languageCode: String = "hinglish",
    val sttLabel: String = "Whisper Hinglish (base)",
)

class HomeViewModel(
    conversations: ConversationRepository,
    recording: RecordingRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<HomeUiState> = combine(
        settings.info, conversations.counts(), conversations.recent(3), recording.state,
    ) { info, counts, recent, rec ->
        HomeUiState(
            greeting = greetingFor(info.userName),
            recordingCount = counts.recordings,
            taskCount = counts.openTasks,
            memoryCount = counts.facts,
            recentConversations = recent,
            isRecording = rec.status != RecordingStatus.IDLE,
            recordingElapsed = if (rec.status != RecordingStatus.IDLE) formatClock(rec.elapsedMs) else null,
            languageCode = info.languageCode,
            sttLabel = info.sttLabel,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setLanguage(lang: String) = viewModelScope.launch {
        settings.setLanguage(lang)
    }
}

@Composable
fun HomeRoute(
    onStartRecording: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onSeeAll: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: HomeViewModel = viewModel(
        factory = vmFactory { HomeViewModel(AppGraph.conversations, AppGraph.recording, AppGraph.settings) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) onStartRecording()
    }
    HomeScreen(
        state = state,
        onStartRecording = { if (state.isRecording) onStartRecording() else launcher.launch(permissions) },
        onOpenConversation = onOpenConversation,
        onSeeAll = onSeeAll,
        onOpenSettings = onOpenSettings,
        onSetLanguage = vm::setLanguage,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onStartRecording: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onSeeAll: () -> Unit,
    onOpenSettings: () -> Unit,
    onSetLanguage: (String) -> Unit = {},
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize()) {
            NishuTopBar(
                title = "Nishu",
                subtitle = "Your personal memory assistant",
                brand = true,
                actions = { NishuIconButton(Icons.Rounded.Settings, "Settings", onOpenSettings) },
            )
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
            ) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .background(
                                Brush.linearGradient(
                                    listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface),
                                ),
                            )
                            .padding(20.dp),
                    ) {
                        Text(state.greeting, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "I'm ready to listen, remember and help you.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NishuFilterChip(
                                label = "Hinglish (Chat)",
                                selected = state.languageCode == "hinglish",
                                onClick = { onSetLanguage("hinglish") },
                            )
                            NishuFilterChip(
                                label = "English (Meetings)",
                                selected = state.languageCode == "english",
                                onClick = { onSetLanguage("english") },
                            )
                        }
                        PrimaryButton(
                            text = if (state.isRecording) "Recording ${state.recordingElapsed} • Tap to open" else "Start Recording",
                            onClick = onStartRecording,
                            icon = Icons.Rounded.Mic,
                        )
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(Dimens.ItemGap)) {
                        StatCard(state.recordingCount, "Recordings", Modifier.weight(1f))
                        StatCard(state.taskCount, "Tasks", Modifier.weight(1f))
                        StatCard(state.memoryCount, "Key Facts", Modifier.weight(1f))
                    }
                }
                item {
                    SectionHeader("Recent Conversations", Modifier.padding(top = 12.dp), actionLabel = "See all", onAction = onSeeAll)
                }
                items(state.recentConversations, key = { it.id }) { c ->
                    ConversationCard(c, onClick = { onOpenConversation(c.id) })
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Preview(widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun HomeScreenPreview() = NishuTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
        HomeScreen(
            HomeUiState("Good morning, Nishant!", 12, 28, 15, PreviewData.conversations.take(3)),
            {}, {}, {}, {},
        )
    }
}

@Preview(widthDp = 411, heightDp = 780, showBackground = true)
@Composable
private fun HomeScreenDarkPreview() = NishuTheme(darkTheme = true) {
    Surface(color = MaterialTheme.colorScheme.background) {
        HomeScreen(
            HomeUiState("Good evening, Nishant!", 12, 28, 15, PreviewData.conversations.take(3), isRecording = true, recordingElapsed = "02:14"),
            {}, {}, {}, {},
        )
    }
}
