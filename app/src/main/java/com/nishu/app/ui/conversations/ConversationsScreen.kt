package com.nishu.app.ui.conversations

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.nishu.app.domain.model.ConversationUiModel
import com.nishu.app.domain.repo.ConversationRepository
import com.nishu.app.ui.components.CategoryDialog
import com.nishu.app.ui.components.ConfirmDialog
import com.nishu.app.ui.components.EmptyState
import com.nishu.app.ui.components.MenuAction
import com.nishu.app.ui.components.NishuFilterChip
import com.nishu.app.ui.components.NishuSearchBar
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.OverflowMenu
import com.nishu.app.ui.components.TextInputDialog
import com.nishu.app.ui.components.nishuCard
import com.nishu.app.ui.components.pressScale
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuPalette
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
    timestampLabel == "Yesterday" -> "Yesterday"
    Regex("^[2-6] days ago").containsMatchIn(timestampLabel) -> "This Week"
    else -> "Earlier"
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModel(private val repo: ConversationRepository) : ViewModel() {
    private val filter = MutableStateFlow<ConversationCategory?>(null)
    private val searchQuery = MutableStateFlow("")

    val state: StateFlow<ConversationsUiState> = kotlinx.coroutines.flow.combine(filter, searchQuery) { f, q -> f to q }
        .flatMapLatest { (f, q) ->
            repo.all(f).map { list ->
                val filtered = if (q.isBlank()) list else list.filter { it.title.contains(q.trim(), ignoreCase = true) }
                val order = listOf("Today", "Yesterday", "This Week", "Earlier")
                val grouped = filtered.groupBy { groupFor(it.timestampLabel) }
                ConversationsUiState(
                    filter = f,
                    groups = order.mapNotNull { k -> grouped[k]?.let { k to it } },
                    isEmpty = filtered.isEmpty(),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationsUiState())

    fun setFilter(c: ConversationCategory?) { filter.value = c }
    fun setSearchQuery(q: String) { searchQuery.value = q }
    fun rename(id: Long, title: String) = viewModelScope.launch { repo.rename(id, title) }
    fun setCategory(id: Long, c: ConversationCategory) = viewModelScope.launch { repo.setCategory(id, c) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
}

@Composable
fun ConversationsRoute(onOpen: (Long) -> Unit, onSearch: () -> Unit) {
    val vm: ConversationsViewModel = viewModel(factory = vmFactory { ConversationsViewModel(AppGraph.conversations) })
    val state by vm.state.collectAsStateWithLifecycle()
    ConversationsScreen(
        state = state,
        onFilter = vm::setFilter,
        onSearchQuery = vm::setSearchQuery,
        onOpen = onOpen,
        onRename = vm::rename,
        onSetCategory = vm::setCategory,
        onDelete = vm::delete,
    )
}

@Composable
fun ConversationsScreen(
    state: ConversationsUiState,
    onFilter: (ConversationCategory?) -> Unit,
    onSearchQuery: (String) -> Unit,
    onOpen: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onSetCategory: (Long, ConversationCategory) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filterOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ConversationUiModel?>(null) }
    var categorizing by remember { mutableStateOf<ConversationUiModel?>(null) }
    var deleting by remember { mutableStateOf<ConversationUiModel?>(null) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize()) {

            // Top Bar: Back, "History"
            NishuTopBar("History")

            // Search Bar matching Screen I ("Search recordings..." + filter icon)
            Box(Modifier.padding(horizontal = Dimens.ScreenGutter, vertical = 4.dp)) {
                NishuSearchBar(
                    query = query,
                    onQueryChange = {
                        query = it
                        onSearchQuery(it)
                    },
                    placeholder = "Search recordings...",
                    trailing = {
                        IconButton(onClick = { filterOpen = !filterOpen }) {
                            Icon(
                                Icons.Rounded.FilterList,
                                contentDescription = "Filter",
                                tint = if (state.filter != null) NishuPalette.Primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }

            // Optional category filter row
            if (filterOpen || state.filter != null) {
                val chips = listOf<Pair<String, ConversationCategory?>>(
                    "All" to null,
                    "Meetings" to ConversationCategory.MEETING,
                    "Work" to ConversationCategory.WORK,
                    "Personal" to ConversationCategory.PERSONAL,
                    "Calls" to ConversationCategory.CALL,
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(chips) { (label, cat) ->
                        NishuFilterChip(label, state.filter == cat, { onFilter(cat) })
                    }
                }
            }

            if (state.isEmpty) {
                EmptyState(
                    icon = Icons.Rounded.History,
                    title = "No recordings found",
                    message = if (query.isNotBlank()) "No notes match '$query'." else "Your recorded voice notes will appear here.",
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.groups.forEach { (sectionTitle, items) ->
                        // Section Header: "Today", "Yesterday", "Earlier"
                        item(key = "header_$sectionTitle") {
                            Text(
                                text = sectionTitle,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                            )
                        }

                        items(items, key = { it.id }) { item ->
                            HistoryRecordingItem(
                                item = item,
                                onClick = { onOpen(item.id) },
                                onRename = { renaming = item },
                                onSetCategory = { categorizing = item },
                                onDelete = { deleting = item },
                            )
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    renaming?.let { item ->
        TextInputDialog(
            title = "Rename recording",
            initial = item.title,
            onConfirm = { onRename(item.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    categorizing?.let { item ->
        CategoryDialog(
            current = item.category,
            onSelect = { onSetCategory(item.id, it); categorizing = null },
            onDismiss = { categorizing = null },
        )
    }

    deleting?.let { item ->
        ConfirmDialog(
            title = "Delete recording?",
            message = "This recording and transcript will be deleted permanently.",
            confirmLabel = "Delete",
            onConfirm = { onDelete(item.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

/** Single row item matching Screen I in the visual reference */
@Composable
private fun HistoryRecordingItem(
    item: ConversationUiModel,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onSetCategory: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }

    Surface(
        onClick = onClick,
        interactionSource = source,
        modifier = modifier.fillMaxWidth().pressScale(source).nishuCard(),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Mint play circle
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(NishuPalette.Mint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = NishuPalette.Primary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${item.durationLabel} • Hindi/English",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = item.timestampLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.width(4.dp))

            OverflowMenu(
                actions = listOf(
                    MenuAction("Rename") { onRename() },
                    MenuAction("Category") { onSetCategory() },
                    MenuAction("Delete") { onDelete() },
                ),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HistoryScreenPreview() = NishuTheme {
    ConversationsScreen(
        state = ConversationsUiState(
            groups = listOf(
                "Today" to PreviewData.conversations.take(2),
                "Yesterday" to PreviewData.conversations.drop(2).take(2),
            ),
        ),
        onFilter = {},
        onSearchQuery = {},
        onOpen = {},
        onRename = { _, _ -> },
        onSetCategory = { _, _ -> },
        onDelete = {},
    )
}
