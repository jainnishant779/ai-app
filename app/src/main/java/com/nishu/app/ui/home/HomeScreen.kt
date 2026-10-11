package com.nishu.app.ui.home

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nishu.app.ui.components.NishuFilterChip
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.data.db.ConversationEntity
import com.nishu.app.domain.model.ConversationCategory
import com.nishu.app.domain.model.ConversationUiModel
import com.nishu.app.domain.model.RecordingStatus
import com.nishu.app.domain.repo.ConversationRepository
import com.nishu.app.domain.repo.RecordingRepository
import com.nishu.app.domain.repo.SettingsRepository
import com.nishu.app.ui.components.ConversationCard
import com.nishu.app.ui.components.EmptyState
import com.nishu.app.ui.components.SectionHeader
import com.nishu.app.ui.components.nishuCard
import com.nishu.app.ui.components.pressScale
import com.nishu.app.ui.formatClock
import com.nishu.app.ui.greetingFor
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuCtaGradient
import com.nishu.app.ui.theme.NishuPalette
import com.nishu.app.ui.theme.NishuTheme
import com.nishu.app.ui.vmFactory
import com.nishu.app.work.Pipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class HomeUiState(
    val greeting: String = "",
    val userName: String = "Nishu",
    val recordingCount: Int = 0,
    val taskCount: Int = 0,
    val decisionCount: Int = 0,
    val memoryCount: Int = 0,
    val recentConversations: List<ConversationUiModel> = emptyList(),
    val isRecording: Boolean = false,
    val recordingElapsed: String? = null,
    val languageCode: String = "hinglish",
    val sttLabel: String = "Swift (Hinglish)",
    val selectedSttModel: String = "hinglish-swift-int8",
)

class HomeViewModel(
    private val conversations: ConversationRepository,
    private val recording: RecordingRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<HomeUiState> = combine(
        settings.info, conversations.counts(), conversations.recent(6), recording.state,
    ) { info, counts, recent, rec ->
        HomeUiState(
            greeting = greetingFor(info.userName),
            userName = if (info.userName.isNotBlank()) info.userName else "Nishant",
            recordingCount = counts.recordings,
            taskCount = counts.openTasks,
            decisionCount = counts.decisions,
            memoryCount = counts.facts,
            recentConversations = recent,
            isRecording = rec.status != RecordingStatus.IDLE,
            recordingElapsed = if (rec.status != RecordingStatus.IDLE) formatClock(rec.elapsedMs) else null,
            languageCode = info.languageCode,
            sttLabel = info.sttLabel,
            selectedSttModel = info.selectedSttModel,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setLanguage(lang: String) = viewModelScope.launch { settings.setLanguage(lang) }
    fun setSttModel(modelId: String) = viewModelScope.launch { settings.setSttModel(modelId) }

    fun importAudioFile(context: Context, uri: Uri, onImported: (Long) -> Unit, onFailed: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var id: Long? = null
            try {
                val cr = context.contentResolver
                val displayName = runCatching {
                    cr.query(uri, null, null, null, null)?.use { c ->
                        val nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && nameCol >= 0) c.getString(nameCol) else null
                    }
                }.getOrNull() ?: "Imported Recording"

                val cleanTitle = displayName.substringBeforeLast(".")
                val now = System.currentTimeMillis()
                val newId = AppGraph.database.conversations().insert(
                    ConversationEntity(title = cleanTitle, createdAt = now, status = "RECORDED", category = "MEETING")
                )
                id = newId
                val destDir = File(context.filesDir, "recordings").apply { mkdirs() }
                val destFile = File(destDir, "$newId.wav")

                // Decode whatever was picked (mp3, m4a, ...) into the 16 kHz mono WAV the recognizer reads.
                com.nishu.app.audio.AudioImporter.import(context, uri, destFile)

                val duration = com.nishu.app.audio.WavFile.durationMs(destFile)
                val row = AppGraph.database.conversations().get(newId)
                if (row != null) {
                    AppGraph.database.conversations().update(
                        row.copy(audioPath = destFile.absolutePath, durationMs = duration)
                    )
                }

                Pipeline.enqueue(context.applicationContext, newId, replace = true)
                withContext(Dispatchers.Main) {
                    onImported(newId)
                }
            } catch (e: Exception) {
                // An import that cannot be read leaves no half-made conversation behind.
                id?.let { runCatching { AppGraph.conversations.delete(it) } }
                val reason = (e as? com.nishu.app.audio.ImportFailed)?.message ?: "Could not import this file"
                withContext(Dispatchers.Main) { onFailed(reason) }
            }
        }
    }
}

