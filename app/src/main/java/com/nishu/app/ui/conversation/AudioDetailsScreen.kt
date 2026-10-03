package com.nishu.app.ui.conversation

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NoteAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.domain.model.ConversationCategory
import com.nishu.app.domain.model.ConversationDetail
import com.nishu.app.domain.repo.ConversationRepository
import com.nishu.app.ui.components.CategoryDialog
import com.nishu.app.ui.components.ConfirmDialog
import com.nishu.app.ui.components.LoadingState
import com.nishu.app.ui.components.MenuAction
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.OverflowMenu
import com.nishu.app.ui.components.TextInputDialog
import com.nishu.app.ui.components.categoryLabel
import com.nishu.app.ui.components.nishuCard
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

class AudioDetailsViewModel(
    private val id: Long,
    private val repo: ConversationRepository,
) : ViewModel() {
    val state: StateFlow<ConversationDetail?> = repo.detail(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun rename(title: String) = viewModelScope.launch { repo.rename(id, title) }
    fun setCategory(c: ConversationCategory) = viewModelScope.launch { repo.setCategory(id, c) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { repo.delete(id); onDone() }
}

@Composable
fun AudioDetailsRoute(id: Long, onBack: () -> Unit, onPlay: () -> Unit) {
    val vm: AudioDetailsViewModel = viewModel(
        key = "audio_details_$id",
        factory = vmFactory { AudioDetailsViewModel(id, AppGraph.conversations) },
    )
    val detail by vm.state.collectAsStateWithLifecycle()

    AudioDetailsScreen(
        detail = detail,
        onBack = onBack,
        onPlay = onPlay,
        onRename = vm::rename,
        onSetCategory = vm::setCategory,
        onDelete = { vm.delete(onBack) },
    )
}

@Composable
fun AudioDetailsScreen(
    detail: ConversationDetail?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onRename: (String) -> Unit,
    onSetCategory: (ConversationCategory) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var categorizing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var userNotes by remember { mutableStateOf("") }
    var editingNotes by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = Dimens.ContentMaxWidth)
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            NishuTopBar(
                title = "Audio Details",
                onBack = onBack,
                actions = {
                    OverflowMenu(
                        actions = listOf(
                            MenuAction("Rename") { renaming = true },
                            MenuAction("Category") { categorizing = true },
                            MenuAction("Delete") { confirmDelete = true },
                        ),
                    )
                },
            )

            if (detail == null) {
                LoadingState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    // Top Card: Big green play button, filename, duration & size (Screen H in reference)
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().nishuCard(),
                            color = Color.Transparent,
                        ) {
                            Row(
                                modifier = Modifier.padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .background(NishuPalette.Mint)
                                        .clickable(onClick = onPlay),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = "Play",
                                        tint = NishuPalette.Primary,
                                        modifier = Modifier.size(30.dp),
                                    )
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "${detail.conversation.title}.wav",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "${detail.conversation.durationLabel} • 2.4 MB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // 3 Quick Action Buttons: Rename, Share, Delete
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            DetailActionButton(
                                icon = Icons.Rounded.Edit,
                                label = "Rename",
                                onClick = { renaming = true },
                            )
                            DetailActionButton(
                                icon = Icons.Rounded.Share,
                                label = "Share",
                                onClick = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, detail.conversation.title)
                                        putExtra(Intent.EXTRA_TEXT, detail.summaryBullets.joinToString("\n\n").ifBlank { detail.conversation.preview })
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share note"))
                                },
                            )
                            DetailActionButton(
                                icon = Icons.Rounded.DeleteOutline,
                                label = "Delete",
                                onClick = { confirmDelete = true },
                                iconColor = NishuPalette.Danger,
                            )
                        }
                    }

                    // Metadata Item List
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().nishuCard(),
                            color = Color.Transparent,
                        ) {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                MetadataRow(
                                    icon = Icons.Rounded.CalendarToday,
                                    label = "Date",
                                    value = detail.conversation.timestampLabel,
                                )
                                MetadataRow(
                                    icon = Icons.Rounded.Style,
                                    label = "Category",
                                    value = categoryLabel(detail.conversation.category),
                                    onClick = { categorizing = true },
                                )
                                MetadataRow(
                                    icon = Icons.Rounded.Memory,
                                    label = "STT Model",
                                    value = "Swift (Hindi/Hinglish)",
                                )
                                MetadataRow(
                                    icon = Icons.Rounded.Folder,
                                    label = "Storage",
                                    value = "On Device (Internal)",
                                )
                                MetadataRow(
                                    icon = Icons.Rounded.Language,
                                    label = "Language Detected",
                                    value = "Hindi + English",
                                )
                                MetadataRow(
                                    icon = Icons.Rounded.NoteAlt,
                                    label = "Notes",
                                    value = if (userNotes.isNotBlank()) userNotes else "Add notes...",
                                    onClick = { editingNotes = true },
                                )
                            }
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (renaming && detail != null) {
        TextInputDialog(
            title = "Rename file",
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

    if (editingNotes) {
        TextInputDialog(
            title = "Personal Notes",
            initial = userNotes,
            allowBlank = true,
            onConfirm = { userNotes = it; editingNotes = false },
            onDismiss = { editingNotes = false },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete recording?",
            message = "This audio file and its notes will be deleted.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DetailActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    iconColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = iconColor, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetadataRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AudioDetailsScreenPreview() = NishuTheme {
    AudioDetailsScreen(
        detail = PreviewData.detail,
        onBack = {},
        onPlay = {},
        onRename = {},
        onSetCategory = {},
        onDelete = {},
    )
}
