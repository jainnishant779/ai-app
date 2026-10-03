package com.nishu.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nishu.app.AppGraph
import com.nishu.app.data.ChatContext
import com.nishu.app.data.ChatScope
import com.nishu.app.llm.ChatMessage
import com.nishu.app.llm.EngineHolder
import com.nishu.app.llm.LLMEngine
import com.nishu.app.llm.ModelMissingException
import com.nishu.app.llm.Role
import com.nishu.app.llm.SamplerProfile
import com.nishu.app.ui.components.EmptyState
import com.nishu.app.ui.components.NishuFilterChip
import com.nishu.app.ui.components.NishuIconButton
import com.nishu.app.ui.components.NishuTopBar
import com.nishu.app.ui.theme.Dimens
import com.nishu.app.ui.vmFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatLine(val fromUser: Boolean, val text: String)

data class ChatUiState(
    val lines: List<ChatLine> = emptyList(),
    /** Null when idle; "" while waiting for the model; the partial reply while it streams. */
    val streaming: String? = null,
    val waitingForModel: Boolean = false,
)

/**
 * Free chat with the on-device model. The context is 1024 tokens, so only the most recent turns that fit are sent;
 * older ones stay on screen but the model no longer sees them.
 */
class ChatViewModel(private val scope: ChatScope = ChatScope.General) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state
    private var job: Job? = null

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || _state.value.streaming != null) return
        _state.update { it.copy(lines = it.lines + ChatLine(true, q), streaming = "", waitingForModel = true) }
        job = viewModelScope.launch {
            val reply = try {
                EngineHolder.withEngine { engine ->
                    _state.update { it.copy(waitingForModel = false) }
                    var acc = ""
                    val r = engine.generate(messagesFor(engine, q), SamplerProfile.Chat(), MAX_REPLY) { piece ->
                        acc += piece
                        _state.update { it.copy(streaming = acc) }
                    }
                    present(r.text)
                }
            } catch (e: CancellationException) {
                _state.value.streaming.orEmpty().ifBlank { "(stopped)" }
            } catch (e: ModelMissingException) {
                "The on-device model is not installed yet."
            } catch (e: Exception) {
                "Something went wrong: ${e.message}"
            }
            _state.update { it.copy(lines = it.lines + ChatLine(false, reply), streaming = null, waitingForModel = false) }
        }
    }

    fun stop() {
        EngineHolder.cancelCurrent()
        job?.cancel()
    }

    fun clear() {
        stop()
        _state.value = ChatUiState()
    }

    /**
     * The model sees: as many earlier turns as fit, then the question. For a recording (or all recordings) the
     * question carries the transcript parts it needs; that context is budgeted first, history gets what is left.
     */
    private suspend fun messagesFor(engine: LLMEngine, question: String): List<ChatMessage> {
        val room = engine.turnTokenBudget - MAX_REPLY - 24
        val context = ChatContext(AppGraph.database, engine::tokenCount)
            .build(scope, question, (room - engine.tokenCount(question) - 60).coerceAtMost(MAX_CONTEXT))
        val last = when {
            context != null -> "Neeche diye transcript ke hisaab se jawab do. Agar jawab isme nahi hai, to saaf bolo ki recording mein nahi hai.\n\n$context\n\nSawal: $question"
            scope != ChatScope.General -> "$question\n\n(Is sawaal se judi koi recording nahi mili.)"
            else -> question
        }
        val lastMsg = ChatMessage(Role.USER, last)
        val earlier = _state.value.lines.dropLast(1).map { ChatMessage(if (it.fromUser) Role.USER else Role.ASSISTANT, it.text) }
        fun cost(m: List<ChatMessage>) = engine.tokenCount(String(engine.template.renderTurns(m), Charsets.UTF_8))
        var kept = listOf(lastMsg)
        for (n in 1..earlier.size) {
            val candidate = earlier.takeLast(n) + lastMsg
            if (candidate.first().role != Role.USER) continue // a turn starts with the user
            if (cost(candidate) > room) break
            kept = candidate
        }
        return kept
    }

    /** Phone actions are not connected yet; say so instead of printing the raw call. */
    private fun present(raw: String): String {
        val text = raw.trim()
        val call = Regex("<tool_call>\\s*\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"").find(text) ?: return text.ifEmpty { "…" }
        val before = text.substringBefore("<tool_call>").trim()
        val note = "I would use ${call.groupValues[1].replace('_', ' ')} for this, but phone actions are not connected yet."
        return if (before.isEmpty()) note else "$before\n\n$note"
    }

    private companion object {
        const val MAX_REPLY = 220
        const val MAX_CONTEXT = 380
    }
}