@Composable
fun HomeRoute(
    onStartRecording: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onSeeAll: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit = {},
    onOpenThinkList: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel(
        factory = vmFactory { HomeViewModel(AppGraph.conversations, AppGraph.recording, AppGraph.settings) },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val recordLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) onStartRecording()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            vm.importAudioFile(
                context, uri,
                onImported = { importedId -> onOpenConversation(importedId) },
                onFailed = { reason -> android.widget.Toast.makeText(context, reason, android.widget.Toast.LENGTH_LONG).show() },
            )
        }
    }

    HomeScreen(
        state = state,
        onStartRecording = { if (state.isRecording) onStartRecording() else recordLauncher.launch(permissions) },
        onImportAudio = { importLauncher.launch("audio/*") },
        onOpenConversation = onOpenConversation,
        onSeeAll = onSeeAll,
        onOpenSettings = onOpenSettings,
        onOpenChat = onOpenChat,
        onOpenThinkList = onOpenThinkList,
        onSetLanguage = vm::setLanguage,
        onSetSttModel = vm::setSttModel,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onStartRecording: () -> Unit,
    onImportAudio: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onSeeAll: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit = {},
    onOpenThinkList: () -> Unit = {},
    onSetLanguage: (String) -> Unit = {},
    onSetSttModel: (String) -> Unit = {},
) {
    var quickQuery by remember { androidx.compose.runtime.mutableStateOf("") }
    var showModelDialog by remember { androidx.compose.runtime.mutableStateOf(false) }

    if (showModelDialog) {
        ModelSelectionDialog(
            currentModel = state.selectedSttModel,
            currentLanguage = state.languageCode,
            onDismiss = { showModelDialog = false },
            onSelectModel = onSetSttModel,
            onSelectLanguage = onSetLanguage,
        )
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize()) {
            HomeHeaderBar(greeting = state.greeting, userName = state.userName, onOpenSettings = onOpenSettings)

            LazyColumn(
                contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 1. Sleek Status & Model strip (Minimal vertical footprint)
                item {
                    StatusAndModelStrip(
                        sttLabel = state.sttLabel,
                        selectedModel = state.selectedSttModel,
                        onOpenModelDialog = { showModelDialog = true },
                        onOpenSettings = onOpenSettings,
                    )
                }

                // 2. Action Tiles: Record (Hero), Think-List, Ask AI
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ActionTile(
                            title = if (state.isRecording) "Recording…" else "Record",
                            subtitle = if (state.isRecording) state.recordingElapsed ?: "00:00" else "Capture audio",
                            icon = Icons.Rounded.Mic,
                            isPrimary = true,
                            onClick = onStartRecording,
                            modifier = Modifier.weight(1.1f),
                        )
                        ActionTile(
                            title = "Think-List",
                            subtitle = "${state.taskCount + state.decisionCount} items",
                            icon = Icons.Rounded.Checklist,
                            isPrimary = false,
                            onClick = onOpenThinkList,
                            modifier = Modifier.weight(1f),
                        )
                        ActionTile(
                            title = "Ask AI",
                            subtitle = "Ask memory",
                            icon = Icons.Rounded.AutoAwesome,
                            isPrimary = false,
                            onClick = onOpenChat,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // 3. Ask Nishu Card with query bar and quick chips
                item {
                    AskNishuCard(
                        query = quickQuery,
                        onQueryChange = { quickQuery = it },
                        onSubmit = { onOpenChat() },
                        onChipClick = { chip ->
                            when (chip) {
                                "tasks" -> onOpenThinkList()
                                "decisions" -> onOpenThinkList()
                                else -> onOpenChat()
                            }
                        },
                    )
                }

                // 4. Quick Stats Row: Recordings, Tasks, Decisions
                item {
                    QuickStatsRow(
                        recordings = state.recordingCount,
                        tasks = state.taskCount,
                        decisions = state.decisionCount,
                        onRecordingsClick = onSeeAll,
                        onTasksClick = onOpenThinkList,
                        onDecisionsClick = onOpenThinkList,
                    )
                }

                // 5. Recent Recording Section & Dedicated "All Recordings" Button
                item {
                    SectionHeader(
                        title = "Recent recording",
                        actionLabel = if (state.recentConversations.isNotEmpty()) "View all (${state.recordingCount}) →" else null,
                        onAction = onSeeAll,
                    )
                }

                if (state.recentConversations.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.Mic,
                            title = "No recordings yet",
                            message = "Tap Record to capture meetings, notes or discussions.",
                        )
                    }
                } else {
                    // Show only 1 latest recording snippet so Home stays super clean & breathable
                    item {
                        ConversationCard(
                            item = state.recentConversations.first(),
                            onClick = { onOpenConversation(state.recentConversations.first().id) },
                        )
                    }

                    // Prominent button that houses all recordings
                    item {
                        ViewAllRecordingsButton(
                            count = state.recordingCount,
                            onClick = onSeeAll,
                        )
                    }
                }

                // 6. Secondary Import Option
                item {
                    ImportAudioBanner(onClick = onImportAudio)
                }

                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }
}

