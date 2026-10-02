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
import com.nishu.app.stt.SpeakerDiarizer
import com.nishu.app.stt.SpeakerTurns
import com.nishu.app.stt.SttModelMissing
import com.nishu.app.summarize.MapReduceSummarizer
import com.nishu.app.util.Trace
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
        db.conversations().setStatus(id, "TRANSCRIBING", ProcessingStage.TRANSCRIBING.name)
        Trace.begin(id, "pipeline: ${"%.1f".format(conv.audioPath?.let { File(it).length() / 32000.0 / 60 } ?: 0.0)} min of audio")
        // One heavy model at a time across all recordings: waits for any LLM work and unloads the LLM first.
        return EngineHolder.exclusively {
            Trace.log(id, "got exclusive access (LLM unloaded)")
            transcribe(id, conv.audioPath)
        }
    }

    private suspend fun transcribe(id: Long, audioPath: String?): Result {
        val db = AppGraph.database
        val audio = audioPath?.let(::File)
        if (audio == null || !audio.exists()) {
            db.conversations().setStatus(id, "FAILED", ProcessingStage.TRANSCRIBING.name, "Audio file is missing")
            return Result.failure()
        }
        val sttRoot = File(applicationContext.filesDir, "models/stt")
        val stt = SherpaOnnxStt(sttRoot)
        return try {
            // Who spoke when first; the diarizer is released before recognition starts, so the models are never resident together.
            Trace.log(id, "diarization start")
            val turns = runCatching { SpeakerDiarizer(sttRoot).diarize(audio) }
                .onFailure { Log.w(TAG, "speaker identification skipped", it) }
                .getOrDefault(emptyList())
            Log.i(TAG, "diarization: ${turns.map { it.speaker }.distinct().size} speaker(s), ${turns.size} turn(s)")
            Trace.log(id, "diarization done: ${turns.map { it.speaker }.distinct().size} speaker(s), ${turns.size} turn(s)")
            // Cleared only now, so the old transcript stays visible while speakers are identified; a re-run starts from scratch, so no stale summary or tasks outlive a new transcript.
            db.transcripts().clear(id)
            db.summaries().clear(id)
            db.tasks().clear(id)
            db.decisions().clear(id)
            Trace.log(id, "stt start (${stt.modelId})")
            var segCount = 0
            var lastProgressLog = 0f
            stt.transcribe(
                audio,
                onProgress = {
                    setProgressAsync(workDataOf("p" to it))
                    if (it - lastProgressLog >= 0.1f) { lastProgressLog = it; Trace.log(id, "stt ${(it * 100).toInt()}% (${segCount} segments)") }
                },
                speakerTurns = turns,
            ) { seg ->
                segCount++
                db.transcripts().insert(
                    TranscriptSegmentEntity(
                        conversationId = id, startMs = seg.startMs, endMs = seg.endMs, text = seg.text,
                        speakerLabel = SpeakerTurns.label(seg.speaker),
                    ),
                )
            }
            db.conversations().get(id)?.let { db.conversations().update(it.copy(sttModelId = stt.modelId)) }
            db.conversations().setStatus(id, "TRANSCRIBED", null)
            Trace.log(id, "stt done: $segCount segments")
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
            Trace.log(id, "summarize: waiting for the LLM")
            EngineHolder.withEngine { engine ->
                Trace.log(id, "summarize: LLM ready, prefix ${engine.systemPrefixTokens} tok, ctx ${engine.contextTokens}")
                MapReduceSummarizer(db, engine, grammar, modelId = "qwen3-0.6b-q4_k_m").run(id)
            }
            val done = db.conversations().get(id)
            Trace.log(id, "summarize done: status=${done?.status} detail=${done?.statusDetail}")
            Trace.end(id)
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
