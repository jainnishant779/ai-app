package com.nishu.app.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nishu.app.AppGraph
import com.nishu.app.MainActivity
import com.nishu.app.domain.model.RecordingState
import com.nishu.app.domain.model.RecordingStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sqrt

/** Process-wide recording state shared between the service and the repository. */
object RecorderController {
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state
    @Volatile var finished: CompletableDeferred<Long>? = null

    internal fun set(s: RecordingState) { _state.value = s }
    internal fun update(f: (RecordingState) -> RecordingState) = _state.update(f)
}

class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getLongExtra(EXTRA_ID, -1), intent.getStringExtra(EXTRA_PATH).orEmpty())
            ACTION_PAUSE -> pause(true)
            ACTION_RESUME -> pause(false)
            ACTION_STOP -> stop()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(id: Long, path: String) {
        // startForeground must run before the first AudioRecord.read(), and within 5 s of startForegroundService().
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Recording…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (id < 0 || path.isEmpty() || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "cannot start: id=$id permission missing or bad args")
            fail()
            return
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nishu:recording").apply { acquire(6 * 60 * 60 * 1000L) }
        paused = false
        RecorderController.set(RecordingState(RecordingStatus.RECORDING, conversationId = id))
        job = scope.launch { record(id, File(path)) }
    }

    private suspend fun record(id: Long, file: File) {
        val minBuf = AudioRecord.getMinBufferSize(WavFile.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, WavFile.BYTES_PER_SECOND)
        val rec = createRecord(bufSize) ?: run { fail(); return }
        val agc = if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
            runCatching { android.media.audiofx.AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null
        val ns = if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
            runCatching { android.media.audiofx.NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null
        val writer = PcmWriter(file)
        val chunk = ByteArray(WavFile.BYTES_PER_SECOND / 10) // 100 ms
        var sinceFlush = 0
        try {
            rec.startRecording()
            while (job?.isActive == true) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                if (paused) continue // keep draining so resuming has no stale audio
                writer.write(chunk, n)
                sinceFlush += n
                if (sinceFlush >= WavFile.BYTES_PER_SECOND) { writer.flush(); sinceFlush = 0 }
                val level = level(chunk, n)
                RecorderController.update { s ->
                    s.copy(
                        elapsedMs = writer.bytesWritten * 1000 / WavFile.BYTES_PER_SECOND,
                        bytes = writer.bytesWritten,
                        levels = (s.levels + level).takeLast(48),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "recording failed", e)
        } finally {
            runCatching { agc?.release() }
            runCatching { ns?.release() }
            runCatching { rec.stop() }
            rec.release()
            writer.finish()
        }
    }

    private fun createRecord(bufSize: Int): AudioRecord? {
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val r = try {
                AudioRecord(source, WavFile.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            } catch (e: SecurityException) { return null }
            if (r.state == AudioRecord.STATE_INITIALIZED) return r
            r.release()
        }
        return null
    }

    private fun level(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        val count = n / 2
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
            i += 2
        }
        val rms = if (count > 0) sqrt(sum / count) else 0.0
        return (rms / 6000.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun pause(value: Boolean) {
        paused = value
        RecorderController.update { it.copy(status = if (value) RecordingStatus.PAUSED else RecordingStatus.RECORDING) }
    }

    private fun stop() {
        val running = job
        val id = RecorderController.state.value.conversationId
        scope.launch {
            job = null
            running?.cancelAndJoinQuietly()
            val file = id?.let { AppGraph.database.conversations().get(it)?.audioPath }?.let(::File)
            if (id != null && file != null) {
                AppGraph.database.conversations().setDuration(id, WavFile.durationMs(file))
                AppGraph.database.conversations().setStatus(id, "RECORDED", null)
                AppGraph.onRecorded(id)
            }
            RecorderController.set(RecordingState())
            RecorderController.finished?.complete(id ?: -1)
            release()
        }
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        runCatching { cancel(); join() }
    }

    private fun fail() {
        RecorderController.set(RecordingState())
        RecorderController.finished?.complete(-1)
        release()
    }

    private fun release() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Nishu")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "RecorderService"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "nishu.START"
        const val ACTION_PAUSE = "nishu.PAUSE"
        const val ACTION_RESUME = "nishu.RESUME"
        const val ACTION_STOP = "nishu.STOP"
        const val EXTRA_ID = "id"
        const val EXTRA_PATH = "path"

        fun send(context: Context, action: String, id: Long = -1, path: String = "") {
            val i = Intent(context, RecorderService::class.java).setAction(action).putExtra(EXTRA_ID, id).putExtra(EXTRA_PATH, path)
            if (action == ACTION_START) ContextCompat.startForegroundService(context, i) else context.startService(i)
        }
    }
}
