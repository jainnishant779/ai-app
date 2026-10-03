package com.nishu.app.ui.conversation

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
) : ViewModel() {
    val state: StateFlow<ConversationDetail?> = repo.detail(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val transcript: StateFlow<List<TranscriptLine>> = repo.transcript(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setTaskDone(taskId: Long, done: Boolean) = viewModelScope.launch { repo.setTaskDone(taskId, done) }
    fun rename(title: String) = viewModelScope.launch { repo.rename(id, title) }
    fun setCategory(c: ConversationCategory) = viewModelScope.launch { repo.setCategory(id, c) }
    fun renameSpeaker(label: String, name: String) = viewModelScope.launch { repo.renameSpeaker(id, label, name) }
    fun deleteAudio() = viewModelScope.launch { repo.deleteAudio(id) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { repo.delete(id); onDone() }
    fun retry(onStarted: () -> Unit) = viewModelScope.launch { repo.retryProcessing(id); onStarted() }

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

                        if (filteredLines.isEmpty()) {
                            item {
                                Text(
                                    text = if (detail.conversation.status == ConversationStatus.RECORDED) {
                                        "Transcribing on-device... Notes will appear shortly."
                                    } else {
                                        "No transcript segments available."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp),
                                )
                            }
                        } else {
                            // Two lines can start at the same millisecond; a key of startMs alone crashed the screen (duplicate key).
                            itemsIndexed(filteredLines, key = { i, l -> "$i:${l.startMs}" }) { index, line ->
                                TranscriptSegmentTimelineItem(
                                    line = line,
                                    speakerIndex = index % 4,
                                    onClick = { onSeek(line.startMs) },
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
                            SectionHeader(title = "Identified Speakers")
                        }
                        val speakers = transcript.mapNotNull { it.speaker }.distinctBy { it.label }
                        if (speakers.isEmpty()) {
                            item {
                                Text(
                                    "Single speaker conversation or diarization pending.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(speakers) { spk ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .nishuCard()
                                        .clickable { renameSpeakerTarget = spk.label }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    SpeakerChip(spk.name, spk.index)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        spk.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { renameSpeakerTarget = spk.label }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "Rename speaker", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
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
        val current = transcript.firstOrNull { it.speaker?.label == label }?.speaker?.name ?: label
        TextInputDialog(
            title = "Rename $label",
            initial = current,
            onConfirm = { onRenameSpeaker(label, it); renameSpeakerTarget = null },
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
