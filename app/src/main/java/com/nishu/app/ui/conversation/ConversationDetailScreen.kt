package com.nishu.app.ui.conversation

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.domain.model.ConversationCategory
import com.nishu.app.domain.model.ConversationDetail
import com.nishu.app.domain.model.ConversationStatus
import com.nishu.app.domain.model.TranscriptLine
import com.nishu.app.domain.repo.AudioPlayer
import com.nishu.app.domain.repo.ConversationRepository
import com.nishu.app.domain.repo.PlayerState
import com.nishu.app.ui.components.AudioWaveform
import com.nishu.app.ui.components.CategoryDialog
import com.nishu.app.ui.components.ConfirmDialog
import com.nishu.app.ui.components.DecisionCard
import com.nishu.app.ui.components.EmptyState
import com.nishu.app.ui.components.KeyPointRow
import com.nishu.app.ui.components.LoadingState
import com.nishu.app.ui.components.MeetingSummaryCard
import com.nishu.app.ui.components.MenuAction
import com.nishu.app.ui.components.NishuButton
import com.nishu.app.ui.components.NishuSearchBar
import com.nishu.app.ui.components.NishuIconButton
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.OverflowMenu
import com.nishu.app.ui.components.SectionHeader
import com.nishu.app.ui.components.SegmentedPillTabs
import com.nishu.app.ui.components.SpeakerChip
import com.nishu.app.ui.components.TaskCard
import com.nishu.app.ui.components.TextInputDialog
import com.nishu.app.ui.components.AssignSpeakerDialog
import com.nishu.app.domain.repo.SettingsRepository
import kotlinx.coroutines.flow.map
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.CheckCircle
import com.nishu.app.ui.components.nishuCard
import com.nishu.app.ui.components.speakerColor
import com.nishu.app.ui.formatClock
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuPalette
import com.nishu.app.ui.theme.NishuTheme
import com.nishu.app.ui.vmFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConversationDetailViewModel(
    private val id: Long,
    private val repo: ConversationRepository,
    val player: AudioPlayer,
    settings: SettingsRepository = AppGraph.settings,
) : ViewModel() {
    val state: StateFlow<ConversationDetail?> = repo.detail(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val transcript: StateFlow<List<TranscriptLine>> = repo.transcript(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val speakerProfiles: StateFlow<List<com.nishu.app.data.db.SpeakerProfileEntity>> = repo.speakerProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val userName: StateFlow<String> = settings.info.map { it.userName.ifBlank { "Nishant" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Nishant")

    fun setTaskDone(taskId: Long, done: Boolean) = viewModelScope.launch { repo.setTaskDone(taskId, done) }
    fun rename(title: String) = viewModelScope.launch { repo.rename(id, title) }
    fun setCategory(c: ConversationCategory) = viewModelScope.launch { repo.setCategory(id, c) }
    fun renameSpeaker(label: String, name: String) = viewModelScope.launch { repo.renameSpeaker(id, label, name) }
    fun deleteAudio() = viewModelScope.launch { repo.deleteAudio(id) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { repo.delete(id); onDone() }
    fun retry(onStarted: () -> Unit) = viewModelScope.launch { repo.retryProcessing(id); onStarted() }
    fun retranscribe(modelId: String?) = viewModelScope.launch { repo.retranscribe(id, modelId) }

    override fun onCleared() {
        player.release()
    }
}

@Composable
fun ConversationDetailRoute(
    id: Long,
    onBack: () -> Unit,
    onTranscript: (Long) -> Unit,
    onProcessing: (Long) -> Unit = {},
    onAudioDetails: () -> Unit = {},
    onAskAi: (Long, String) -> Unit = { _, _ -> },
) {
    val vm: ConversationDetailViewModel = viewModel(
        key = "detail_$id",
        factory = vmFactory {
            ConversationDetailViewModel(id, AppGraph.conversations, AppGraph.newPlayer(id))
        },
    )
    val detail by vm.state.collectAsStateWithLifecycle()
    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val speakerProfiles by vm.speakerProfiles.collectAsStateWithLifecycle()
    val userName by vm.userName.collectAsStateWithLifecycle()
    val playerState by vm.player.state.collectAsStateWithLifecycle()
    val envelope by vm.player.envelope.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose { vm.player.pause() }
    }

    ConversationDetailScreen(
        detail = detail,
        transcript = transcript,
        playerState = playerState,
        envelope = envelope,
        speakerProfiles = speakerProfiles,
        userName = userName,
        onBack = onBack,
        onPlayPause = { if (playerState.isPlaying) vm.player.pause() else vm.player.play() },
        onSeek = vm.player::seekTo,
        onCycleSpeed = vm.player::cycleSpeed,
        onToggleTask = vm::setTaskDone,
        onRename = vm::rename,
        onSetCategory = vm::setCategory,
        onRenameSpeaker = vm::renameSpeaker,
        onDeleteAudio = vm::deleteAudio,
        onDelete = { vm.delete(onBack) },
        onRetry = { vm.retry { onProcessing(id) } },
        onRetranscribe = vm::retranscribe,
        onAudioDetails = onAudioDetails,
        onAskAi = { onAskAi(id, detail?.conversation?.title.orEmpty()) },
    )
}

private val detailTabs = listOf("Transcript", "Summary", "Tasks", "Speakers")

@Composable
fun ConversationDetailScreen(
    detail: ConversationDetail?,
    transcript: List<TranscriptLine>,
    playerState: PlayerState,
    envelope: List<Float>,
    speakerProfiles: List<com.nishu.app.data.db.SpeakerProfileEntity> = emptyList(),
    userName: String = "Nishant",
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    onToggleTask: (Long, Boolean) -> Unit,
    onRename: (String) -> Unit,
    onSetCategory: (ConversationCategory) -> Unit,
    onRenameSpeaker: (String, String) -> Unit,
    onDeleteAudio: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit = {},
    onRetranscribe: (String?) -> Unit = {},
    onAudioDetails: () -> Unit = {},
    onAskAi: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf(false) }
    var categorizing by remember { mutableStateOf(false) }
    var renameSpeakerTarget by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteAudio by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize()) {

            // Top Bar
            NishuTopBar(
                title = detail?.conversation?.title ?: "Recording",
                onBack = onBack,
                actions = {
                    if (detail != null && transcript.isNotEmpty()) {
                        NishuIconButton(Icons.Rounded.AutoAwesome, "Ask AI about this recording", onAskAi)
                    }
                    OverflowMenu(
                        actions = buildList {
                            add(MenuAction("Assign speakers") { selectedTab = 3 })
                            if (detail?.hasAudio == true) {
                                add(MenuAction("Change transcript model") { showModelDialog = true })
                            }
                            add(MenuAction("Audio details") { onAudioDetails() })
                            add(MenuAction("Rename") { renaming = true })
                            add(MenuAction("Change category") { categorizing = true })
                            if (detail?.hasAudio == true) {
                                add(MenuAction("Delete audio, keep transcript") { confirmDeleteAudio = true })
                            }
                            add(MenuAction("Delete conversation") { confirmDelete = true })
                        },
                    )
                },
            )

            if (detail == null) {
                LoadingState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    // Audio Player Card (matching Screen D)
                    if (detail.hasAudio) {
                        item {
                            AudioPlayerCard(
                                playerState = playerState,
                                envelope = envelope,
                                onPlayPause = onPlayPause,
                                onSeek = onSeek,
                                onCycleSpeed = onCycleSpeed,
                            )
                        }
                    }

                    // Segmented Tabs: [Transcript | Summary | Tasks | Speakers]
                    item {
                        SegmentedPillTabs(
                            tabs = detailTabs,
                            selectedIndex = selectedTab,
                            onSelect = { selectedTab = it },
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    // TAB 0: TRANSCRIPTION (Screen D)
                    if (selectedTab == 0) {
                        if (searchOpen) {
                            item {
                                NishuSearchBar(
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    placeholder = "Search in transcript...",
                                    autoFocus = true,
                                )
                            }
                        }

                        val filteredLines = if (searchQuery.isBlank()) transcript else {
                            transcript.filter { it.text.contains(searchQuery.trim(), ignoreCase = true) }
                        }

                        if (detail.hasAudio) {
                            item {
                                Surface(
                                    onClick = { showModelDialog = true },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Icon(
                                                Icons.Rounded.Tune,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Column {
                                                Text(
                                                    "Speech Model",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                Text(
                                                    "Active: ${detail.conversation.sttModelLabel}",
                                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                        Surface(
                                            onClick = { showModelDialog = true },
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                        ) {
                                            Text(
                                                "Change Model",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color.White),
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        val isProcessing = detail.conversation.status == ConversationStatus.RECORDED ||
                            detail.conversation.status == ConversationStatus.TRANSCRIBING ||
                            detail.conversation.status == ConversationStatus.SUMMARIZING

                        if (isProcessing) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(32.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            strokeWidth = 3.dp,
                                        )
                                        Text(
                                            text = if (detail.conversation.status == ConversationStatus.SUMMARIZING) {
                                                "Summarizing with Local LLM..."
                                            } else {
                                                "Transcribing with ${detail.conversation.sttModelLabel}..."
                                            },
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "Running on-device Silero VAD, 3D-Speaker diarization, and speech decoding. Notes will appear shortly.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        } else if (filteredLines.isEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = "No speech detected in this recording.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (detail.hasAudio) {
                                        NishuButton(
                                            text = "Re-transcribe with Another Model",
                                            onClick = { showModelDialog = true },
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                }
                            }
                        } else {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            Icons.Rounded.RecordVoiceOver,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Text(
                                            "Tap speaker tag [S1] to assign name & enroll voiceprint",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            // Two lines can start at the same millisecond; a key of startMs alone crashed the screen (duplicate key).
                            itemsIndexed(filteredLines, key = { i, l -> "$i:${l.startMs}" }) { index, line ->
                                TranscriptSegmentTimelineItem(
                                    line = line,
                                    speakerIndex = index % 4,
                                    onClick = { onSeek(line.startMs) },
                                    onSpeakerClick = { label -> renameSpeakerTarget = label },
                                )
                            }
                        }
                    }

                    // TAB 1: SUMMARY (Screen E)
                    if (selectedTab == 1) {
                        val summaryText = detail.summaryBullets.joinToString("\n\n").ifBlank { detail.conversation.preview }

                        // Meeting Summary card
                        item {
                            MeetingSummaryCard(
                                summaryText = summaryText,
                                title = "Meeting Summary",
                            )
                        }

                        // Key Points
                        if (detail.keyPoints.isNotEmpty()) {
                            item {
                                SectionHeader(title = "Key Points", modifier = Modifier.padding(top = 6.dp))
                            }
                            items(detail.keyPoints) { kp ->
                                KeyPointRow(text = kp)
                            }
                        }

                        // Action Items with checkboxes & priority pills
                        if (detail.tasks.isNotEmpty()) {
                            item {
                                SectionHeader(title = "Action Items", modifier = Modifier.padding(top = 6.dp))
                            }
                            items(detail.tasks, key = { it.id }) { task ->
                                TaskCard(
                                    item = task,
                                    onToggle = { onToggleTask(task.id, it) },
                                )
                            }
                        }

                        // Decisions
                        if (detail.decisions.isNotEmpty()) {
                            item {
                                SectionHeader(title = "Decisions", modifier = Modifier.padding(top = 6.dp))
                            }
                            items(detail.decisions, key = { it.id }) { dec ->
                                DecisionCard(item = dec)
                            }
                        }
                    }

                    // TAB 2: TASKS
                    if (selectedTab == 2) {
                        if (detail.tasks.isEmpty()) {
                            item {
                                Text(
                                    "No tasks identified in this conversation.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp),
                                )
                            }
                        } else {
                            items(detail.tasks, key = { it.id }) { task ->
                                TaskCard(
                                    item = task,
                                    onToggle = { onToggleTask(task.id, it) },
                                )
                            }
                        }
                    }

                    // TAB 3: SPEAKERS
                    if (selectedTab == 3) {
                        item {
                            SectionHeader(title = "Speakers in this Conversation")
                        }
                        item {
                            Text(
                                "Assign names to speakers below. Nishu AI learns their voiceprints using on-device CAM++ biometrics so they are recognized automatically in future meetings and voice notes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }

                        val rawSpeakers = transcript.mapNotNull { it.speaker }.distinctBy { it.label }
                        val speakers = if (rawSpeakers.isNotEmpty()) rawSpeakers else listOf(com.nishu.app.domain.model.SpeakerRef("S1", "Speaker 1"))

                        items(speakers) { spk ->
                            val turnCount = transcript.count { (it.speaker?.label ?: "S1") == spk.label }
                            val isNamed = spk.name != "Speaker 1" && spk.name != "Speaker 2" && !spk.name.startsWith("Speaker ")

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp)),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, if (isNamed) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant),
                                tonalElevation = 1.dp,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    // Speaker Avatar
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(CircleShape)
                                            .background(speakerColor(spk.index).copy(alpha = 0.18f))
                                            .border(1.5.dp, speakerColor(spk.index), CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = spk.label,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = speakerColor(spk.index),
                                        )
                                    }

                                    // Speaker Info
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = spk.name,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "$turnCount spoken segment${if (turnCount > 1) "s" else ""}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        if (isNamed) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Icon(
                                                    Icons.Rounded.CheckCircle,
                                                    contentDescription = null,
                                                    tint = Color(0xFF1B8755),
                                                    modifier = Modifier.size(13.dp),
                                                )
                                                Text(
                                                    "Voice enrolled • Auto-recognized",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                                    color = Color(0xFF1B8755),
                                                )
                                            }
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Icon(
                                                    Icons.Rounded.RecordVoiceOver,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(13.dp),
                                                )
                                                Text(
                                                    "Voice ready • Tap Assign",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                    }

                                    // Action Button
                                    Surface(
                                        onClick = { renameSpeakerTarget = spk.label },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isNamed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                Icons.Rounded.Edit,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = if (isNamed) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                                            )
                                            Text(
                                                text = if (isNamed) "Rename" else "Assign",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = if (isNamed) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Explanatory card
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                        Text(
                                            "How On-Device Voice Memory Works",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Text(
                                        "• 3D-Speaker CAM++ creates a 512-dim voiceprint from spoken speech.\n" +
                                        "• When you assign a name, Nishu AI saves the voice profile locally.\n" +
                                        "• In future recordings, speakers are matched and tagged automatically.\n" +
                                        "• Completely private: 0 bytes of audio or voice data leave your phone.",
                                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }

                // BOTTOM ACTION BAR
                // Screen D: Copy, Edit, Search, Translate (when in Transcript tab)
                // Screen E: "Copy Summary" & "Save to Notes" (when in Summary tab)
                Surface(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    tonalElevation = 2.dp,
                ) {
                    if (selectedTab == 0) {
                        // Transcript bottom action bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TranscriptBottomAction(
                                icon = Icons.Rounded.ContentCopy,
                                label = "Copy",
                                onClick = {
                                    val fullText = transcript.joinToString("\n") { "[${formatClock(it.startMs)}] ${it.text}" }
                                    clipboard.setText(AnnotatedString(fullText))
                                    Toast.makeText(context, "Transcript copied", Toast.LENGTH_SHORT).show()
                                },
                            )
                            TranscriptBottomAction(
                                icon = Icons.Rounded.Edit,
                                label = "Rename",
                                onClick = { renaming = true },
                            )
                            TranscriptBottomAction(
                                icon = Icons.Rounded.Search,
                                label = "Search",
                                onClick = { searchOpen = !searchOpen },
                            )
                            TranscriptBottomAction(
                                icon = Icons.Rounded.Share,
                                label = "Share",
                                onClick = {
                                    val fullText = transcript.joinToString("\n") { "[${formatClock(it.startMs)}] ${it.text}" }
                                    shareText(context, detail.conversation.title, fullText)
                                },
                            )
                        }
                    } else if (selectedTab == 1) {
                        // AI Summary bottom action bar: "Copy Summary" & "Save to Notes"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.ScreenGutter, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                onClick = {
                                    val fullSummary = buildString {
                                        appendLine(detail.summaryBullets.joinToString("\n\n").ifBlank { detail.conversation.preview })
                                        if (detail.keyPoints.isNotEmpty()) {
                                            appendLine("\nKey Points:")
                                            detail.keyPoints.forEach { appendLine("• $it") }
                                        }
                                    }
                                    clipboard.setText(AnnotatedString(fullSummary))
                                    Toast.makeText(context, "Summary copied", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.weight(1f).height(50.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "Copy Summary",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }

                            NishuButton(
                                text = "Share",
                                onClick = {
                                    val body = buildString {
                                        appendLine(detail.summaryBullets.joinToString("\n").ifBlank { detail.conversation.preview })
                                        if (detail.keyPoints.isNotEmpty()) {
                                            appendLine("\nKey points:")
                                            detail.keyPoints.forEach { appendLine("• $it") }
                                        }
                                    }
                                    shareText(context, detail.conversation.title, body)
                                },
                                modifier = Modifier.weight(1f).height(50.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (renaming && detail != null) {
        TextInputDialog(
            title = "Rename conversation",
            initial = detail.conversation.title,
            onConfirm = { onRename(it); renaming = false },
            onDismiss = { renaming = false },
        )
    }

    if (categorizing && detail != null) {
        CategoryDialog(
            current = detail.conversation.category,
            onSelect = { onSetCategory(it); categorizing = false },
            onDismiss = { categorizing = false },
        )
    }

    renameSpeakerTarget?.let { label ->
        val current = transcript.firstOrNull { it.speaker?.label == label }?.speaker?.name
            ?: if (label == "S1") "Speaker 1" else label
        val initialInput = if (current.startsWith("Speaker ") || current == "S1") "" else current
        AssignSpeakerDialog(
            speakerLabel = label,
            currentName = initialInput,
            defaultUserName = userName,
            savedProfiles = speakerProfiles,
            onConfirm = { name ->
                onRenameSpeaker(label, name)
                Toast.makeText(context, if (name.isNotBlank()) "Assigned as '$name'. Voice remembered!" else "Reset speaker name", Toast.LENGTH_SHORT).show()
                renameSpeakerTarget = null
            },
            onDismiss = { renameSpeakerTarget = null },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete conversation?",
            message = "This note and its audio will be permanently deleted.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }

    if (confirmDeleteAudio) {
        ConfirmDialog(
            title = "Delete audio file?",
            message = "Audio will be removed from device storage to free space. Transcripts and summaries remain.",
            confirmLabel = "Delete Audio",
            onConfirm = { confirmDeleteAudio = false; onDeleteAudio() },
            onDismiss = { confirmDeleteAudio = false },
        )
    }

    if (showModelDialog && detail != null) {
        ChangeTranscriptModelDialog(
            currentModelId = detail.conversation.sttModelId,
            onDismiss = { showModelDialog = false },
            onSelectModel = { modelId ->
                onRetranscribe(modelId)
                val label = when {
                    modelId.contains("swift", ignoreCase = true) -> "Swift (Hinglish Base)"
                    modelId.contains("small", ignoreCase = true) -> "Whisper Small (High Accuracy)"
                    modelId.contains("qwen", ignoreCase = true) -> "Qwen3-ASR (Bilingual)"
                    else -> "Auto"
                }
                Toast.makeText(context, "Re-transcribing with $label...", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun ChangeTranscriptModelDialog(
    currentModelId: String?,
    onDismiss: () -> Unit,
    onSelectModel: (String) -> Unit,
) {
    val models = listOf(
        Triple(
            "hinglish-swift-int8",
            "Swift (Hinglish Base - 72M) ★ Fast Roman",
            "Fastest inference. Directly writes Roman Hinglish text. Best for everyday voice notes.",
        ),
        Triple(
            "whisper-small-en-int8",
            "Whisper Small (High Accuracy - 244M) 🔥 Higher Accuracy",
            "3.4x larger parameters. Highest recognition accuracy for mixed English/Hindi speech, accents, and noisy environments.",
        ),
        Triple(
            "qwen3-asr-hinglish-int8",
            "Qwen3-ASR Hinglish (0.6B) 🚀 SOTA Bilingual",
            "600M parameters. Deep linguistic comprehension across diverse Indian accents.",
        ),
        Triple(
            "auto",
            "Auto Selection",
            "Automatically selects the optimal installed model based on hardware and language.",
        ),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    "Change Transcript Model",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Tap any model below to immediately re-transcribe this recording on-device. Notes, speaker turns, and summaries will be regenerated from the original audio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    models.forEach { (id, title, desc) ->
                        val isSelected = when (id) {
                            "auto" -> currentModelId == "auto"
                            "hinglish-swift-int8" -> currentModelId == null || currentModelId.contains("swift", ignoreCase = true)
                            "qwen3-asr-hinglish-int8" -> currentModelId?.contains("qwen", ignoreCase = true) == true
                            "whisper-small-en-int8" -> currentModelId?.contains("small", ignoreCase = true) == true
                            else -> currentModelId == id
                        }
                        Surface(
                            onClick = {
                                onSelectModel(id)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) NishuPalette.PrimaryLight else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                if (isSelected) 1.5.dp else 1.dp,
                                if (isSelected) NishuPalette.Primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        onSelectModel(id)
                                        onDismiss()
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        ),
                                        color = if (isSelected) NishuPalette.Primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Text(
                "Cancel",
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** Audio Player card matching Screen D in the visual reference */
@Composable
private fun AudioPlayerCard(
    playerState: PlayerState,
    envelope: List<Float>,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = if (playerState.durationMs > 0) {
        (playerState.positionMs.toFloat() / playerState.durationMs).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = modifier.fillMaxWidth().nishuCard(),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Play / Pause Circle (Mint background with Forest Green play icon)
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(NishuPalette.Primary)
                        .clickable(role = Role.Button, onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(Modifier.width(12.dp))

                // Waveform & scrubber
                Column(Modifier.weight(1f)) {
                    AudioWaveform(
                        levels = if (envelope.isNotEmpty()) envelope else PreviewData.levels,
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clickable { /* Seek via click handled by slider */ },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${formatClock(playerState.positionMs)} / ${formatClock(playerState.durationMs)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Playback speed pill: "1.0x ∨"
                Surface(
                    onClick = onCycleSpeed,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.height(32.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${playerState.speed}x",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Timestamped transcript segment with timeline dot and connector line (Screen D) */
@Composable
private fun TranscriptSegmentTimelineItem(
    line: TranscriptLine,
    speakerIndex: Int,
    onClick: () -> Unit,
    onSpeakerClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dotColor = speakerColor(speakerIndex)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Vertical timeline with dot
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(48.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = formatClock(line.startMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }

        Spacer(Modifier.width(8.dp))

        // Text content
        Column(Modifier.weight(1f)) {
            val spkName = line.speaker?.name ?: "Speaker 1"
            val spkIndex = line.speaker?.index ?: speakerIndex
            val spkLabel = line.speaker?.label ?: "S1"
            val isCustomNamed = line.speaker?.name != null && !spkName.startsWith("Speaker ") && spkName != "S1"

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSpeakerClick(spkLabel) }
                    .padding(vertical = 2.dp, horizontal = 4.dp),
            ) {
                SpeakerChip(spkName, spkIndex)
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "Assign name to $spkLabel",
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                if (!isCustomNamed) {
                    Text(
                        text = "Assign",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TranscriptBottomAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConversationDetailPreview() = NishuTheme {
    ConversationDetailScreen(
        detail = PreviewData.detail,
        transcript = PreviewData.transcript,
        playerState = PlayerState(isPlaying = false, positionMs = 24000, durationMs = 120000, speed = 1.0f),
        envelope = PreviewData.levels,
        onBack = {},
        onPlayPause = {},
        onSeek = {},
        onCycleSpeed = {},
        onToggleTask = { _, _ -> },
        onRename = {},
        onSetCategory = {},
        onRenameSpeaker = { _, _ -> },
        onDeleteAudio = {},
        onDelete = {},
    )
}

/** Opens the Android share sheet; the text never leaves the phone unless the user picks an app. */
private fun shareText(context: android.content.Context, title: String, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, title)
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(send, "Share").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}
