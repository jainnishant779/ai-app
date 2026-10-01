package com.nishu.app.ui.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.domain.model.ConversationCategory
import com.nishu.app.domain.model.ConversationUiModel
import com.nishu.app.domain.repo.ConversationRepository
import com.nishu.app.ui.components.CategoryDialog
import com.nishu.app.ui.components.ConfirmDialog
import com.nishu.app.ui.components.ConversationCard
import com.nishu.app.ui.components.EmptyState
import com.nishu.app.ui.components.MenuAction
import com.nishu.app.ui.components.NishuFilterChip
import com.nishu.app.ui.components.NishuIconButton
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.TextInputDialog
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuTheme
import com.nishu.app.ui.vmFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationsUiState(
    val filter: ConversationCategory? = null,
    val groups: List<Pair<String, List<ConversationUiModel>>> = emptyList(),
    val isEmpty: Boolean = false,
)

private fun groupFor(timestampLabel: String): String = when {
    timestampLabel.startsWith("Today") || timestampLabel == "Just now" -> "Today"
    timestampLabel == "Yesterday" ||
        Regex("^[2-6] days ago").containsMatchIn(timestampLabel) -> "This Week"
    else -> "Earlier"
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModel(private val repo: ConversationRepository) : ViewModel() {
    private val filter = MutableStateFlow<ConversationCategory?>(null)

    val state: StateFlow<ConversationsUiState> = filter
        .flatMapLatest { f ->
            repo.all(f).map { list ->
                val order = listOf("Today", "This Week", "Earlier")
                val grouped = list.groupBy { groupFor(it.timestampLabel) }
                ConversationsUiState(
                    filter = f,
                    groups = order.mapNotNull { k -> grouped[k]?.let { k to it } },
                    isEmpty = list.isEmpty(),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationsUiState())

    fun setFilter(c: ConversationCategory?) { filter.value = c }
    fun rename(id: Long, title: String) = viewModelScope.launch { repo.rename(id, title) }
    fun setCategory(id: Long, c: ConversationCategory) = viewModelScope.launch { repo.setCategory(id, c) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
}

@Composable
fun ConversationsRoute(onOpen: (Long) -> Unit, onSearch: () -> Unit) {
    val vm: ConversationsViewModel = viewModel(factory = vmFactory { ConversationsViewModel(AppGraph.conversations) })
    val state by vm.state.collectAsStateWithLifecycle()
    ConversationsScreen(state, vm::setFilter, onOpen, onSearch, vm::rename, vm::setCategory, vm::delete)
}

@Composable
fun ConversationsScreen(
    state: ConversationsUiState,
    onFilter: (ConversationCategory?) -> Unit,
    onOpen: (Long) -> Unit,
    onSearch: () -> Unit,
    onRename: (Long, String) -> Unit,
    onSetCategory: (Long, ConversationCategory) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var renaming by remember { mutableStateOf<ConversationUiModel?>(null) }
    var categorizing by remember { mutableStateOf<ConversationUiModel?>(null) }
    var deleting by remember { mutableStateOf<ConversationUiModel?>(null) }

    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize()) {
            NishuTopBar("Conversations", actions = { NishuIconButton(Icons.Rounded.Search, "Search", onSearch) })
            val chips = listOf<Pair<String, ConversationCategory?>>(
                "All" to null, "Personal" to ConversationCategory.PERSONAL, "Work" to ConversationCategory.WORK,
                "Calls" to ConversationCategory.CALL, "Meetings" to ConversationCategory.MEETING,
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chips) { (label, cat) -> NishuFilterChip(label, state.filter == cat, { onFilter(cat) }) }
            }
            if (state.isEmpty) {
                EmptyState(Icons.Rounded.Forum, "No conversations yet", "Tap Start Recording on Home to capture your first one.")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
                ) {
                    state.groups.forEach { (title, items) ->
                        item(key = "h_$title") {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(items, key = { it.id }) { c ->
                            ConversationCard(
                                c, onClick = { onOpen(c.id) },
                                menu = listOf(
                                    MenuAction("Rename") { renaming = c },
                                    MenuAction("Set category") { categorizing = c },
                                    MenuAction("Delete") { deleting = c },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    renaming?.let { c ->
        TextInputDialog("Rename", c.title, "Save", { onRename(c.id, it); renaming = null }, { renaming = null })
    }
    categorizing?.let { c ->
        CategoryDialog(c.category, { onSetCategory(c.id, it); categorizing = null }, { categorizing = null })
    }
    deleting?.let { c ->
        ConfirmDialog("Delete conversation?", "This removes the audio, transcript and summary.", "Delete", { onDelete(c.id); deleting = null }, { deleting = null })
    }
}

@Preview(widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun ConversationsPreview() = NishuTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
        ConversationsScreen(
            ConversationsUiState(null, listOf("Today" to PreviewData.conversations.take(2), "This Week" to PreviewData.conversations.drop(2))),
            {}, {}, {}, { _, _ -> }, { _, _ -> }, {},
        )
    }
}
