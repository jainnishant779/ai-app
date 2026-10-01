package com.nishu.app.ui.conversation

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.domain.model.ConversationCategory
import com.nishu.app.domain.model.ConversationDetail
import com.nishu.app.domain.model.ConversationStatus
import com.nishu.app.domain.repo.ConversationRepository
import com.nishu.app.ui.components.CategoryDialog
import com.nishu.app.ui.components.ConfirmDialog
import com.nishu.app.ui.components.DecisionCard
import com.nishu.app.ui.components.EmptyState
import com.nishu.app.ui.components.ErrorState
import com.nishu.app.ui.components.KeyPointRow
import com.nishu.app.ui.components.LinkRow
import com.nishu.app.ui.components.LoadingState
import com.nishu.app.ui.components.MenuAction
import com.nishu.app.ui.components.NishuIconButton
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.OverflowMenu
import com.nishu.app.ui.components.SectionHeader
import com.nishu.app.ui.components.SummaryCard
import com.nishu.app.ui.components.TaskCard
import com.nishu.app.ui.components.TextInputDialog
import com.nishu.app.ui.components.nishuCard
import com.nishu.app.ui.formatClock
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuTheme
import com.nishu.app.ui.vmFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConversationDetailViewModel(private val id: Long, private val repo: ConversationRepository) : ViewModel() {
    val state: StateFlow<ConversationDetail?> = repo.detail(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setTaskDone(taskId: Long, done: Boolean) = viewModelScope.launch { repo.setTaskDone(taskId, done) }
    fun rename(title: String) = viewModelScope.launch { repo.rename(id, title) }
    fun setCategory(c: ConversationCategory) = viewModelScope.launch { repo.setCategory(id, c) }
    fun deleteAudio() = viewModelScope.launch { repo.deleteAudio(id) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { repo.delete(id); onDone() }
}

@Composable
fun ConversationDetailRoute(id: Long, onBack: () -> Unit, onTranscript: (Long) -> Unit) {
    val vm: ConversationDetailViewModel = viewModel(
        key = "detail_$id", factory = vmFactory { ConversationDetailViewModel(id, AppGraph.conversations) },
    )
    val detail by vm.state.collectAsStateWithLifecycle()
    ConversationDetailScreen(
        detail = detail, onBack = onBack, onTranscript = { onTranscript(id) },
        onToggleTask = vm::setTaskDone, onRename = vm::rename, onSetCategory = vm::setCategory,
        onDeleteAudio = vm::deleteAudio, onDelete = { vm.delete(onBack) },
    )
}

private val tabNames = listOf("Summary", "Transcript", "Tasks", "Decisions")

@Composable
fun ConversationDetailScreen(
    detail: ConversationDetail?,
    onBack: () -> Unit,
    onTranscript: () -> Unit,
    onToggleTask: (Long, Boolean) -> Unit,
    onRename: (String) -> Unit,
    onSetCategory: (ConversationCategory) -> Unit,
    onDeleteAudio: () -> Unit,
    onDelete: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf(false) }
    var categorizing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteAudio by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize()) {
            NishuTopBar(
                title = "", onBack = onBack,
                actions = {
                    NishuIconButton(Icons.Rounded.Edit, "Rename", { renaming = true })
                    OverflowMenu(
                        buildList {
                            add(MenuAction("Set category") { categorizing = true })
                            if (detail?.hasAudio == true) add(MenuAction("Delete audio, keep transcript") { confirmDeleteAudio = true })
                            add(MenuAction("Delete") { confirmDelete = true })
                        },
                    )
                },
            )
            when {
                detail == null -> LoadingState()
                detail.conversation.status == ConversationStatus.FAILED -> ErrorState("Summary unavailable. Your transcript was saved.")
                else -> {
                    Column(Modifier.padding(horizontal = Dimens.ScreenGutter)) {
                        Text(detail.conversation.title, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${detail.conversation.timestampLabel} • ${detail.conversation.durationLabel}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TabRow(
                        selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        tabNames.forEachIndexed { i, name ->
                            val count = when (i) { 2 -> detail.tasks.size; 3 -> detail.decisions.size; else -> null }
                            Tab(
                                selected = tab == i, onClick = { tab = i },
                                selectedContentColor = MaterialTheme.colorScheme.primary,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = { Text(if (count != null) "$name ($count)" else name, style = MaterialTheme.typography.labelLarge, maxLines = 1) },
                            )
                        }
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
                    ) {
                        if (detail.hasLowConfidence && tab >= 2) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                    Text(
                                        "  Some items were found by simple rules and may be inaccurate.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                        when (tab) {
                            0 -> {
                                if (detail.summaryBullets.isEmpty()) {
                                    item { EmptyState(Icons.Rounded.Checklist, "Summary unavailable", "The transcript is still available.") }
                                } else {
                                    item { SummaryCard(detail.summaryBullets) }
                                    item { SectionHeader("Key Points", Modifier.padding(top = 8.dp)) }
                                    item {
                                        Column(Modifier.fillMaxWidth().nishuCard().padding(horizontal = Dimens.CardPadding, vertical = 8.dp)) {
                                            detail.keyPoints.forEach { KeyPointRow(it) }
                                        }
                                    }
                                    item { LinkRow("View Full Transcript", onTranscript) }
                                }
                            }
                            1 -> {
                                if (detail.transcriptPreview.isEmpty()) {
                                    item { EmptyState(Icons.Rounded.Checklist, "No transcript", "Nothing was transcribed for this recording.") }
                                } else {
                                    detail.transcriptPreview.forEach { line ->
                                        item {
                                            Row(Modifier.fillMaxWidth().nishuCard().padding(Dimens.CardPadding)) {
                                                Text(formatClock(line.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
                                                Text(line.text, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                    item { LinkRow("View Full Transcript", onTranscript) }
                                }
                            }
                            2 -> {
                                if (detail.tasks.isEmpty()) {
                                    item { EmptyState(Icons.Rounded.Checklist, "No action items found", "Nothing in this conversation sounded like a task.") }
                                }
                                detail.tasks.forEach { t -> item(key = "t${t.id}") { TaskCard(t, { onToggleTask(t.id, it) }) } }
                            }
                            else -> {
                                if (detail.decisions.isEmpty()) {
                                    item { EmptyState(Icons.Rounded.Checklist, "No decisions found", "Nothing in this conversation sounded like a decision.") }
                                }
                                detail.decisions.forEach { d -> item(key = "d${d.id}") { DecisionCard(d) } }
                            }
                        }
                    }
                }
            }
        }
    }

    if (renaming && detail != null) {
        TextInputDialog("Rename", detail.conversation.title, "Save", { onRename(it); renaming = false }, { renaming = false })
    }
    if (categorizing && detail != null) {
        CategoryDialog(detail.conversation.category, { onSetCategory(it); categorizing = false }, { categorizing = false })
    }
    if (confirmDelete) {
        ConfirmDialog("Delete conversation?", "This removes the audio, transcript and summary.", "Delete", { confirmDelete = false; onDelete() }, { confirmDelete = false })
    }
    if (confirmDeleteAudio) {
        ConfirmDialog("Delete audio?", "The transcript and summary stay.", "Delete audio", { confirmDeleteAudio = false; onDeleteAudio() }, { confirmDeleteAudio = false })
    }
}

@Preview(widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun ConversationDetailPreview() = NishuTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
        ConversationDetailScreen(PreviewData.detail, {}, {}, { _, _ -> }, {}, {}, {}, {})
    }
}
