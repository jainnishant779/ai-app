package com.nishu.app.data

import com.nishu.app.data.db.ConversationEntity
import com.nishu.app.data.db.DecisionEntity
import com.nishu.app.data.db.MemoryFactEntity
import com.nishu.app.data.db.NishuDatabase
import com.nishu.app.data.db.SpeakerNameEntity
import com.nishu.app.data.db.SummaryEntity
import com.nishu.app.data.db.TaskEntity
import com.nishu.app.domain.model.*
import com.nishu.app.domain.repo.ConversationRepository
import com.nishu.app.domain.repo.MemoryRepository
import com.nishu.app.domain.repo.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.File

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

fun defaultSpeakerName(label: String) = "Speaker ${label.drop(1)}"

private fun confidence(raw: String) = if (raw == "MODEL_JSON") Confidence.HIGH else Confidence.LOW

fun ConversationEntity.toUi(summary: SummaryEntity?, nowMs: Long = System.currentTimeMillis()) = ConversationUiModel(
    id = id,
    title = title,
    category = enumOr(category, ConversationCategory.OTHER),
    timestampLabel = TimeLabels.timestamp(createdAt, nowMs),
    durationLabel = TimeLabels.duration(durationMs),
    preview = summary?.bulletsText?.lineSequence()?.firstOrNull { it.isNotBlank() }.orEmpty(),
    status = enumOr(status, ConversationStatus.FAILED),
)

class RoomConversationRepository(
    private val db: NishuDatabase,
    private val onCancel: suspend (Long) -> Unit = {},
    private val onRetry: suspend (Long) -> Unit = {},
) : ConversationRepository {
    private val uiModels: Flow<List<ConversationUiModel>> =
        combine(db.conversations().observeAll(), db.summaries().observeAll()) { convs, sums ->
            val byId = sums.associateBy { it.conversationId }
            convs.map { it.toUi(byId[it.id]) }
        }

    override fun counts(): Flow<HomeCounts> =
        combine(db.conversations().countAll(), db.tasks().countOpen(), db.memory().count()) { c, t, f -> HomeCounts(c, t, f) }

    override fun recent(limit: Int) = uiModels.map { it.take(limit) }

    override fun all(category: ConversationCategory?) =
        uiModels.map { list -> list.filter { category == null || it.category == category } }

    /** Transcript lines with speakers resolved to their display names. */
    private fun lines(id: Long): Flow<List<TranscriptLine>> =
        combine(db.transcripts().observe(id), db.speakers().observe(id)) { segments, names ->
            val custom = names.associate { it.label to it.name }
            segments.map { s ->
                val ref = s.speakerLabel?.let { SpeakerRef(it, custom[it]?.takeIf { n -> n.isNotBlank() } ?: defaultSpeakerName(it)) }
                TranscriptLine(s.startMs, s.text, ref)
            }
        }

    override fun detail(id: Long): Flow<ConversationDetail?> = combine(
        db.conversations().observe(id), db.summaries().observe(id), db.tasks().observe(id),
        db.decisions().observe(id), lines(id),
    ) { conv, summary, tasks, decisions, lines ->
        conv?.let {
            val bullets = summary?.bulletsText?.lines()?.map { l -> l.trim().trimStart('-', '*', '•').trim() }
                ?.filter { l -> l.isNotEmpty() }.orEmpty()
            ConversationDetail(
                conversation = it.toUi(summary),
                summaryBullets = bullets,
                keyPoints = emptyList(),
                tasks = tasks.map { t -> TaskUiModel(t.id, t.text, t.dueHint, t.done, confidence(t.extractionConfidence)) },
                decisions = decisions.map { d -> DecisionUiModel(d.id, d.text, confidence(d.extractionConfidence)) },
                transcriptPreview = lines.take(3),
                statusDetail = it.statusDetail,
                hasAudio = !it.audioDeleted && it.audioPath != null,
            )
        }
    }

    override fun transcript(id: Long) = lines(id)

    override suspend fun renameSpeaker(id: Long, label: String, name: String) {
        if (name.isBlank()) db.speakers().clear(id, label) else db.speakers().upsert(SpeakerNameEntity(id, label, name.trim()))
    }

    override fun processing(id: Long): Flow<Map<ProcessingStage, StepState>> = db.conversations().observe(id).map { c ->
        val stages = ProcessingStage.entries
        when {
            c == null -> stages.associateWith { StepState.PENDING }
            c.status == "DONE" -> stages.associateWith { StepState.COMPLETED }
            c.status == "FAILED" -> {
                val at = stages.indexOfFirst { it.name == c.stage }.let { if (it < 0) 0 else it }
                stages.mapIndexed { i, s -> s to if (i < at) StepState.COMPLETED else if (i == at) StepState.FAILED else StepState.PENDING }.toMap()
            }
            c.status == "RECORDING" || c.status == "RECORDED" -> stages.associateWith { StepState.PENDING }
            else -> {
                val at = stages.indexOfFirst { it.name == c.stage }.let { if (it < 0) 0 else it }
                stages.mapIndexed { i, s -> s to if (i < at) StepState.COMPLETED else if (i == at) StepState.RUNNING else StepState.PENDING }.toMap()
            }
        }
    }

    override suspend fun setTaskDone(taskId: Long, done: Boolean) = db.tasks().setDone(taskId, done)
    override suspend fun setCategory(id: Long, category: ConversationCategory) = db.conversations().setCategory(id, category.name)
    override suspend fun rename(id: Long, title: String) = db.conversations().rename(id, title)

    override suspend fun deleteAudio(id: Long) {
        db.conversations().get(id)?.audioPath?.let { File(it).delete() }
        db.conversations().markAudioDeleted(id)
    }

    override suspend fun delete(id: Long) {
        db.conversations().get(id)?.audioPath?.let { File(it).delete() }
        db.conversations().delete(id)
    }

    override suspend fun cancelProcessing(id: Long) {
        onCancel(id)
        db.conversations().setStatus(id, "FAILED", db.conversations().get(id)?.stage, "Cancelled")
    }

    override suspend fun retryProcessing(id: Long) {
        db.conversations().setStatus(id, "RECORDED", null, null)
        onRetry(id)
    }

    override suspend fun audioPath(id: Long): String? = db.conversations().get(id)?.audioPath
}

