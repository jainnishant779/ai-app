package com.nishu.app.data.fake

import com.nishu.app.domain.model.*
import com.nishu.app.domain.repo.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

class FakeConversationRepository(private val store: FakeStore) : ConversationRepository {
    override fun counts(): Flow<HomeCounts> =
        combine(store.conversations, store.facts) { convs, facts ->
            HomeCounts(convs.size, convs.sumOf { c -> c.tasks.count { !it.done } }, facts.size)
        }

    override fun recent(limit: Int) = store.conversations.map { l -> l.take(limit).map { it.conversation } }

    override fun all(category: ConversationCategory?) = store.conversations.map { l ->
        l.map { it.conversation }.filter { category == null || it.category == category }
    }

    override fun detail(id: Long): Flow<ConversationDetail?> = combine(store.conversations, store.transcripts) { l, t ->
        l.firstOrNull { it.conversation.id == id }?.let { d ->
            d.copy(transcriptPreview = t[id].orEmpty().take(3))
        }
    }

    override fun transcript(id: Long) = store.transcripts.map { it[id].orEmpty() }

    override fun processing(id: Long): Flow<Map<ProcessingStage, StepState>> =
        combine(store.processing, store.conversations) { p, l ->
            p[id] ?: if (l.any { it.conversation.id == id && it.conversation.status == ConversationStatus.DONE }) {
                ProcessingStage.entries.associateWith { StepState.COMPLETED }
            } else {
                ProcessingStage.entries.associateWith { StepState.PENDING }
            }
        }

    override suspend fun setTaskDone(taskId: Long, done: Boolean) {
        store.conversations.update { l ->
            l.map { d -> d.copy(tasks = d.tasks.map { if (it.id == taskId) it.copy(done = done) else it }) }
        }
    }

    override suspend fun renameSpeaker(id: Long, label: String, name: String) = Unit
    override suspend fun setCategory(id: Long, category: ConversationCategory) = update(id) { it.copy(category = category) }
    override suspend fun rename(id: Long, title: String) = update(id) { it.copy(title = title) }

    override suspend fun deleteAudio(id: Long) {
        store.conversations.update { l -> l.map { if (it.conversation.id == id) it.copy(hasAudio = false) else it } }
    }

    override suspend fun delete(id: Long) {
        store.conversations.update { l -> l.filterNot { it.conversation.id == id } }
    }

    override suspend fun cancelProcessing(id: Long) = delete(id)
    override suspend fun retryProcessing(id: Long) = Unit
    override suspend fun audioPath(id: Long): String? = null

    private fun update(id: Long, f: (ConversationUiModel) -> ConversationUiModel) {
        store.conversations.update { l ->
            l.map { if (it.conversation.id == id) it.copy(conversation = f(it.conversation)) else it }
        }
    }
}

class FakeRecordingRepository(private val store: FakeStore, private val scope: CoroutineScope) : RecordingRepository {
    private val _state = MutableStateFlow(RecordingState())
    override val state: StateFlow<RecordingState> = _state
    private var job: Job? = null

    override suspend fun start() {
        _state.value = RecordingState(status = RecordingStatus.RECORDING)
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(100)
                _state.update { s ->
                    if (s.status != RecordingStatus.RECORDING) s else {
                        val level = 0.15f + Random.nextFloat() * 0.85f
                        s.copy(elapsedMs = s.elapsedMs + 100, bytes = s.bytes + 3_200, levels = (s.levels + level).takeLast(48))
                    }
                }
            }
        }
    }

    override suspend fun pause() = _state.update { it.copy(status = RecordingStatus.PAUSED) }
    override suspend fun resume() = _state.update { it.copy(status = RecordingStatus.RECORDING) }

    override suspend fun stopAndProcess(): Long {
        job?.cancel()
        val id = store.newRecording(_state.value.elapsedMs)
        _state.value = RecordingState()
        return id
    }

    override suspend fun discard() {
        job?.cancel()
        _state.value = RecordingState()
    }
}

