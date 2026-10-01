package com.nishu.app.llm

import android.content.Context
import com.nishu.app.llm.llamacpp.PrefixCache
import java.io.File
import java.security.MessageDigest

/** Identifies the loaded GGUF. The hash is the model half of the prefix-cache key, so a swapped model rebuilds it. */
class ModelInfo(private val context: Context) {
    val file: File get() = File(context.filesDir, "models/llm/model.gguf")
    val exists: Boolean get() = file.exists()

    /** Hashing ~400 MB is slow, so the result is cached against path, length and mtime. */
    fun sha256(): String {
        val prefs = context.getSharedPreferences("model_info", Context.MODE_PRIVATE)
        val stamp = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        if (prefs.getString("stamp", null) == stamp) prefs.getString("sha", null)?.let { return it }
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        prefs.edit().putString("stamp", stamp).putString("sha", sha).apply()
        return sha
    }

    /** Short label for the UI: "Qwen3-0.6B (stock)" until a model with "nishu" in its hash registry arrives. */
    fun label(): String = "Qwen3-0.6B (stock)"

    fun prefixCache(nCtx: Int, llamaTag: String, systemPrompt: ByteArray) =
        PrefixCache(File(context.filesDir, "kvcache"), sha256(), llamaTag, nCtx, systemPrompt)
}

class ModelMissingException(path: String) : Exception("model not found: $path (run tools/push_models.ps1)")