private fun suggestionsFor(scope: ChatScope) = when (scope) {
    ChatScope.General -> listOf(
        "Tum kaun ho?",
        "Meeting ke liye 5 points ka agenda banao",
        "Explain inflation in simple words",
        "Translate to Hinglish: The client call moved to Friday.",
    )
    ChatScope.AllRecordings -> listOf(
        "Kal ki meeting mein kya decide hua?",
        "Budget ke baare mein kisne kya kaha?",
        "Mujhe kaunse kaam karne hain?",
    )
    is ChatScope.Recording -> listOf(
        "Is recording mein kya baat hui?",
        "Kaunse kaam kisko diye gaye?",
        "Kya koi date ya deadline bola gaya?",
    )
}

/**
 * The Chat tab: free chat or questions over all recordings. [recordingId] opens the chat of one recording instead
 * (from its detail screen), with [onBack] to return.
 */
@Composable
fun ChatRoute(recordingId: Long? = null, recordingTitle: String? = null, onBack: (() -> Unit)? = null) {
    var tabScope by rememberSaveable { mutableStateOf("general") }
    val scope: ChatScope = when {
        recordingId != null -> ChatScope.Recording(recordingId)
        tabScope == "all" -> ChatScope.AllRecordings
        else -> ChatScope.General
    }
    // One conversation per scope, so switching modes does not mix answers about recordings with free chat.
    val vm: ChatViewModel = viewModel(key = "chat-$scope", factory = vmFactory { ChatViewModel(scope) })
    val state by vm.state.collectAsStateWithLifecycle()
    ChatScreen(
        state = state,
        scope = scope,
        title = if (recordingId != null) "Ask about this recording" else "Chat",
        subtitle = recordingTitle ?: "On this phone, offline",
        onBack = onBack,
        onScope = if (recordingId == null) ({ tabScope = it }) else null,
        onSend = vm::send, onStop = vm::stop, onClear = vm::clear,
    )
}

@Composable
fun ChatScreen(
    state: ChatUiState,
    scope: ChatScope,
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    onScope: ((String) -> Unit)?,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val count = state.lines.size + if (state.streaming != null) 1 else 0
    LaunchedEffect(count, state.streaming?.length) { if (count > 0) listState.animateScrollToItem(count - 1) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.ContentMaxWidth).fillMaxSize().imePadding()) {
            NishuTopBar(
                title,
                subtitle = subtitle,
                onBack = onBack,
                actions = { if (state.lines.isNotEmpty()) NishuIconButton(Icons.Rounded.DeleteSweep, "Clear chat", onClear) },
            )
            if (onScope != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenGutter, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NishuFilterChip("Ask anything", scope == ChatScope.General, { onScope("general") })
                    NishuFilterChip("My recordings", scope == ChatScope.AllRecordings, { onScope("all") })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (count == 0) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = Dimens.ScreenGutter),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val (head, body) = when (scope) {
                            ChatScope.General -> "Ask Nishu anything" to "Hinglish or English, both work. Nothing leaves your phone."
                            ChatScope.AllRecordings -> "Ask about your recordings" to "Nishu searches your transcripts and answers from them."
                            is ChatScope.Recording -> "Ask about this recording" to "Answers come only from this recording's transcript."
                        }
                        EmptyState(Icons.Rounded.AutoAwesome, head, body)
                        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestionsFor(scope).forEach { s -> NishuFilterChip(s, false, { onSend(s) }) }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = Dimens.ScreenGutter, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(state.lines) { _, line -> Bubble(line.fromUser, line.text) }
                        if (state.streaming != null) {
                            item {
                                Bubble(
                                    false,
                                    when {
                                        state.waitingForModel -> "Waiting for the model… (a recording may be processing)"
                                        state.streaming.isEmpty() -> "Thinking…"
                                        else -> state.streaming
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenGutter, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Message Nishu") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.weight(1f),
                )
                val busy = state.streaming != null
                FilledIconButton(
                    onClick = {
                        if (busy) onStop() else {
                            onSend(input)
                            input = ""
                        }
                    },
                    enabled = busy || input.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(if (busy) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send, contentDescription = if (busy) "Stop" else "Send")
                }
            }
        }
    }
}

@Composable
private fun Bubble(fromUser: Boolean, text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (fromUser) 18.dp else 4.dp, bottomEnd = if (fromUser) 4.dp else 18.dp,
            ),
            color = if (fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shadowElevation = if (fromUser) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}
