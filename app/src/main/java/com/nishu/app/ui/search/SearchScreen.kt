package com.nishu.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.domain.model.SearchHit
import com.nishu.app.domain.model.SearchResults
import com.nishu.app.domain.repo.SearchRepository
import com.nishu.app.ui.components.EmptyState
import com.nishu.app.ui.components.NishuFilterChip
import com.nishu.app.ui.components.NishuSearchBar
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.nishuCard
import com.nishu.app.ui.highlight
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuTheme
import com.nishu.app.ui.vmFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

enum class SearchFilter { ALL, CONVERSATIONS, MEMORY, TASKS }

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val results: SearchResults = SearchResults(),
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(repo: SearchRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(SearchFilter.ALL)

    val state: StateFlow<SearchUiState> = combine(
        query, filter, query.debounce(250).flatMapLatest { repo.search(it) },
    ) { q, f, r -> SearchUiState(q, f, r) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(q: String) { query.value = q }
    fun setFilter(f: SearchFilter) { filter.value = f }
}

@Composable
fun SearchRoute(onBack: () -> Unit, onOpenConversation: (Long) -> Unit, onOpenMemory: () -> Unit) {
    val vm: SearchViewModel = viewModel(factory = vmFactory { SearchViewModel(AppGraph.search) })
    val state by vm.state.collectAsStateWithLifecycle()
    SearchScreen(state, vm::setQuery, vm::setFilter, onBack, onOpenConversation, onOpenMemory)
}

@Composable
private fun ResultGroup(title: String, hits: List<SearchHit>, query: String, onClick: (SearchHit) -> Unit) {
    val hl = MaterialTheme.colorScheme.primaryContainer
    Text("$title (${hits.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    hits.forEach { h ->
        Column(Modifier.fillMaxWidth().nishuCard().clickable { onClick(h) }.padding(Dimens.CardPadding)) {
            Text(h.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                highlight(h.snippet, query, hl), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SearchScreen(
    state: SearchUiState,
    onQuery: (String) -> Unit,
    onFilter: (SearchFilter) -> Unit,
    onBack: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onOpenMemory: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize()) {
            NishuTopBar("Search", onBack = onBack)
            NishuSearchBar(state.query, onQuery, "Search conversations, memory, tasks", Modifier.padding(horizontal = Dimens.ScreenGutter), autoFocus = true)
            LazyRow(
                contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SearchFilter.entries) { f ->
                    NishuFilterChip(f.name.lowercase().replaceFirstChar { it.uppercase() }, state.filter == f, { onFilter(f) })
                }
            }
            val r = state.results
            val show = { f: SearchFilter -> state.filter == SearchFilter.ALL || state.filter == f }
            val nothing = (!show(SearchFilter.CONVERSATIONS) || r.conversations.isEmpty()) &&
                (!show(SearchFilter.MEMORY) || r.memory.isEmpty()) && (!show(SearchFilter.TASKS) || r.tasks.isEmpty())
            if (state.query.isNotBlank() && nothing) {
                EmptyState(Icons.Rounded.SearchOff, "No results", "Nothing matched \"${state.query.trim()}\".")
            }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
            ) {
                if (show(SearchFilter.CONVERSATIONS) && r.conversations.isNotEmpty()) {
                    item { ResultGroup("Conversations", r.conversations, state.query) { h -> h.conversationId?.let(onOpenConversation) } }
                }
                if (show(SearchFilter.MEMORY) && r.memory.isNotEmpty()) {
                    item { ResultGroup("Memory", r.memory, state.query) { onOpenMemory() } }
                }
                if (show(SearchFilter.TASKS) && r.tasks.isNotEmpty()) {
                    item { ResultGroup("Tasks", r.tasks, state.query) { h -> h.conversationId?.let(onOpenConversation) } }
                }
            }
        }
    }
}

@Preview(widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun SearchPreview() = NishuTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
        SearchScreen(
            SearchUiState(
                "project", SearchFilter.ALL,
                SearchResults(
                    conversations = listOf(SearchHit(1, 1, "Team meeting discussion", "...new project timeline and client...")),
                    memory = listOf(SearchHit(4, null, "I am building an AI app called Nishu", "Project • Saved from conversation")),
                ),
            ),
            {}, {}, {}, {}, {},
        )
    }
}
