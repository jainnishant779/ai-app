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
                        val ids = buildList {
                            if (intent.hasExtra("id")) add(intent.getLongExtra("id", -1))
                            intent.getStringExtra("ids")?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.let(::addAll)
                        }.filter { it > 0 }
                        for (id in ids) {
                            Log.i("NishuDebug", "reprocess $id")
                            AppGraph.conversations.retryProcessing(id)
                        }
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

    /** STT on CPU versus NNAPI (the MediaTek APU) with the real model and your longest recording. */
    private suspend fun sttBench(): Result {
        val root = java.io.File(applicationContext.filesDir, "models/stt")
        val wav = java.io.File(applicationContext.filesDir, "recordings").listFiles { f -> f.extension == "wav" }
            .orEmpty().maxByOrNull { it.length() } ?: return Result.failure()
        val seconds = (wav.length() - 44) / 32000.0
        Log.i("NishuBench", "stt bench on ${wav.name} (${"%.1f".format(seconds)} s of audio)")
        EngineHolder.exclusively {
            val texts = HashMap<String, String>()
            val threadCounts = (inputData.getString("sttThreads") ?: "1,2,3,4").split(',').mapNotNull { it.trim().toIntOrNull() }
            for (n in threadCounts) {
                val stt = com.nishu.app.stt.SherpaOnnxStt(root, numThreads = n, provider = "cpu")
                val t0 = System.nanoTime()
                val out = StringBuilder()
                val result = runCatching { stt.transcribe(wav) { out.append(it.text).append(' ') } }
                val ms = (System.nanoTime() - t0) / 1_000_000
                Log.i(
                    "NishuBench",
                    "stt threads=$n: ${if (result.isSuccess) "$ms ms (${"%.2f".format(seconds * 1000 / ms)}x realtime)" else "FAILED ${result.exceptionOrNull()}"}",
                )
                if (result.isSuccess) texts["t$n"] = out.toString().trim()
            }
            Log.i("NishuBench", "stt text identical across thread counts: ${texts.values.distinct().size <= 1}")
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
        EngineHolder.exclusively {
            repeat(runs) { i ->
                LlamaCppEngine.load(info.file, prompt, nCtx = 1024, nThreads = threads, nThreadsBatch = batch, prefixCache = null).use { engine ->
                    val warm = engine.warmPrefix()
                    // A long answer, so decode speed is averaged over many tokens rather than a 12-token reply.
                    val r = engine.generate(
                        listOf(ChatMessage(Role.USER, "Explain step by step how a petrol car engine works, in detail.")),
                        SamplerProfile.Greedy, 96,
                    )
                    Log.i(
                        "NishuBench",
                        "run ${i + 1}/$runs: prefill ${warm.prefixTokens} tok in ${warm.millis} ms (${warm.prefixTokens * 1000 / warm.millis.coerceAtLeast(1)} tok/s) | " +
                            "decode ${"%.1f".format(r.decodeTokPerSec)} tok/s over ${r.tokens} tok | ttft ${r.ttftMs} ms",
                    )
                }
            }
        }
        Log.i("NishuBench", "done")
        return Result.success()
    }
}