fun interface QuestionAnswerer {
    /** Streams the growing answer text. */
    fun answer(question: String, context: List<String>): Flow<String>
}

class RoomMemoryRepository(
    private val db: NishuDatabase,
    private val answerer: QuestionAnswerer,
) : MemoryRepository {
    override fun facts(kind: MemoryKind?) = db.memory().observeAll().map { list ->
        list.filter { kind == null || it.kind == kind.name }.map { it.toUi() }
    }

    override suspend fun add(text: String, kind: MemoryKind, sourceConversationId: Long?, sourceStartMs: Long?) {
        db.memory().insert(
            MemoryFactEntity(
                text = text.trim(), kind = kind.name,
                origin = if (sourceConversationId != null) "PINNED" else "MANUAL",
                sourceConversationId = sourceConversationId, sourceStartMs = sourceStartMs,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun updateKind(id: Long, kind: MemoryKind) = db.memory().setKind(id, kind.name)
    override suspend fun delete(id: Long) = db.memory().delete(id)

    override fun ask(question: String): Flow<String> = kotlinx.coroutines.flow.flow {
        val match = ftsMatch(question)
        val segments = if (match.isEmpty()) emptyList() else db.transcripts().searchOnce(match, 3).map { it.text }
        val facts = db.memory().searchOnce(likePattern(question.split(' ').maxByOrNull { it.length } ?: question), 3).map { it.text }
        emitAll(answerer.answer(question, facts + segments))
    }
}

private fun MemoryFactEntity.toUi(): MemoryFactUiModel {
    val kindLabel = when (kind) { "PREFERENCE" -> "Preference"; "CONTACT" -> "Contact"; "OTHER" -> "Memory"; else -> "Fact" }
    val origin = if (sourceConversationId != null) "Saved from conversation" else "Added manually"
    return MemoryFactUiModel(id, text, enumOr(kind, MemoryKind.FACT), "$kindLabel • $origin", TimeLabels.relativeDay(createdAt))
}

/** Prefix-matching FTS query: each word becomes `word*`; punctuation is dropped so user input cannot break the syntax. */
fun ftsMatch(query: String): String =
    query.trim().split(Regex("\\s+")).map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        .joinToString(" ") { "$it*" }

fun likePattern(query: String): String =
    "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

class RoomSearchRepository(private val db: NishuDatabase) : SearchRepository {
    override fun search(query: String): Flow<SearchResults> {
        val q = query.trim()
        val match = ftsMatch(q)
        if (q.isEmpty() || match.isEmpty()) return flowOf(SearchResults())
        val like = likePattern(q)
        return combine(
            db.transcripts().search(match), db.conversations().observeAll(), db.memory().search(like), db.tasks().search(like),
        ) { segments, convs, facts, tasks ->
            val titles = convs.associate { it.id to it.title }
            SearchResults(
                conversations = segments.groupBy { it.conversationId }.map { (cid, segs) ->
                    SearchHit(cid, cid, titles[cid] ?: "Conversation", segs.first().text)
                },
                memory = facts.map { SearchHit(it.id, null, it.text, it.toUi().sourceLabel) },
                tasks = tasks.map { SearchHit(it.id, it.conversationId, it.text, it.conversationTitle) },
            )
        }
    }
}
