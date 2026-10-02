package com.nishu.app.util

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Timing and memory trace of one recording's trip through the pipeline, written to logcat under `NishuTrace` and appended to
 * [file]: logcat's ring buffer is flushed within minutes by chatty tags, the file keeps the whole run.
 * Cheap enough to leave on: one line per stage.
 */
object Trace {
    private const val TAG = "NishuTrace"
    private const val MAX_BYTES = 256 * 1024L
    /** Set once at startup; the file is capped so it cannot grow without bound. */
    @Volatile var file: File? = null
    private val starts = ConcurrentHashMap<Long, Long>()

    fun begin(conversationId: Long, what: String) {
        starts.putIfAbsent(conversationId, SystemClock.elapsedRealtime())
        log(conversationId, "BEGIN $what")
    }

    fun log(conversationId: Long, message: String) {
        val t0 = starts[conversationId] ?: SystemClock.elapsedRealtime().also { starts[conversationId] = it }
        val sec = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val line = "#$conversationId +${"%.1f".format(sec)}s [${memory()}] $message"
        Log.i(TAG, line)
        append(line)
    }

    fun end(conversationId: Long) {
        log(conversationId, "END")
        starts.remove(conversationId)
    }

    @Synchronized
    private fun append(line: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.writeText("") // old runs are not worth keeping
            f.appendText(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + line + "\n")
        }
    }

    /** "pss 612MB" for the whole process: what the memory gate is measured against. */
    private fun memory(): String {
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return "pss ${info.totalPss / 1000}MB"
    }
}
