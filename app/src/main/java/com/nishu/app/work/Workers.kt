package com.nishu.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nishu.app.AppGraph
import com.nishu.app.data.db.TranscriptSegmentEntity
import com.nishu.app.domain.model.ProcessingStage
import com.nishu.app.llm.EngineHolder
import com.nishu.app.llm.ModelMissingException
import com.nishu.app.stt.SherpaOnnxStt
import com.nishu.app.stt.SttModelMissing
import com.nishu.app.summarize.MapReduceSummarizer
import kotlinx.coroutines.CancellationException
import java.io.File

private const val TAG = "NishuWork"
private const val CHANNEL = "processing"
private const val KEY_ID = "id"

private fun foregroundInfo(context: Context, text: String): ForegroundInfo {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Processing", NotificationManager.IMPORTANCE_LOW))
    val n = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("Nishu")
        .setContentText(text)
        .setOngoing(true)
        .build()
    return ForegroundInfo(2002, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}

/** Foreground promotion can be refused when started from the background; the work still runs without it. */
private suspend fun CoroutineWorker.promote(text: String) {
    runCatching { setForeground(foregroundInfo(applicationContext, text)) }
}

/**
 * Transcribes on-device. The STT model is released before this worker returns, so the LLM
 * (loaded by the next worker) is never resident together with onnxruntime.
 */
class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo() = foregroundInfo(applicationContext, "Transcribing…")

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        val db = AppGraph.database
        val conv = db.conversations().get(id) ?: return Result.failure()
        promote("Transcribing…")
        EngineHolder.unload() // free the LLM (if Ask loaded it) before onnxruntime comes in
        db.conversations().setStatus(id, "TRANSCRIBING", ProcessingStage.TRANSCRIBING.name)
        val audio = conv.audioPath?.let(::File)
        if (audio == null || !audio.exists()) {
            db.conversations().setStatus(id, "FAILED", ProcessingStage.TRANSCRIBING.name, "Audio file is missing")
            return Result.failure()
        }
        val stt = SherpaOnnxStt(File(applicationContext.filesDir, "models/stt"))
        return try {
            db.transcripts().clear(id)
            stt.transcribe(audio, onProgress = { setProgressAsync(workDataOf("p" to it)) }) { seg ->
                db.transcripts().insert(TranscriptSegmentEntity(conversationId = id, startMs = seg.startMs, endMs = seg.endMs, text = seg.text))
            }
            db.conversations().get(id)?.let { db.conversations().update(it.copy(sttModelId = stt.modelId)) }
            db.conversations().setStatus(id, "TRANSCRIBED", null)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SttModelMissing) {
            Log.e(TAG, "stt model missing", e)
            db.conversations().setStatus(id, "FAILED", ProcessingStage.TRANSCRIBING.name, e.message)
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "transcribe failed for conversation $id", e)
            db.conversations().setStatus(id, "FAILED", ProcessingStage.TRANSCRIBING.name, "Transcription failed: ${e.message}")
            Result.failure()
        }
    }
}

class SummarizeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo() = foregroundInfo(applicationContext, "Summarizing…")

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        val db = AppGraph.database
        db.conversations().get(id) ?: return Result.failure()
        promote("Summarizing…")
        val grammar = applicationContext.assets.open("grammars/extract.gbnf").use { String(it.readBytes(), Charsets.UTF_8) }
        return try {
            EngineHolder.withEngine { engine ->
                MapReduceSummarizer(db, engine, grammar, modelId = "qwen3-0.6b-q4_k_m").run(id)
            }
            val done = db.conversations().get(id)
            if (done != null) com.nishu.app.CompletionNotifier.notifyDone(applicationContext, id, done.title, done.status == "DONE")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelMissingException) {
            // The transcript is still a useful product on its own.
            Log.e(TAG, "model missing", e)
            db.conversations().setStatus(id, "FAILED", ProcessingStage.SUMMARIZING.name, "Summary unavailable: model not installed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "summarize failed for conversation $id", e)
            db.conversations().setStatus(id, "FAILED", ProcessingStage.SUMMARIZING.name, "Summary unavailable: ${e.javaClass.simpleName}: ${e.message}")
            Result.success()
        }
    }
}

object Pipeline {
    private fun name(id: Long) = "process-$id"

    /** Transcribe, then summarize. Two chained workers so the STT and LLM models are never loaded together. */
    fun enqueue(context: Context, id: Long, replace: Boolean = false) {
        val data = workDataOf(KEY_ID to id)
        val transcribe = OneTimeWorkRequestBuilder<TranscribeWorker>().setInputData(data).build()
        val summarize = OneTimeWorkRequestBuilder<SummarizeWorker>().setInputData(data).build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).beginUniqueWork(name(id), policy, transcribe).then(summarize).enqueue()
    }

    fun cancel(context: Context, id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(name(id))
        EngineHolder.cancelCurrent()
    }
}
