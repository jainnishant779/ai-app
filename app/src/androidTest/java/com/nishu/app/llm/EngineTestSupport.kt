package com.nishu.app.llm

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.nishu.app.llm.llamacpp.LlamaCppEngine
import com.nishu.app.llm.llamacpp.PrefixCache
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import java.io.File

const val TAG = "NishuTest"

object EngineTestSupport {
    val appContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val testContext: Context get() = InstrumentationRegistry.getInstrumentation().context

    val modelFile: File get() = File(appContext.filesDir, "models/llm/model.gguf")

    val isEmulator: Boolean
        get() = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")

    fun systemPrompt(): ByteArray = appContext.assets.open("system_prompt.bin").use { it.readBytes() }

    fun requireModel() {
        assumeTrue("push the model first: tools/push_models.ps1 (missing ${modelFile.path})", modelFile.exists())
    }

    /** Shared across tests so only the very first warmPrefix pays the (slow on emulators) cold prefill. */
    fun sharedCache(nCtx: Int = 1024) =
        PrefixCache(File(appContext.filesDir, "kvcache"), "test-model-sha", "b11312", nCtx, systemPrompt())

    fun loadEngine(nCtx: Int = 1024, cache: PrefixCache? = sharedCache(nCtx)): LlamaCppEngine {
        requireModel()
        return LlamaCppEngine.load(modelFile, systemPrompt(), nCtx = nCtx, prefixCache = cache)
    }

    fun fixtureNames(): List<String> =
        testContext.assets.list("")!!.filter { it.endsWith(".messages.json") }.sorted()

    fun fixtureMessages(name: String): List<ChatMessage> {
        val arr = JSONArray(testContext.assets.open(name).use { String(it.readBytes(), Charsets.UTF_8) })
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val tc = o.optJSONArray("tool_calls")?.getJSONObject(0)?.getJSONObject("function")?.let {
                ToolCall(it.getString("name"), it.get("arguments").toString())
            }
            ChatMessage(Role.valueOf(o.getString("role").uppercase()), o.optString("content", ""), tc)
        }
    }

    fun fixtureBytes(name: String): ByteArray = testContext.assets.open(name).use { it.readBytes() }

    fun fixtureTokens(name: String): IntArray {
        val ids = org.json.JSONObject(String(fixtureBytes(name), Charsets.UTF_8)).getJSONArray("ids")
        return IntArray(ids.length()) { ids.getInt(it) }
    }

    fun grammar(name: String): String = appContext.assets.open("grammars/$name").use { String(it.readBytes(), Charsets.UTF_8) }

    fun log(msg: String) = Log.i(TAG, msg)
}
