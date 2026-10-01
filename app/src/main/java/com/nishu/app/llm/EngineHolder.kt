package com.nishu.app.llm

import android.content.Context
import com.nishu.app.BuildConfig
import com.nishu.app.llm.llamacpp.LlamaCppEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide lazy singleton. One engine at a time (the Mutex also serializes use), unloaded after 60 s idle so
 * the ~460 MB model is not resident while the app sits unused.
 */
object EngineHolder {
    private const val IDLE_MS = 60_000L
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: LLMEngine? = null
    private var idleJob: Job? = null
    private lateinit var context: Context

    fun init(appContext: Context) {
        context = appContext.applicationContext
    }

    val isLoaded: Boolean get() = engine != null

    suspend fun <T> withEngine(block: suspend (LLMEngine) -> T): T = mutex.withLock {
        idleJob?.cancel()
        try {
            val e = engine ?: load().also { engine = it }
            block(e)
        } finally {
            scheduleUnload()
        }
    }

    /** Cancels generation in flight without taking the lock. */
    fun cancelCurrent() {
        engine?.cancel()
    }

    suspend fun unload() = mutex.withLock {
        idleJob?.cancel()
        engine?.close()
        engine = null
    }

    private suspend fun load(): LLMEngine = withContext(Dispatchers.Default) {
        val info = ModelInfo(context)
        if (!info.exists) throw ModelMissingException(info.file.path)
        val prompt = context.assets.open("system_prompt.bin").use { it.readBytes() }
        val cache = info.prefixCache(nCtx = 1024, llamaTag = BuildConfig.LLAMA_TAG, systemPrompt = prompt)
        LlamaCppEngine.load(info.file, prompt, nCtx = 1024, nThreads = 4, prefixCache = cache).also { it.warmPrefix() }
    }

    private fun scheduleUnload() {
        idleJob = scope.launch {
            delay(IDLE_MS)
            mutex.withLock {
                engine?.close()
                engine = null
            }
        }
    }
}
