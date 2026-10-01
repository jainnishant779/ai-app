package com.nishu.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversation")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** ConversationCategory name. Set by the user only; defaults to OTHER. */
    val category: String = "OTHER",
    val createdAt: Long,
    val durationMs: Long = 0,
    val audioPath: String? = null,
    /** ConversationStatus name. */
    val status: String,
    /** ProcessingStage name while processing. */
    val stage: String? = null,
    val statusDetail: String? = null,
    val sttModelId: String? = null,
    val llmModelId: String? = null,
    val audioDeleted: Boolean = false,
)

@Entity(
    tableName = "transcript_segment",
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")],
)
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Reserved for diarization; always null in V0.1. */
    val speakerLabel: String? = null,
)

@Fts4(contentEntity = TranscriptSegmentEntity::class)
@Entity(tableName = "transcript_fts")
data class TranscriptFts(val text: String)

@Entity(
    tableName = "summary",
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
)
data class SummaryEntity(
    @PrimaryKey val conversationId: Long,
    /** Newline-separated bullets. The primary summary: bullets are what the model was trained on. */
    val bulletsText: String,
    val chunkCount: Int,
    val failedChunks: Int,
    val createdAt: Long,
    val llmModelId: String?,
)

@Entity(
    tableName = "task",
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val text: String,
    val owner: String? = null,
    /** Free text such as "Mon". Never a timestamp: a 0.6B model's date guess must not become a date. */
    val dueHint: String? = null,
    val done: Boolean = false,
    /** MODEL_JSON, MODEL_SALVAGED or HEURISTIC. */
    val extractionConfidence: String,
)

@Entity(
    tableName = "decision",
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")],
)
data class DecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val text: String,
    val extractionConfidence: String,
)

/** A user-chosen name for a diarized speaker ("S1" -> "Rahul"). Absent means the default "Speaker 1". */
@Entity(
    tableName = "speaker_name",
    primaryKeys = ["conversationId", "label"],
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
)
data class SpeakerNameEntity(
    val conversationId: Long,
    val label: String,
    val name: String,
)

@Entity(
    tableName = "memory_fact",
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["sourceConversationId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("sourceConversationId")],
)
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** MemoryKind name. */
    val kind: String,
    /** PINNED or MANUAL. */
    val origin: String,
    val sourceConversationId: Long? = null,
    val sourceStartMs: Long? = null,
    val createdAt: Long,
)
