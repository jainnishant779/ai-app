package com.nishu.app.debug

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nishu.app.AppGraph
import com.nishu.app.work.Pipeline
import com.nishu.app.llm.ChatMessage
import com.nishu.app.llm.DeviceProfile
import com.nishu.app.llm.EngineHolder
import com.nishu.app.llm.ModelInfo
import com.nishu.app.llm.Role
import com.nishu.app.llm.SamplerProfile
import com.nishu.app.llm.llamacpp.LlamaBridge
import com.nishu.app.llm.llamacpp.LlamaCppEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug builds only. Drive the app from adb without touching the screen or killing the process:
 *
 *   reprocess:  am broadcast -a com.nishu.app.DEBUG_REPROCESS -n com.nishu.app/.debug.DebugReceiver --es ids 12,15
 *   benchmark:  am broadcast -a com.nishu.app.DEBUG_BENCH     -n com.nishu.app/.debug.DebugReceiver [--ei threads 4 --ei batch 4 --ei runs 3]
 *               results are logged under the tag NishuBench.
 *               --es configs "repack:512,norepack:512,repack:128,norepack:128" sweeps weight repacking and micro-batch size,
 *               logging memory after each load (the default is one run with the app's own settings).
 *   stt bench:  --es mode stt [--es model qwen3|swift] [--es wav imports/x.wav] [--es sttThreads 4]
 *               times one model on one file (default: the longest recording) and writes its text to files/bench_<model>.txt.
 *
 * The benchmark runs as a foreground worker: a broadcast receiver that takes longer than a few seconds is killed as an ANR.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.nishu.app.DEBUG_REPROCESS" -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val lang = intent.getStringExtra("lang")
                        if (lang != null) {
                            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("audio_language", lang).apply()
                        }
                        val ids = buildList {
                            if (intent.hasExtra("id")) add(intent.getLongExtra("id", -1))
                            intent.getStringExtra("ids")?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.let(::addAll)
                        }.filter { it > 0 }
                        for (id in ids) {
                            Log.i("NishuDebug", "reprocess $id (lang=${lang ?: "default"})")
                            Pipeline.enqueue(context, id, replace = true, language = lang)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            "com.nishu.app.DEBUG_IMPORT" -> {
                // am broadcast -a com.nishu.app.DEBUG_IMPORT -n com.nishu.app/.debug.DebugReceiver --es file imports/meeting.wav --es title "Weekly meeting" [--es lang english]
                // The WAV (16 kHz mono PCM16) must already be inside the app's files dir (see tools/import_audio.ps1).
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val src = java.io.File(context.filesDir, intent.getStringExtra("file") ?: return@launch)
                        val title = intent.getStringExtra("title") ?: src.nameWithoutExtension
                        val lang = intent.getStringExtra("lang")
                        val now = System.currentTimeMillis()
                        val id = AppGraph.database.conversations().insert(
                            com.nishu.app.data.db.ConversationEntity(title = title, createdAt = now, status = "RECORDED"),
                        )
                        val dest = java.io.File(java.io.File(context.filesDir, "recordings").apply { mkdirs() }, "$id.wav")
                        // Anything other than our own WAV goes through the same decoder as the Import button.
                        if (src.extension.equals("wav", true)) src.copyTo(dest, overwrite = true)
                        else com.nishu.app.audio.AudioImporter.import(context, android.net.Uri.fromFile(src), dest)
                        val row = AppGraph.database.conversations().get(id)!!
                        AppGraph.database.conversations().update(
                            row.copy(audioPath = dest.absolutePath, durationMs = com.nishu.app.audio.WavFile.durationMs(dest)),
                        )
                        Log.i("NishuDebug", "imported '$title' as conversation $id (${dest.length() / 1_000_000} MB, lang=${lang ?: "default"})")
                        Pipeline.enqueue(context, id, replace = true, language = lang)
                    } finally {
                        pending.finish()
                    }
                }
            }
            "com.nishu.app.DEBUG_BENCH" -> {
                val data = workDataOf(
                    "threads" to intent.getIntExtra("threads", -1),
                    "batch" to intent.getIntExtra("batch", -1),
                    "runs" to intent.getIntExtra("runs", 3),
                    "mode" to (intent.getStringExtra("mode") ?: "llm"),
                    "sttThreads" to (intent.getStringExtra("sttThreads") ?: "1,2,3,4"),
                    "configs" to (intent.getStringExtra("configs") ?: ""),
                    "model" to (intent.getStringExtra("model") ?: ""),
                    "wav" to (intent.getStringExtra("wav") ?: ""),
                )
                WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<BenchWorker>().setInputData(data).build())
            }
        }
    }
}

