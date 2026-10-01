package com.nishu.app.audio

import android.content.Context
import com.nishu.app.data.TimeLabels
import com.nishu.app.data.db.ConversationEntity
import com.nishu.app.data.db.NishuDatabase
import com.nishu.app.domain.model.RecordingState
import com.nishu.app.domain.repo.RecordingRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class RealRecordingRepository(
    private val context: Context,
    private val db: NishuDatabase,
) : RecordingRepository {
    override val state: StateFlow<RecordingState> = RecorderController.state

    override suspend fun start() {
        val now = System.currentTimeMillis()
        val id = db.conversations().insert(
            ConversationEntity(title = TimeLabels.defaultTitle(now), createdAt = now, status = "RECORDING"),
        )
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val path = File(dir, "$id.wav").absolutePath
        val row = db.conversations().get(id)!!
        db.conversations().update(row.copy(audioPath = path))
        RecorderService.send(context, RecorderService.ACTION_START, id, path)
    }

    override suspend fun pause() = RecorderService.send(context, RecorderService.ACTION_PAUSE)
    override suspend fun resume() = RecorderService.send(context, RecorderService.ACTION_RESUME)

    override suspend fun stopAndProcess(): Long {
        val done = CompletableDeferred<Long>()
        RecorderController.finished = done
        val id = RecorderController.state.value.conversationId ?: return -1
        RecorderService.send(context, RecorderService.ACTION_STOP)
        return withTimeoutOrNull(10_000) { done.await() } ?: id
    }

    override suspend fun discard() {
        val id = RecorderController.state.value.conversationId
        val done = CompletableDeferred<Long>()
        RecorderController.finished = done
        RecorderService.send(context, RecorderService.ACTION_STOP)
        withTimeoutOrNull(10_000) { done.await() }
        if (id != null) {
            db.conversations().get(id)?.audioPath?.let { File(it).delete() }
            db.conversations().delete(id)
        }
    }
}

/** Recordings left in RECORDING by a killed process: repair the WAV header and send them on to processing. */
object RecordingRecovery {
    suspend fun run(db: NishuDatabase, onRecorded: suspend (Long) -> Unit) {
        if (RecorderController.state.value.conversationId != null) return
        for (c in db.conversations().withStatus("RECORDING")) {
            val file = c.audioPath?.let(::File)
            if (file == null || !file.exists() || file.length() <= WavFile.HEADER_SIZE) {
                db.conversations().delete(c.id)
                continue
            }
            WavFile.patchHeader(file)
            db.conversations().setDuration(c.id, WavFile.durationMs(file))
            db.conversations().setStatus(c.id, "RECORDED", null)
            onRecorded(c.id)
        }
    }
}
