package com.nishu.app.ui.thinklist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.data.db.DecisionEntity
import com.nishu.app.data.db.MemoryFactEntity
import com.nishu.app.data.db.TaskEntity
import com.nishu.app.ui.components.EmptyState
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.components.SegmentedPillTabs
import com.nishu.app.ui.components.TextInputDialog
import com.nishu.app.ui.components.nishuCard
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.theme.NishuPalette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ThinkListUiState(
    val tasks: List<TaskEntity> = emptyList(),
    val decisions: List<DecisionEntity> = emptyList(),
    val notes: List<MemoryFactEntity> = emptyList(),
)

class ThinkListViewModel : ViewModel() {
    private val db = AppGraph.database

    val state: StateFlow<ThinkListUiState> = combine(
        db.tasks().observeAll(),
        db.decisions().observeAll(),
        db.memory().observeAll(),
    ) { tasks, decisions, notes ->
        ThinkListUiState(tasks, decisions, notes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThinkListUiState())

    fun toggleTask(id: Long, done: Boolean) {
        viewModelScope.launch {
            db.tasks().setDone(id, done)
        }
    }

    fun addTask(text: String, due: String? = null) {
        viewModelScope.launch {
            if (text.isNotBlank()) {
                val convId = db.conversations().recent(1).firstOrNull()?.id ?: 1L
                db.tasks().insert(
                    TaskEntity(
                        conversationId = convId,
                        text = text.trim(),
                        dueHint = due,
                        done = false,
                        extractionConfidence = "MANUAL",
                    ),
                )
            }
        }
    }
}

@Composable
fun ThinkListRoute() {
    val vm: ThinkListViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    ThinkListScreen(
        state = state,
        onToggleTask = vm::toggleTask,
        onAddTask = vm::addTask,
    )
}

@Composable
fun ThinkListScreen(
    state: ThinkListUiState,
    onToggleTask: (Long, Boolean) -> Unit,
    onAddTask: (String, String?) -> Unit,
) {
    var selectedCategory by rememberSaveable { mutableIntStateOf(0) } // 0: Tasks, 1: Decisions, 2: Notes
    var showAddDialog by remember { mutableStateOf(false) }
    var completedExpanded by remember { mutableStateOf(false) }

    val pendingTasks = state.tasks.filter { !it.done }
    val completedTasks = state.tasks.filter { it.done }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = Dimens.ContentMaxWidth)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(14.dp))
            // Top Bar with "+ Add" Action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenGutter, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Think-List",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Surface(
                    onClick = { showAddDialog = true },
                    shape = RoundedCornerShape(20.dp),
                    color = NishuPalette.Primary,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(18.dp))
                        Text(
                            "Add",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                    }
                }
            }

            // Category Tabs: Tasks | Decisions | Notes
            SegmentedPillTabs(
                tabs = listOf("Tasks", "Decisions", "Notes"),
                selectedIndex = selectedCategory,
                onSelect = { selectedCategory = it },
                modifier = Modifier.padding(horizontal = Dimens.ScreenGutter),
            )

            Spacer(Modifier.height(14.dp))

            when (selectedCategory) {
                0 -> {
                    // TASKS TAB (Screen 4 in design)
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Status Counters Row
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StatusCounterBadge(
                                    label = "Pending",
                                    count = pendingTasks.size,
                                    bgColor = Color(0xFFFEF3C7),
                                    textColor = Color(0xFFB45309),
                                    modifier = Modifier.weight(1f),
                                )
                                StatusCounterBadge(
                                    label = "In progress",
                                    count = minOf(pendingTasks.size, 1),
                                    bgColor = Color(0xFFE0F2FE),
                                    textColor = Color(0xFF0369A1),
                                    modifier = Modifier.weight(1f),
                                )
                                StatusCounterBadge(
                                    label = "Completed",
                                    count = completedTasks.size,
                                    bgColor = Color(0xFFDCFCE7),
                                    textColor = Color(0xFF15803D),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        if (state.tasks.isEmpty()) {
                            item {
                                EmptyState(
                                    icon = Icons.Rounded.CheckCircle,
                                    title = "No tasks yet",
                                    message = "Tasks mentioned in your conversations will appear here automatically.",
                                )
                            }
                        } else {
                            // High Priority Tasks
                            if (pendingTasks.isNotEmpty()) {
                                item {
                                    Text(
                                        "Action items",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }

                                items(pendingTasks, key = { it.id }) { task ->
                                    ThinkTaskItem(
                                        task = task,
                                        onToggle = { onToggleTask(task.id, it) },
                                    )
                                }
                            }

                            // Completed Tasks (Collapsible)
                            if (completedTasks.isNotEmpty()) {
                                item {
                                    Surface(
                                        onClick = { completedExpanded = !completedExpanded },
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.Transparent,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                "Completed (${completedTasks.size})",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Icon(
                                                if (completedExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }

                                if (completedExpanded) {
                                    items(completedTasks, key = { it.id }) { task ->
                                        ThinkTaskItem(
                                            task = task,
                                            onToggle = { onToggleTask(task.id, it) },
                                        )
                                    }
                                }
                            }
                        }

                        item { Spacer(Modifier.height(72.dp)) }
                    }
                }

                1 -> {
                    // DECISIONS TAB
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.decisions.isEmpty()) {
                            item {
                                EmptyState(
                                    icon = Icons.Rounded.CheckCircle,
                                    title = "No decisions recorded",
                                    message = "Key agreements and decisions made in meetings will show here.",
                                )
                            }
                        } else {
                            items(state.decisions, key = { it.id }) { dec ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth().nishuCard(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color(0xFFF3E8FF),
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text("◇", color = Color(0xFF7E22CE), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                            }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                dec.text,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(72.dp)) }
                    }
                }

                2 -> {
                    // NOTES TAB
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.notes.isEmpty()) {
                            item {
                                EmptyState(
                                    icon = Icons.Rounded.CheckCircle,
                                    title = "No notes or pinned facts",
                                    message = "Important facts saved from transcripts will appear here.",
                                )
                            }
                        } else {
                            items(state.notes, key = { it.id }) { note ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth().nishuCard(),
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(
                                            note.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = NishuPalette.Mint,
                                        ) {
                                            Text(
                                                note.kind,
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = NishuPalette.Primary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(72.dp)) }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        TextInputDialog(
            title = "Add new task",
            initial = "",
            confirmLabel = "Add Task",
            onConfirm = { text ->
                onAddTask(text, null)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun StatusCounterBadge(
    label: String,
    count: Int,
    bgColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                "$count",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = textColor,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = textColor,
            )
        }
    }
}

@Composable
private fun ThinkTaskItem(
    task: TaskEntity,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth().nishuCard(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(
                checked = task.done,
                onCheckedChange = onToggle,
                colors = CheckboxDefaults.colors(
                    checkedColor = NishuPalette.Primary,
                    uncheckedColor = MaterialTheme.colorScheme.outline,
                ),
            )

            Column(Modifier.weight(1f)) {
                Text(
                    task.text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (task.done) FontWeight.Normal else FontWeight.SemiBold,
                    ),
                    color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                )

                if (!task.dueHint.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Rounded.CalendarToday,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            task.dueHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (task.done) Color(0xFFF3F4F6) else Color(0xFFFEE2E2),
            ) {
                Text(
                    if (task.done) "Done" else "Action",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (task.done) Color(0xFF6B7280) else Color(0xFFDC2626),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
