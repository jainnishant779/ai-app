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
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nishu.app.ui.components.NishuFilterChip
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
    val memoryCount: Int = 0,
    val recentConversations: List<ConversationUiModel> = emptyList(),
    val isRecording: Boolean = false,
    val recordingElapsed: String? = null,
    val languageCode: String = "hinglish",
    val sttLabel: String = "Whisper Hinglish (base)",
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
            userName = if (info.userName.isNotBlank()) info.userName else "Nishu",
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

    fun setLanguage(lang: String) = viewModelScope.launch { settings.setLanguage(lang) }

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
        onSetLanguage = vm::setLanguage,
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
    onSetLanguage: (String) -> Unit = {},
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize()) {
            HomeHeaderBar(greeting = state.greeting, onOpenSettings = onOpenSettings)

            LazyColumn(
                contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // One thing to do on this screen: record. Everything else is secondary.
                item {
                    RecordHero(
                        isRecording = state.isRecording,
                        elapsed = state.recordingElapsed,
                        languageCode = state.languageCode,
                        onRecord = onStartRecording,
                        onSetLanguage = onSetLanguage,
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryAction("Import audio", Icons.Rounded.Folder, onImportAudio, Modifier.weight(1f))
                        SecondaryAction("Ask Nishu", Icons.Rounded.AutoAwesome, onOpenChat, Modifier.weight(1f))
                    }
                }
                if (state.recordingCount > 0) {
                    item {
                        Text(
                            "${state.recordingCount} recordings • ${state.taskCount} open tasks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                item {
                    SectionHeader(title = "Recent", actionLabel = "See all", onAction = onSeeAll)
                }
                if (state.recentConversations.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.Mic,
                            title = "No recordings yet",
                            message = "Tap the mic to record a conversation, or import an audio file.",
                        )
                    }
                } else {
                    items(state.recentConversations, key = { it.id }) { item ->
                        ConversationCard(item = item, onClick = { onOpenConversation(item.id) })
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun HomeHeaderBar(greeting: String, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = Dimens.ScreenGutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Record. Nishu writes it down, on your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The big record button. Turns red with the running time while recording; tapping it opens the recording. */
@Composable
private fun RecordHero(
    isRecording: Boolean,
    elapsed: String?,
    languageCode: String,
    onRecord: () -> Unit,
    onSetLanguage: (String) -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "recPulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.12f else 1.04f,
        animationSpec = infiniteRepeatable(tween(if (isRecording) 700 else 1600), RepeatMode.Reverse),
        label = "pulse",
    )
    val color = if (isRecording) Color(0xFFE5484D) else NishuPalette.Primary
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .nishuCard(shape = RoundedCornerShape(28.dp))
            .padding(vertical = 26.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(168.dp)) {
            Box(Modifier.size(150.dp).scale(pulse).clip(CircleShape).background(color.copy(alpha = 0.12f)))
            Surface(
                onClick = onRecord,
                interactionSource = source,
                shape = CircleShape,
                color = color,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(112.dp)
                    .pressScale(source)
                    .semantics { contentDescription = if (isRecording) "Open the running recording" else "Start recording" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isRecording) {
                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color.White))
                    } else {
                        Icon(Icons.Rounded.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(46.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (isRecording) "Recording • ${elapsed ?: "00:00"}" else "Tap to record",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (isRecording) color else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (isRecording) "Tap to see controls" else "Conversations, meetings, voice notes",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isRecording) {
            Spacer(Modifier.height(14.dp))
            Text("Language spoken", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NishuFilterChip("Hindi / Hinglish", languageCode != "english", { onSetLanguage("hinglish") })
                NishuFilterChip("English", languageCode == "english", { onSetLanguage("english") })
            }
        }
    }
}

@Composable
private fun SecondaryAction(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = source,
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = modifier.height(56.dp).pressScale(source).nishuCard(shape = RoundedCornerShape(18.dp)),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = NishuPalette.Primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Preview(widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun HomeScreenPreview() = NishuTheme(darkTheme = false) {
    HomeScreen(
        state = HomeUiState("Good morning", "Nishu", 12, 4, 15, PreviewData.conversations.take(4)),
        onStartRecording = {},
        onImportAudio = {},
        onOpenConversation = {},
        onSeeAll = {},
        onOpenSettings = {},
    )
}
