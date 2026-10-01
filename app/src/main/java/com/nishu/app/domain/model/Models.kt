package com.nishu.app.domain.model

enum class ConversationCategory { PERSONAL, WORK, CALL, MEETING, OTHER }

enum class ConversationStatus { RECORDING, RECORDED, TRANSCRIBING, TRANSCRIBED, SUMMARIZING, DONE, FAILED }

enum class ProcessingStage { TRANSCRIBING, SUMMARIZING, EXTRACTING_TASKS, IDENTIFYING_DECISIONS, SAVING }

enum class StepState { PENDING, RUNNING, COMPLETED, FAILED }

/** HIGH = model JSON output; LOW = salvaged JSON or heuristic rules. */
enum class Confidence { HIGH, LOW }

enum class MemoryKind { FACT, PREFERENCE, CONTACT, OTHER }

enum class RecordingStatus { IDLE, RECORDING, PAUSED }

enum class EngineStatus { READY, NOT_LOADED, MODEL_MISSING }

data class ConversationUiModel(
    val id: Long,
    val title: String,
    val category: ConversationCategory,
    val timestampLabel: String,
    val durationLabel: String,
    val preview: String,
    val status: ConversationStatus,
)

data class HomeCounts(val recordings: Int, val openTasks: Int, val facts: Int)

/** [label] is the stable key ("S1"); [name] is what to show ("Speaker 1", or whatever the user renamed it to). */
data class SpeakerRef(val label: String, val name: String) {
    /** 0-based index used to pick a colour, so a speaker keeps the same colour on every screen. */
    val index: Int get() = (label.drop(1).toIntOrNull() ?: 1) - 1
}

data class TranscriptLine(val startMs: Long, val text: String, val speaker: SpeakerRef? = null)

data class TaskUiModel(
    val id: Long,
    val text: String,
    val dueHint: String?,
    val done: Boolean,
    val confidence: Confidence,
)

data class DecisionUiModel(val id: Long, val text: String, val confidence: Confidence)

data class ConversationDetail(
    val conversation: ConversationUiModel,
    val summaryBullets: List<String>,
    val keyPoints: List<String>,
    val tasks: List<TaskUiModel>,
    val decisions: List<DecisionUiModel>,
    val transcriptPreview: List<TranscriptLine>,
    val statusDetail: String?,
    val hasAudio: Boolean,
) {
    val hasLowConfidence: Boolean
        get() = tasks.any { it.confidence == Confidence.LOW } || decisions.any { it.confidence == Confidence.LOW }
}

data class MemoryFactUiModel(
    val id: Long,
    val text: String,
    val kind: MemoryKind,
    val sourceLabel: String,
    val timeLabel: String,
)

data class SearchHit(val id: Long, val conversationId: Long?, val title: String, val snippet: String)

data class SearchResults(
    val conversations: List<SearchHit> = emptyList(),
    val memory: List<SearchHit> = emptyList(),
    val tasks: List<SearchHit> = emptyList(),
) {
    val isEmpty: Boolean get() = conversations.isEmpty() && memory.isEmpty() && tasks.isEmpty()
}

data class RecordingState(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val elapsedMs: Long = 0,
    val bytes: Long = 0,
    val levels: List<Float> = emptyList(),
    val conversationId: Long? = null,
)

data class SettingsInfo(
    val modelLabel: String,
    val runtimeLabel: String,
    val soc: String,
    val engineStatus: EngineStatus,
    val sttLabel: String,
    val languageLabel: String,
    val storageUsedBytes: Long,
    val storageTotalBytes: Long,
    val userName: String,
)

data class BenchmarkResultUiModel(
    val model: String,
    val runtime: String,
    val soc: String,
    val ttftMs: Int,
    val tokensPerSec: Double,
    val peakMemoryMb: Int,
    val avgPowerW: Double?,
    val jsonSuccessPct: Int,
)

data class BenchmarkProgress(val done: Int, val total: Int, val result: BenchmarkResultUiModel? = null)

data class DiagnosticsInfo(val rows: List<Pair<String, String>>)
