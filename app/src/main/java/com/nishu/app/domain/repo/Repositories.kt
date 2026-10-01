package com.nishu.app.domain.repo

import com.nishu.app.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ConversationRepository {
    fun counts(): Flow<HomeCounts>
    fun recent(limit: Int): Flow<List<ConversationUiModel>>
    fun all(category: ConversationCategory?): Flow<List<ConversationUiModel>>
    fun detail(id: Long): Flow<ConversationDetail?>
    fun transcript(id: Long): Flow<List<TranscriptLine>>
    fun processing(id: Long): Flow<Map<ProcessingStage, StepState>>
    suspend fun setTaskDone(taskId: Long, done: Boolean)
    suspend fun setCategory(id: Long, category: ConversationCategory)
    suspend fun rename(id: Long, title: String)
    suspend fun deleteAudio(id: Long)
    suspend fun delete(id: Long)
    suspend fun cancelProcessing(id: Long)
    /** Path of the recorded WAV, for the transcript player; null if the audio was deleted. */
    suspend fun audioPath(id: Long): String?
}

interface RecordingRepository {
    val state: StateFlow<RecordingState>
    suspend fun start()
    suspend fun pause()
    suspend fun resume()
    /** Stops, enqueues processing and returns the conversation id. */
    suspend fun stopAndProcess(): Long
    suspend fun discard()
}

interface MemoryRepository {
    fun facts(kind: MemoryKind?): Flow<List<MemoryFactUiModel>>
    suspend fun add(text: String, kind: MemoryKind, sourceConversationId: Long? = null, sourceStartMs: Long? = null)
    suspend fun updateKind(id: Long, kind: MemoryKind)
    suspend fun delete(id: Long)
    fun ask(question: String): Flow<String>
}

interface SearchRepository {
    fun search(query: String): Flow<SearchResults>
}

interface SettingsRepository {
    val info: Flow<SettingsInfo>
    suspend fun setUserName(name: String)
}

interface BenchmarkRepository {
    fun run(): Flow<BenchmarkProgress>
    fun last(): Flow<BenchmarkResultUiModel?>
    fun diagnostics(): Flow<DiagnosticsInfo>
}