@Composable
private fun HomeHeaderBar(greeting: String, userName: String, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = Dimens.ScreenGutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(
                painter = painterResource(com.nishu.app.R.drawable.ic_nishu_logo),
                contentDescription = "Nishu Logo",
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
            )
            Column {
                Text(
                    text = "YOUR PRIVATE AI COMPANION",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Hey, $userName 👋",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        ) {
            Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun StatusAndModelStrip(
    sttLabel: String,
    selectedModel: String,
    onOpenModelDialog: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modelShort = when {
        selectedModel.contains("swift", ignoreCase = true) -> "Swift (Hinglish)"
        selectedModel.contains("qwen", ignoreCase = true) -> "Qwen3"
        selectedModel.contains("small", ignoreCase = true) -> "Whisper Small"
        else -> "Swift (Auto)"
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Device status chip
        Surface(
            onClick = onOpenSettings,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 0.5.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(NishuPalette.Success),
                )
                Text(
                    text = "Nishu Wearable • Ready",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Model selector pill
        Surface(
            onClick = onOpenModelDialog,
            shape = RoundedCornerShape(14.dp),
            color = NishuPalette.PrimaryLight.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, NishuPalette.Primary.copy(alpha = 0.3f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = NishuPalette.Primary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "STT: $modelShort ▾",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = NishuPalette.Primary,
                )
            }
        }
    }
}

@Composable
private fun QuickStatsRow(
    recordings: Int,
    tasks: Int,
    decisions: Int,
    onRecordingsClick: () -> Unit,
    onTasksClick: () -> Unit,
    onDecisionsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. Recordings
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onRecordingsClick() }
                    .padding(vertical = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(NishuPalette.PrimaryLight),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = NishuPalette.Primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "$recordings",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Recordings",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(42.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            )

            // 2. Open Tasks
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onTasksClick() }
                    .padding(vertical = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8F5E9)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Checklist,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "$tasks",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Open tasks",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(42.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            )

            // 3. Decisions
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onDecisionsClick() }
                    .padding(vertical = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEDE7F6)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFF673AB7),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "$decisions",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Decisions",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ViewAllRecordingsButton(
    count: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(NishuPalette.PrimaryLight),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = NishuPalette.Primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = "All Recordings ($count)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "View full library",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NishuPalette.Secondary,
                )
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = NishuPalette.Secondary,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer(rotationZ = 180f),
                )
            }
        }
    }
}

