package com.nishu.app.ui.preview

import com.nishu.app.data.fake.FakeStore
import com.nishu.app.domain.model.*

/** Realistic sample data for @Preview functions only. */
object PreviewData {
    private val seed = FakeStore.seedConversations()
    val conversations: List<ConversationUiModel> = seed.map { it.conversation }
    val detail: ConversationDetail = seed.first().copy(
        transcriptPreview = FakeStore.seedTranscripts().getValue(1L).take(3),
    )
    val transcript: List<TranscriptLine> = FakeStore.seedTranscripts().getValue(1L)
    val facts: List<MemoryFactUiModel> = FakeStore.seedFacts()
    val levels: List<Float> = List(48) { i -> 0.2f + 0.8f * ((i * 37 % 100) / 100f) }
    val steps: Map<ProcessingStage, StepState> = mapOf(
        ProcessingStage.TRANSCRIBING to StepState.COMPLETED,
        ProcessingStage.SUMMARIZING to StepState.RUNNING,
        ProcessingStage.EXTRACTING_TASKS to StepState.PENDING,
        ProcessingStage.IDENTIFYING_DECISIONS to StepState.PENDING,
        ProcessingStage.SAVING to StepState.PENDING,
    )
    val settings = SettingsInfo(
        "Qwen3-0.6B (stock)", "llama.cpp (CPU)", "Snapdragon 8 Gen 3", EngineStatus.READY,
        "whisper-tiny.en", "English (V0.1)", 1_200_000_000, 10_000_000_000, "Nishant",
    )
    val benchmark = BenchmarkResultUiModel("Qwen3-0.6B (stock)", "llama.cpp (CPU)", "Snapdragon 8 Gen 3", 520, 8.4, 432, 2.1, 98)
}
