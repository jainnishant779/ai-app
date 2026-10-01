package com.nishu.app.ui.memory

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.domain.model.MemoryFactUiModel
import com.nishu.app.domain.model.MemoryKind
import com.nishu.app.domain.repo.MemoryRepository
import com.nishu.app.domain.usecase.suggestFactKind
import com.nishu.app.ui.components.ConfirmDialog
import com.nishu.app.ui.components.EmptyState
import com.nishu.app.ui.components.MemoryCard
import com.nishu.app.ui.components.MenuAction
import com.nishu.app.ui.components.NishuFilterChip
import com.nishu.app.ui.components.NishuIconButton
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.PrimaryButton
import com.nishu.app.ui.components.nishuCard
import com.nishu.app.ui.preview.PreviewData
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuTheme
import com.nishu.app.ui.vmFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AskUiState(val answer: String = "", val isAsking: Boolean = false)

data class MemoryUiState(
    val filter: MemoryKind? = null,
    val facts: List<MemoryFactUiModel> = emptyList(),
    val ask: AskUiState = AskUiState(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModel(private val repo: MemoryRepository) : ViewModel() {
    private val filter = MutableStateFlow<MemoryKind?>(null)
    private val ask = MutableStateFlow(AskUiState())
    private var askJob: Job? = null

    val state: StateFlow<MemoryUiState> = combine(
        filter.flatMapLatest { f -> repo.facts(f) }, filter, ask,
    ) { facts, f, a -> MemoryUiState(f, facts, a) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryUiState())

    fun setFilter(k: MemoryKind?) { filter.value = k }

    fun add(text: String, kind: MemoryKind) = viewModelScope.launch { repo.add(text, kind) }
    fun updateKind(id: Long, kind: MemoryKind) = viewModelScope.launch { repo.updateKind(id, kind) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }

    fun ask(question: String) {
        if (question.isBlank()) return
        askJob?.cancel()
        ask.value = AskUiState("", true)
        askJob = viewModelScope.launch {
            repo.ask(question).collect { ask.value = AskUiState(it, true) }
            ask.value = ask.value.copy(isAsking = false)
        }
    }
}

@Composable
fun MemoryRoute(onSearch: () -> Unit) {
    val vm: MemoryViewModel = viewModel(factory = vmFactory { MemoryViewModel(AppGraph.memory) })
    val state by vm.state.collectAsStateWithLifecycle()
    MemoryScreen(state, vm::setFilter, onSearch, vm::add, vm::updateKind, vm::delete, vm::ask)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    state: MemoryUiState,
    onFilter: (MemoryKind?) -> Unit,
    onSearch: () -> Unit,
    onAdd: (String, MemoryKind) -> Unit,
    onChangeKind: (Long, MemoryKind) -> Unit,
    onDelete: (Long) -> Unit,
    onAsk: (String) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MemoryFactUiModel?>(null) }
    var question by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize()) {
            NishuTopBar("Memory", actions = { NishuIconButton(Icons.Rounded.Search, "Search", onSearch) })
            val chips = listOf<Pair<String, MemoryKind?>>(
                "All" to null, "Facts" to MemoryKind.FACT, "Preferences" to MemoryKind.PREFERENCE, "Contacts" to MemoryKind.CONTACT,
            )
            LazyRow(contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chips) { (label, k) -> NishuFilterChip(label, state.filter == k, { onFilter(k) }) }
            }
            LazyColumn(
                contentPadding = PaddingValues(start = Dimens.ScreenGutter, end = Dimens.ScreenGutter, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
            ) {
                item {
                    Column(Modifier.fillMaxWidth().nishuCard().padding(Dimens.CardPadding)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Text("  Ask Nishu", style = MaterialTheme.typography.titleSmall)
                        }
                        OutlinedTextField(
                            value = question,
                            onValueChange = { question = it },
                            placeholder = { Text("Ask about your conversations") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            trailingIcon = {
                                IconButton(onClick = { onAsk(question) }, enabled = !state.ask.isAsking) {
                                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Ask")
                                }
                            },
                        )
                        if (state.ask.answer.isNotEmpty() || state.ask.isAsking) {
                            Text(
                                state.ask.answer.ifEmpty { "Thinking…" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
                if (state.facts.isEmpty()) {
                    item {
                        EmptyState(
                            Icons.Rounded.Lightbulb, "Nothing saved yet",
                            "Long-press a transcript line to save it to memory, or tap + to add one.",
                        )
                    }
                }
                items(state.facts, key = { it.id }) { f ->
                    MemoryCard(
                        f,
                        menu = listOf(
                            MenuAction("Mark as Fact") { onChangeKind(f.id, MemoryKind.FACT) },
                            MenuAction("Mark as Preference") { onChangeKind(f.id, MemoryKind.PREFERENCE) },
                            MenuAction("Mark as Contact") { onChangeKind(f.id, MemoryKind.CONTACT) },
                            MenuAction("Delete") { deleting = f },
                        ),
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) { Icon(Icons.Rounded.Add, contentDescription = "Add to memory") }
    }

    if (showAdd) {
        AddFactSheet("", { text, kind -> onAdd(text, kind); showAdd = false }, { showAdd = false })
    }
    deleting?.let { f ->
        ConfirmDialog("Delete memory?", f.text, "Delete", { onDelete(f.id); deleting = null }, { deleting = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFactSheet(initialText: String, onSave: (String, MemoryKind) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialText) }
    var kind by remember { mutableStateOf(suggestFactKind(initialText)) }
    var kindTouched by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = Dimens.ScreenGutter).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Save to memory", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { contentDescription = "Save to memory" })
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    if (!kindTouched) kind = suggestFactKind(it)
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(MemoryKind.FACT to "Fact", MemoryKind.PREFERENCE to "Preference", MemoryKind.CONTACT to "Contact").forEach { (k, label) ->
                    NishuFilterChip(label, kind == k, { kind = k; kindTouched = true })
                }
            }
            PrimaryButton("Save", onClick = { if (text.isNotBlank()) onSave(text.trim(), kind) }, enabled = text.isNotBlank())
        }
    }
}

@Preview(widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun MemoryPreview() = NishuTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
        MemoryScreen(MemoryUiState(null, PreviewData.facts, AskUiState()), {}, {}, { _, _ -> }, { _, _ -> }, {}, {})
    }
}
