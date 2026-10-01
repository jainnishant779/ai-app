package com.nishu.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    "com.nishu.app.DEBUG_REPROCESS" -> reprocess(intent)
                    "com.nishu.app.DEBUG_BENCH" -> bench(context, intent)
                }
            } catch (e: Throwable) {
                Log.e("NishuBench", "debug command failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun reprocess(intent: Intent) {
        val ids = buildList {
            if (intent.hasExtra("id")) add(intent.getLongExtra("id", -1))
            intent.getStringExtra("ids")?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.let(::addAll)
        }.filter { it > 0 }
        for (id in ids) {
            Log.i("NishuDebug", "reprocess $id")
            AppGraph.conversations.retryProcessing(id)
        }
    }

    /** Cold prefill and greedy decode speed with the real model, plus which CPU instruction set ggml chose. */
    private suspend fun bench(context: Context, intent: Intent) {
        val profile = DeviceProfile.read()
        val threads = intent.getIntExtra("threads", profile.decodeThreads)
        val batch = intent.getIntExtra("batch", profile.batchThreads)
        val runs = intent.getIntExtra("runs", 3)
        Log.i("NishuBench", "device=${profile.soc} cores=${profile.totalCores} big=${profile.bigCores} threads=$threads batch=$batch")
        Log.i("NishuBench", "cpu=" + LlamaBridge.systemInfo().lineSequence().firstOrNull { "CPU" in it || "NEON" in it }?.trim())
        val info = ModelInfo(context)
        val prompt = context.assets.open("system_prompt.bin").use { it.readBytes() }
        // Exclusive: no pipeline may be running a speech model or the LLM while we measure.
        EngineHolder.exclusively {
            repeat(runs) { i ->
                LlamaCppEngine.load(info.file, prompt, nCtx = 1024, nThreads = threads, nThreadsBatch = batch, prefixCache = null).use { engine ->
                    val warm = engine.warmPrefix()
                    val r = engine.generate(listOf(ChatMessage(Role.USER, "Namaste, aap kaun ho aur kya kar sakte ho?")), SamplerProfile.Greedy, 64)
                    Log.i(
                        "NishuBench",
                        "run ${i + 1}/$runs: prefill ${warm.prefixTokens} tok in ${warm.millis} ms (${warm.prefixTokens * 1000 / warm.millis.coerceAtLeast(1)} tok/s) | " +
                            "decode ${"%.1f".format(r.decodeTokPerSec)} tok/s over ${r.tokens} tok | ttft ${r.ttftMs} ms",
                    )
                }
            }
        }
        Log.i("NishuBench", "done")
    }
}