class FakeMemoryRepository(private val store: FakeStore) : MemoryRepository {
    private var nextId = 1000L

    override fun facts(kind: MemoryKind?) = store.facts.map { l -> l.filter { kind == null || it.kind == kind } }

    override suspend fun add(text: String, kind: MemoryKind, sourceConversationId: Long?, sourceStartMs: Long?) {
        val label = if (sourceConversationId != null) "Saved from conversation" else "Added manually"
        store.facts.update { listOf(MemoryFactUiModel(nextId++, text, kind, label, "Just now")) + it }
    }

    override suspend fun updateKind(id: Long, kind: MemoryKind) {
        store.facts.update { l -> l.map { if (it.id == id) it.copy(kind = kind) else it } }
    }

    override suspend fun delete(id: Long) {
        store.facts.update { l -> l.filterNot { it.id == id } }
    }

    override fun ask(question: String): Flow<String> = flow {
        val words = "Based on your saved memory and recent conversations, you are working on the Nishu app and prefer Kotlin."
            .split(" ")
        var acc = ""
        for (w in words) {
            acc = if (acc.isEmpty()) w else "$acc $w"
            emit(acc)
            delay(60)
        }
    }
}

class FakeSearchRepository(private val store: FakeStore) : SearchRepository {
    override fun search(query: String): Flow<SearchResults> =
        combine(store.conversations, store.transcripts, store.facts) { convs, trans, facts ->
            val q = query.trim()
            if (q.isEmpty()) return@combine SearchResults()
            fun has(s: String) = s.contains(q, ignoreCase = true)
            SearchResults(
                conversations = convs.mapNotNull { d ->
                    val line = trans[d.conversation.id].orEmpty().firstOrNull { has(it.text) }?.text
                    val hit = line ?: d.conversation.preview.takeIf { has(it) } ?: d.conversation.title.takeIf { has(it) }
                    hit?.let { SearchHit(d.conversation.id, d.conversation.id, d.conversation.title, it) }
                },
                memory = facts.filter { has(it.text) }.map { SearchHit(it.id, null, it.text, it.sourceLabel) },
                tasks = convs.flatMap { d ->
                    d.tasks.filter { has(it.text) }.map { SearchHit(it.id, d.conversation.id, it.text, d.conversation.title) }
                },
            )
        }
}

class FakeSettingsRepository(private val store: FakeStore) : SettingsRepository {
    override val info: Flow<SettingsInfo> = store.userName.map {
        SettingsInfo(
            modelLabel = "Qwen3-0.6B (stock)", runtimeLabel = "llama.cpp (CPU)", soc = "Snapdragon 8 Gen 3",
            engineStatus = EngineStatus.READY, sttLabel = "whisper-tiny.en", languageLabel = "English (V0.1)",
            storageUsedBytes = 1_200_000_000, storageTotalBytes = 10_000_000_000, userName = it,
        )
    }

    override suspend fun setUserName(name: String) {
        store.userName.value = name
    }
}

class FakeBenchmarkRepository : BenchmarkRepository {
    private val sample = BenchmarkResultUiModel("Qwen3-0.6B (stock)", "llama.cpp (CPU)", "Snapdragon 8 Gen 3", 520, 8.4, 432, 2.1, 98)
    private val last = MutableStateFlow<BenchmarkResultUiModel?>(sample)

    override fun run(): Flow<BenchmarkProgress> = flow {
        val total = 21
        for (i in 0..total) {
            emit(BenchmarkProgress(i, total))
            delay(120)
        }
        last.value = sample
        emit(BenchmarkProgress(total, total, sample))
    }

    override fun last(): Flow<BenchmarkResultUiModel?> = last

    override fun diagnostics(): Flow<DiagnosticsInfo> = flowOf(
        DiagnosticsInfo(
            listOf(
                "Prefix tokens" to "294 (sample)", "Decode tok/s" to "8.4", "PSS" to "432 MB",
                "Prefix cache" to "hit", "Model sha" to "ac2d9771", "llama.cpp" to "b11312",
            ),
        ),
    )
}