@Composable
private fun ImportAudioBanner(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = NishuPalette.Secondary,
                    modifier = Modifier.size(18.dp),
                )
                Column {
                    Text(
                        "Import existing audio",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "WAV, MP3, M4A from your device",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "Import +",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = NishuPalette.Primary,
            )
        }
    }
}

@Composable
private fun ModelSelectionDialog(
    currentModel: String,
    currentLanguage: String,
    onDismiss: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectLanguage: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Speech Recognition Model",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "All models run 100% locally on your Motorola hardware with zero latency and complete privacy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val models = listOf(
                    Triple(
                        "hinglish-swift-int8",
                        "Swift (Hinglish Base - 72M) ★ Current",
                        "Fastest. Directly writes Roman Hinglish. Recommended for everyday voice notes.",
                    ),
                    Triple(
                        "whisper-small-en-int8",
                        "Whisper Small (High Accuracy - 244M) 🔥 Better Accuracy",
                        "3.4x larger than Swift. Best recognition accuracy for noisy audio, accents, and mixed English/Hindi speech.",
                    ),
                    Triple(
                        "qwen3-asr-hinglish-int8",
                        "Qwen3-ASR Hinglish (0.6B) 🚀 SOTA Bilingual",
                        "600M parameters. Highest speech comprehension (Devanagari for Hindi, Latin for English).",
                    ),
                    Triple(
                        "auto",
                        "Auto Selection",
                        "Automatically selects the best model based on language.",
                    ),
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    models.forEach { (id, title, desc) ->
                        val isSelected = when (id) {
                            "auto" -> currentModel == "auto"
                            "hinglish-swift-int8" -> currentModel.contains("swift", ignoreCase = true)
                            "qwen3-asr-hinglish-int8" -> currentModel.contains("qwen", ignoreCase = true)
                            "whisper-small-en-int8" -> currentModel.contains("small", ignoreCase = true)
                            else -> false
                        }

                        Surface(
                            onClick = { onSelectModel(id); onDismiss() },
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
                                RadioButton(selected = isSelected, onClick = { onSelectModel(id); onDismiss() })
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                                        color = if (isSelected) NishuPalette.Primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("Target Language Mode", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NishuFilterChip(
                        label = "Hinglish ✓",
                        selected = currentLanguage != "english",
                        onClick = { onSelectLanguage("hinglish") },
                        modifier = Modifier.weight(1f),
                    )
                    NishuFilterChip(
                        label = "English",
                        selected = currentLanguage == "english",
                        onClick = { onSelectLanguage("english") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", fontWeight = FontWeight.Bold, color = NishuPalette.Primary)
            }
        },
    )
}

@Composable
private fun AskNishuCard(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onChipClick: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "✦ Ask Nishu anything",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Find answers in conversations, tasks and notes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text("Ask about your day…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        inner()
                    },
                )
                IconButton(
                    onClick = onSubmit,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(NishuPalette.Primary),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Submit",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp).graphicsLayer(rotationZ = 180f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PromptChip("✦ Summarize day") { onChipClick("Summarize my day") }
                PromptChip("✓ My tasks") { onChipClick("What are my pending tasks?") }
                PromptChip("◇ Decisions") { onChipClick("What did I decide?") }
            }
        }
    }
}

@Composable
private fun PromptChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.height(32.dp),
    ) {
        Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = NishuPalette.Secondary)
        }
    }
}

@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isPrimary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = source,
        shape = RoundedCornerShape(20.dp),
        color = if (isPrimary) NishuPalette.Primary else MaterialTheme.colorScheme.surface,
        border = if (isPrimary) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = if (isPrimary) 4.dp else 1.dp,
        modifier = modifier
            .height(115.dp)
            .pressScale(source),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isPrimary) Color.White.copy(alpha = 0.18f) else NishuPalette.PrimaryLight),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (isPrimary) Color.White else NishuPalette.Primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (isPrimary) Color.White else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (isPrimary) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
