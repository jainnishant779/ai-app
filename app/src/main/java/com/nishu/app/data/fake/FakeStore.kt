package com.nishu.app.data.fake

import com.nishu.app.domain.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Shared in-memory state so the fake repositories behave like one coherent app. */
class FakeStore(private val scope: CoroutineScope) {
    val conversations = MutableStateFlow(seedConversations())
    val transcripts = MutableStateFlow(seedTranscripts())
    val facts = MutableStateFlow(seedFacts())
    val userName = MutableStateFlow("Nishant")
    val processing = MutableStateFlow<Map<Long, Map<ProcessingStage, StepState>>>(emptyMap())
    private var nextId = 100L

    fun newRecording(durationMs: Long): Long {
        val id = nextId++
        val minutes = (durationMs / 60_000).coerceAtLeast(1)
        val conv = ConversationUiModel(
            id = id, title = "Recording, Oct 1 3:42 PM", category = ConversationCategory.OTHER,
            timestampLabel = "Just now", durationLabel = "$minutes min", preview = "",
            status = ConversationStatus.TRANSCRIBING,
        )
        conversations.update {
            listOf(ConversationDetail(conv, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, true)) + it
        }
        startProcessing(id)
        return id
    }

    private fun startProcessing(id: Long) {
        scope.launch {
            val stages = ProcessingStage.entries
            val state = stages.associateWith { StepState.PENDING }.toMutableMap()
            for (stage in stages) {
                state[stage] = StepState.RUNNING
                processing.update { it + (id to state.toMap()) }
                delay(1500)
                state[stage] = StepState.COMPLETED
                processing.update { it + (id to state.toMap()) }
            }
            conversations.update { list ->
                list.map { d ->
                    if (d.conversation.id != id) d else d.copy(
                        conversation = d.conversation.copy(
                            status = ConversationStatus.DONE,
                            preview = "Discussed the sprint plan and release timeline.",
                        ),
                        summaryBullets = listOf(
                            "Reviewed the sprint plan and release timeline.",
                            "Agreed to ship the first build by Friday.",
                            "Open question on who owns testing.",
                        ),
                        keyPoints = listOf("Sprint plan reviewed", "Ship first build by Friday", "Testing owner unclear"),
                        tasks = listOf(TaskUiModel(id * 10, "Ship first build", "Friday", false, Confidence.HIGH)),
                        decisions = listOf(DecisionUiModel(id * 10 + 1, "Release first build on Friday", Confidence.HIGH)),
                        transcriptPreview = listOf(TranscriptLine(0, "okay let us review the sprint plan")),
                    )
                }
            }
            transcripts.update { it + (id to listOf(TranscriptLine(0, "okay let us review the sprint plan"))) }
        }
    }

    companion object {
        private fun conv(
            id: Long, title: String, cat: ConversationCategory, ts: String, dur: String, preview: String,
        ) = ConversationUiModel(id, title, cat, ts, dur, preview, ConversationStatus.DONE)

        fun seedConversations(): List<ConversationDetail> = listOf(
            ConversationDetail(
                conversation = conv(
                    1, "Team meeting discussion", ConversationCategory.MEETING, "Today, 5:30 PM", "12 min",
                    "Discussed the new project timeline, client requirements and budget.",
                ),
                summaryBullets = listOf(
                    "Discussed the new project timeline, client requirements and budget.",
                    "Decided to create a presentation and send it to the client by Monday.",
                    "Need to include budget breakdown and team allocation.",
                ),
                keyPoints = listOf(
                    "New project timeline discussed",
                    "Client requirements and budget reviewed",
                    "Presentation to be sent by Monday",
                    "Team allocation to be finalized",
                ),
                tasks = listOf(
                    TaskUiModel(11, "Create project presentation", "Due Mon", false, Confidence.HIGH),
                    TaskUiModel(12, "Include budget breakdown", "Due Mon", false, Confidence.HIGH),
                    TaskUiModel(13, "Share with client", "Due Mon", false, Confidence.LOW),
                ),
                decisions = listOf(
                    DecisionUiModel(21, "Send the presentation to the client by Monday", Confidence.HIGH),
                    DecisionUiModel(22, "Confirm team allocation before the review", Confidence.LOW),
                ),
                transcriptPreview = emptyList(),
                statusDetail = null,
                hasAudio = true,
            ),
            ConversationDetail(
                conv(2, "Call with Mom", ConversationCategory.CALL, "Today, 1:20 PM", "8 min", "Talked about the weekend visit."),
                listOf("Planned the weekend visit to Delhi.", "Mom asked to bring sweets."), listOf("Weekend visit planned"),
                listOf(TaskUiModel(14, "Book train tickets", "Due Fri", false, Confidence.HIGH)),
                emptyList(), emptyList(), null, true,
            ),
            ConversationDetail(
                conv(3, "Project planning", ConversationCategory.WORK, "Yesterday", "15 min", "Mapped milestones for the next quarter."),
                listOf("Mapped milestones for the next quarter."), listOf("Q4 milestones mapped"),
                emptyList(), emptyList(), emptyList(), null, true,
            ),
            ConversationDetail(
                conv(4, "Interview preparation", ConversationCategory.PERSONAL, "2 days ago", "18 min", "Practised system design questions."),
                listOf("Practised system design questions."), listOf("System design practice"),
                emptyList(), emptyList(), emptyList(), null, true,
            ),
            ConversationDetail(
                conv(5, "Friend catch-up", ConversationCategory.PERSONAL, "2 days ago", "10 min", "Caught up on the trip plans."),
                listOf("Caught up on the trip plans."), listOf("Trip plans"),
                emptyList(), emptyList(), emptyList(), null, true,
            ),
        )

        fun seedTranscripts(): Map<Long, List<TranscriptLine>> = mapOf(
            1L to listOf(
                TranscriptLine(0, "toh kal wale project ke liye hume presentation bana leni hai, aur client ko Monday tak bhejni hai"),
                TranscriptLine(12_000, "haan, client ne kaha tha ki Monday tak bhejni hai"),
                TranscriptLine(28_000, "budget bhi include karna hai, jo hume discuss kiya tha"),
                TranscriptLine(45_000, "team allocation bhi mention karna zaroori hai"),
                TranscriptLine(70_000, "aur haan, design team se bhi confirm kar lena"),
                TranscriptLine(92_000, "client ne specifically mobile app ka UI dekhne ko bola hai"),
                TranscriptLine(125_000, "theek hai, main aaj draft bana deta hoon"),
                TranscriptLine(134_000, "kal subah tak review kar lenge"),
            ),
            2L to listOf(
                TranscriptLine(0, "Mummy ne kaha weekend pe aana hai"),
                TranscriptLine(20_000, "main Friday ko tickets book kar dunga"),
            ),
        )

        fun seedFacts(): List<MemoryFactUiModel> = listOf(
            MemoryFactUiModel(1, "I work as an Android developer", MemoryKind.FACT, "Work • Saved from conversation", "Today"),
            MemoryFactUiModel(2, "I prefer Kotlin over Java", MemoryKind.PREFERENCE, "Preference • Saved from conversation", "2 days ago"),
            MemoryFactUiModel(3, "My mom lives in Delhi", MemoryKind.CONTACT, "Personal • Saved from call", "3 days ago"),
            MemoryFactUiModel(4, "I am building an AI app called Nishu", MemoryKind.FACT, "Project • Saved from conversation", "3 days ago"),
            MemoryFactUiModel(5, "I like chai and often drink it in the evening", MemoryKind.PREFERENCE, "Preference • Saved from conversation", "3 days ago"),
        )
    }
}
