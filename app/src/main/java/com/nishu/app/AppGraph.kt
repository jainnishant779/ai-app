package com.nishu.app

import android.content.Context
import com.nishu.app.audio.RealAudioPlayer
import com.nishu.app.audio.RealRecordingRepository
import com.nishu.app.audio.RecordingRecovery
import com.nishu.app.audio.SilentPlayer
import com.nishu.app.data.RealSettingsRepository
import com.nishu.app.data.RoomConversationRepository
import com.nishu.app.data.RoomMemoryRepository
import com.nishu.app.data.RoomSearchRepository
import com.nishu.app.data.db.NishuDatabase
import com.nishu.app.data.fake.FakeBenchmarkRepository
import com.nishu.app.domain.repo.AudioPlayer
import com.nishu.app.domain.repo.BenchmarkRepository
import com.nishu.app.domain.repo.ConversationRepository
import com.nishu.app.domain.repo.MemoryRepository
import com.nishu.app.domain.repo.RecordingRepository
import com.nishu.app.domain.repo.SearchRepository
import com.nishu.app.domain.repo.SettingsRepository
import com.nishu.app.llm.EngineHolder
import com.nishu.app.llm.LlmQuestionAnswerer
import com.nishu.app.work.Pipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The only place that chooses implementations. Everything is real except the benchmark (M14).
 */
object AppGraph {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var appContext: Context
        private set
    lateinit var database: NishuDatabase
        private set
    lateinit var conversations: ConversationRepository
        private set
    lateinit var memory: MemoryRepository
        private set
    lateinit var search: SearchRepository
        private set
    lateinit var recording: RecordingRepository
        private set
    lateinit var settings: SettingsRepository
        private set

    val benchmark: BenchmarkRepository by lazy { FakeBenchmarkRepository() }

    /** Called when a recording has been finalized. */
    var onRecorded: suspend (Long) -> Unit = {}

    fun newPlayer(conversationId: Long): AudioPlayer {
        val path = runBlocking(Dispatchers.IO) { database.conversations().get(conversationId)?.audioPath }
        val file = path?.let(::File)?.takeIf { it.exists() }
        return if (file != null) RealAudioPlayer(file) else SilentPlayer()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        database = NishuDatabase.create(appContext)
        EngineHolder.init(appContext)
        onRecorded = { Pipeline.enqueue(appContext, it) }
        conversations = RoomConversationRepository(
            database,
            onCancel = { Pipeline.cancel(appContext, it) },
            onRetry = { Pipeline.enqueue(appContext, it, replace = true) },
        )
        memory = RoomMemoryRepository(database, LlmQuestionAnswerer())
        search = RoomSearchRepository(database)
        recording = RealRecordingRepository(appContext, database)
        settings = RealSettingsRepository(appContext)
        scope.launch { RecordingRecovery.run(database) { onRecorded(it) } }
    }
}
