package com.nishu.app.llm.llamacpp

import java.io.File
import java.security.MessageDigest

/**
 * Persists the KV state of the constant system prefix. The key covers everything that can change
 * the state's validity; a stale file is rebuilt, never loaded. Never ship these files in the APK.
 */
class PrefixCache(
    private val dir: File,
    private val modelSha256: String,
    private val llamaTag: String,
    private val nCtx: Int,
    private val prefixBytes: ByteArray,
) {
    val key: String = sha256("$modelSha256|$llamaTag|q8_0/q8_0|$nCtx|${sha256(prefixBytes)}".toByteArray())
    private val file = File(dir, "$key.state")

    fun tryLoad(handle: Long, tokens: IntArray): Boolean =
        file.exists() && LlamaBridge.loadState(handle, file.absolutePath, tokens)

    fun store(handle: Long, tokens: IntArray) {
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".state") && f != file }?.forEach { it.delete() }
        LlamaBridge.saveState(handle, file.absolutePath, tokens)
    }

    fun delete() {
        file.delete()
    }

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