/** Cold prefill and greedy decode speed with the real model, plus which CPU instruction set ggml chose. */
class BenchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bench", "Benchmark", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, "bench").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Nishu benchmark").setOngoing(true).build()
        return ForegroundInfo(2003, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** One STT model on one file: speed, peak memory and the text, so models can be compared on the same audio. */
    private suspend fun sttBench(): Result {
        val root = java.io.File(applicationContext.filesDir, "models/stt")
        val named = inputData.getString("wav")?.takeIf { it.isNotBlank() }
        val wav = named?.let { java.io.File(applicationContext.filesDir, it) }
            ?: java.io.File(applicationContext.filesDir, "recordings").listFiles { f -> f.extension == "wav" }
                .orEmpty().maxByOrNull { it.length() } ?: return Result.failure()
        val modelName = inputData.getString("model")?.takeIf { it.isNotBlank() } ?: "default"
        val spec = when (modelName) {
            "qwen3" -> com.nishu.app.stt.SttModelSpec.QWEN3_HINGLISH
            "swift" -> com.nishu.app.stt.SttModelSpec.HINGLISH_SWIFT
            "base", "en", "english" -> com.nishu.app.stt.SttModelSpec.WHISPER_BASE_EN
            "tiny" -> com.nishu.app.stt.SttModelSpec.TINY_EN
            else -> com.nishu.app.stt.SttModelSpec.select(root)
        }
        val seconds = (wav.length() - 44) / 32000.0
        Log.i("NishuBench", "stt bench ${spec.id} on ${wav.name} (${"%.1f".format(seconds)} s of audio)")
        EngineHolder.exclusively {
            val threadCounts = (inputData.getString("sttThreads") ?: "4").split(',').mapNotNull { it.trim().toIntOrNull() }
            for (n in threadCounts) {
                var peak = 0
                val out = StringBuilder()
                val stt = com.nishu.app.stt.SherpaOnnxStt(root, spec = spec, numThreads = n, provider = "cpu")
                val t0 = System.nanoTime()
                val result = runCatching {
                    stt.transcribe(wav) {
                        out.append(it.text).append(' ')
                        val mem = android.os.Debug.MemoryInfo().also { m -> android.os.Debug.getMemoryInfo(m) }
                        peak = maxOf(peak, mem.totalPss / 1000)
                    }
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                val words = out.trim().split(Regex("\\s+")).size
                Log.i(
                    "NishuBench",
                    "stt ${spec.id} threads=$n: " +
                        (if (result.isSuccess) "$ms ms (RTF ${"%.2f".format(ms / 1000.0 / seconds)}), peak PSS $peak MB, $words words"
                        else "FAILED ${result.exceptionOrNull()}"),
                )
                java.io.File(applicationContext.filesDir, "bench_$modelName.txt").writeText(out.toString().trim())
            }
        }
        Log.i("NishuBench", "done")
        return Result.success()
    }

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        if (inputData.getString("mode") == "stt") return sttBench()
        val profile = DeviceProfile.read()
        val threads = inputData.getInt("threads", -1).takeIf { it > 0 } ?: profile.decodeThreads
        val batch = inputData.getInt("batch", -1).takeIf { it > 0 } ?: profile.batchThreads
        val runs = inputData.getInt("runs", 3)
        Log.i("NishuBench", "device=${profile.soc} cores=${profile.totalCores} big=${profile.bigCores} threads=$threads batch=$batch")
        Log.i("NishuBench", "cpu features: " + LlamaBridge.systemInfo().lineSequence().joinToString(" ").take(300))
        val info = ModelInfo(applicationContext)
        val prompt = applicationContext.assets.open("system_prompt.bin").use { it.readBytes() }
        // Exclusive: no pipeline may be running a speech model or the LLM while we measure.
        val configs = (inputData.getString("configs") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { it.split(':').let { p -> (p[0] != "norepack") to (p.getOrNull(1)?.toIntOrNull() ?: 512) } }
            .ifEmpty { listOf(false to 128) } // the app's own defaults
        EngineHolder.exclusively {
            for ((repack, ubatch) in configs) {
                LlamaBridge.setOptions(repack, ubatch)
                val label = "${if (repack) "repack" else "norepack"} ubatch=$ubatch"
                repeat(runs) { i ->
                    LlamaCppEngine.load(info.file, prompt, nCtx = 1024, nThreads = threads, nThreadsBatch = batch, prefixCache = null).use { engine ->
                        val warm = engine.warmPrefix()
                        // A long answer, so decode speed is averaged over many tokens rather than a 12-token reply.
                        val r = engine.generate(
                            listOf(ChatMessage(Role.USER, "Explain step by step how a petrol car engine works, in detail.")),
                            SamplerProfile.Greedy, 96,
                        )
                        val mem = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
                        Log.i(
                            "NishuBench",
                            "[$label] run ${i + 1}/$runs: prefill ${warm.prefixTokens} tok in ${warm.millis} ms (${warm.prefixTokens * 1000 / warm.millis.coerceAtLeast(1)} tok/s) | " +
                                "decode ${"%.1f".format(r.decodeTokPerSec)} tok/s over ${r.tokens} tok | ttft ${r.ttftMs} ms | " +
                                "pss ${mem.totalPss / 1000} MB, native heap ${mem.getMemoryStat("summary.native-heap").toInt() / 1000} MB, " +
                                "mapped ${mem.getMemoryStat("summary.private-other").toInt() / 1000} MB",
                        )
                    }
                }
            }
        }
        Log.i("NishuBench", "done")
        return Result.success()
    }
}
