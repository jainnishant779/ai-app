package com.nishu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.nishu.app.data.db.ConversationEntity
import com.nishu.app.data.db.DecisionEntity
import com.nishu.app.data.db.MemoryFactEntity
import com.nishu.app.data.db.NishuDatabase
import com.nishu.app.data.db.SummaryEntity
import com.nishu.app.data.db.TaskEntity
import com.nishu.app.data.db.TranscriptSegmentEntity
import com.nishu.app.domain.model.Confidence
import com.nishu.app.domain.model.ProcessingStage
import com.nishu.app.domain.model.StepState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DatabaseTest {
    private lateinit var db: NishuDatabase

    @Before fun setUp() {
        db = NishuDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After fun tearDown() = db.close()

    private suspend fun conversation(status: String = "DONE", stage: String? = null) =
        db.conversations().insert(ConversationEntity(title = "Team meeting", createdAt = System.currentTimeMillis(), status = status, stage = stage))

    @Test
    fun cascadeDeleteRemovesChildrenButKeepsMemoryFacts() = runBlocking {
        val id = conversation()
        db.transcripts().insert(TranscriptSegmentEntity(conversationId = id, startMs = 0, endMs = 1000, text = "hello world"))
        db.tasks().insertAll(listOf(TaskEntity(conversationId = id, text = "do it", extractionConfidence = "HEURISTIC")))
        db.decisions().insertAll(listOf(DecisionEntity(conversationId = id, text = "go", extractionConfidence = "MODEL_JSON")))
        db.summaries().upsert(SummaryEntity(id, "- a\n- b", 1, 0, 0, null))
        val factId = db.memory().insert(MemoryFactEntity(text = "fact", kind = "FACT", origin = "PINNED", sourceConversationId = id, createdAt = 0))

        db.conversations().delete(id)

        assertTrue(db.transcripts().get(id).isEmpty())
        assertTrue(db.tasks().observe(id).first().isEmpty())
        assertTrue(db.decisions().observe(id).first().isEmpty())
        assertNull(db.summaries().observe(id).first())
        val fact = db.memory().observeAll().first().single { it.id == factId }
        assertNull("fact must survive its conversation", fact.sourceConversationId)
    }

    @Test
    fun fullTextSearchFindsPrefixesAndFollowsEdits() = runBlocking {
        val id = conversation()
        db.transcripts().insert(TranscriptSegmentEntity(conversationId = id, startMs = 0, endMs = 1, text = "client wants the presentation by Monday"))
        db.transcripts().insert(TranscriptSegmentEntity(conversationId = id, startMs = 5, endMs = 6, text = "budget breakdown too"))
        assertEquals(1, db.transcripts().searchOnce(ftsMatch("pres"), 10).size)
        assertEquals(1, db.transcripts().searchOnce(ftsMatch("client monday"), 10).size)
        assertEquals(0, db.transcripts().searchOnce(ftsMatch("nonexistent"), 10).size)
        db.transcripts().clear(id)
        assertEquals("FTS must follow deletes", 0, db.transcripts().searchOnce(ftsMatch("budget"), 10).size)
    }

    @Test
    fun repositoryMapsDetailAndProcessingStages() = runBlocking {
        val repo = RoomConversationRepository(db)
        val id = conversation(status = "SUMMARIZING", stage = "EXTRACTING_TASKS")
        db.tasks().insertAll(
            listOf(
                TaskEntity(conversationId = id, text = "a", extractionConfidence = "MODEL_JSON"),
                TaskEntity(conversationId = id, text = "b", extractionConfidence = "HEURISTIC"),
            ),
        )
        val steps = repo.processing(id).first()
        assertEquals(StepState.COMPLETED, steps[ProcessingStage.TRANSCRIBING])
        assertEquals(StepState.COMPLETED, steps[ProcessingStage.SUMMARIZING])
        assertEquals(StepState.RUNNING, steps[ProcessingStage.EXTRACTING_TASKS])
        assertEquals(StepState.PENDING, steps[ProcessingStage.SAVING])

        val detail = repo.detail(id).first()!!
        assertEquals(listOf(Confidence.HIGH, Confidence.LOW), detail.tasks.map { it.confidence })
        assertTrue(detail.hasLowConfidence)
        assertEquals(1, repo.counts().first().recordings)
        assertEquals(2, repo.counts().first().openTasks)
    }

    @Test
    fun searchSpansTranscriptMemoryAndTasks() = runBlocking {
        val id = conversation()
        db.transcripts().insert(TranscriptSegmentEntity(conversationId = id, startMs = 0, endMs = 1, text = "new project timeline"))
        db.tasks().insertAll(listOf(TaskEntity(conversationId = id, text = "Create project presentation", extractionConfidence = "MODEL_JSON")))
        db.memory().insert(MemoryFactEntity(text = "I am building a project called Nishu", kind = "FACT", origin = "MANUAL", createdAt = 0))
        val r = RoomSearchRepository(db).search("project").first()
        assertEquals(1, r.conversations.size)
        assertEquals(1, r.tasks.size)
        assertEquals(1, r.memory.size)
        assertTrue(RoomSearchRepository(db).search("   ").first().isEmpty)
    }
}
