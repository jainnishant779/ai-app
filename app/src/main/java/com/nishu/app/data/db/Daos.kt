package com.nishu.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert suspend fun insert(c: ConversationEntity): Long
    @Update suspend fun update(c: ConversationEntity)

    @Query("SELECT * FROM conversation ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE id = :id")
    fun observe(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversation WHERE status = :status")
    suspend fun withStatus(status: String): List<ConversationEntity>

    @Query("UPDATE conversation SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE conversation SET category = :category WHERE id = :id")
    suspend fun setCategory(id: Long, category: String)

    @Query("UPDATE conversation SET status = :status, stage = :stage, statusDetail = :detail WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, stage: String?, detail: String? = null)

    @Query("UPDATE conversation SET audioDeleted = 1, audioPath = NULL WHERE id = :id")
    suspend fun markAudioDeleted(id: Long)

    @Query("UPDATE conversation SET durationMs = :durationMs WHERE id = :id")
    suspend fun setDuration(id: Long, durationMs: Long)

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM conversation")
    fun countAll(): Flow<Int>
}

@Dao
interface TranscriptDao {
    @Insert suspend fun insert(s: TranscriptSegmentEntity): Long

    @Query("DELETE FROM transcript_segment WHERE conversationId = :id")
    suspend fun clear(id: Long)

    @Query("SELECT * FROM transcript_segment WHERE conversationId = :id ORDER BY startMs")
    fun observe(id: Long): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segment WHERE conversationId = :id ORDER BY startMs")
    suspend fun get(id: Long): List<TranscriptSegmentEntity>

    @Query(
        "SELECT s.* FROM transcript_segment AS s JOIN transcript_fts AS f ON s.id = f.rowid " +
            "WHERE transcript_fts MATCH :match ORDER BY s.conversationId DESC, s.startMs LIMIT 80",
    )
    fun search(match: String): Flow<List<TranscriptSegmentEntity>>

    @Query(
        "SELECT s.* FROM transcript_segment AS s JOIN transcript_fts AS f ON s.id = f.rowid " +
            "WHERE transcript_fts MATCH :match ORDER BY s.conversationId DESC, s.startMs LIMIT :limit",
    )
    suspend fun searchOnce(match: String, limit: Int): List<TranscriptSegmentEntity>
}

@Dao
interface SummaryDao {
    @androidx.room.Upsert suspend fun upsert(s: SummaryEntity)

    @Query("SELECT * FROM summary WHERE conversationId = :id")
    fun observe(id: Long): Flow<SummaryEntity?>

    @Query("SELECT * FROM summary")
    fun observeAll(): Flow<List<SummaryEntity>>
}

@Dao
interface TaskDao {
    @Insert suspend fun insertAll(tasks: List<TaskEntity>)

    @Query("DELETE FROM task WHERE conversationId = :id")
    suspend fun clear(id: Long)

    @Query("SELECT * FROM task WHERE conversationId = :id ORDER BY id")
    fun observe(id: Long): Flow<List<TaskEntity>>

    @Query("SELECT COUNT(*) FROM task WHERE done = 0")
    fun countOpen(): Flow<Int>

    @Query("UPDATE task SET done = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)

    @androidx.room.RewriteQueriesToDropUnusedColumns
    @Query("SELECT t.*, c.title AS conversationTitle FROM task t JOIN conversation c ON c.id = t.conversationId WHERE t.text LIKE :like ESCAPE '\\' ORDER BY t.id DESC LIMIT 40")
    fun search(like: String): Flow<List<TaskWithTitle>>
}

data class TaskWithTitle(
    val id: Long,
    val conversationId: Long,
    val text: String,
    val conversationTitle: String,
)

@Dao
interface DecisionDao {
    @Insert suspend fun insertAll(items: List<DecisionEntity>)

    @Query("DELETE FROM decision WHERE conversationId = :id")
    suspend fun clear(id: Long)

    @Query("SELECT * FROM decision WHERE conversationId = :id ORDER BY id")
    fun observe(id: Long): Flow<List<DecisionEntity>>
}

@Dao
interface SpeakerDao {
    @androidx.room.Upsert suspend fun upsert(s: SpeakerNameEntity)

    @Query("DELETE FROM speaker_name WHERE conversationId = :id AND label = :label")
    suspend fun clear(id: Long, label: String)

    @Query("SELECT * FROM speaker_name WHERE conversationId = :id")
    fun observe(id: Long): Flow<List<SpeakerNameEntity>>
}

@Dao
interface MemoryDao {
    @Insert suspend fun insert(f: MemoryFactEntity): Long

    @Query("SELECT * FROM memory_fact ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_fact WHERE text LIKE :like ESCAPE '\\' ORDER BY createdAt DESC LIMIT 40")
    fun search(like: String): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_fact WHERE text LIKE :like ESCAPE '\\' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun searchOnce(like: String, limit: Int): List<MemoryFactEntity>

    @Query("UPDATE memory_fact SET kind = :kind WHERE id = :id")
    suspend fun setKind(id: Long, kind: String)

    @Query("DELETE FROM memory_fact WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM memory_fact")
    fun count(): Flow<Int>
}
