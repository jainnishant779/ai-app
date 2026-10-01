package com.nishu.app

import android.content.Context
import com.nishu.app.audio.RealRecordingRepository
import com.nishu.app.audio.RecordingRecovery
import com.nishu.app.data.QuestionAnswerer
import com.nishu.app.data.RoomConversationRepository
import com.nishu.app.data.RoomMemoryRepository
import com.nishu.app.data.RoomSearchRepository
import com.nishu.app.data.db.NishuDatabase
import com.nishu.app.data.fake.FakeAudioPlayer
import com.nishu.app.data.fake.FakeBenchmarkRepository
import com.nishu.app.data.fake.FakeRecordingRepository
import com.nishu.app.data.fake.FakeSettingsRepository
import com.nishu.app.data.fake.FakeStore
import com.nishu.app.domain.repo.AudioPlayer
import com.nishu.app.domain.repo.BenchmarkRepository
import com.nishu.app.domain.repo.ConversationRepository
import com.nishu.app.domain.repo.MemoryRepository
import com.nishu.app.domain.repo.RecordingRepository
import com.nishu.app.domain.repo.SearchRepository
import com.nishu.app.domain.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The only place that chooses fake or real implementations. Each backend milestone swaps one line here.
 * Still fake: recording (M8), settings, benchmark, audio player (M12/M14).
 */
object AppGraph {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fakeStore by lazy { FakeStore(scope) }

    lateinit var database: NishuDatabase
        private set
    lateinit var appContext: Context
        private set

    lateinit var conversations: ConversationRepository
        private set
    lateinit var memory: MemoryRepository
        private set
    lateinit var search: SearchRepository
        private set

    lateinit var recording: RecordingRepository
        private set

    /** Called when a recording has been finalized. M11 replaces this with the processing pipeline. */
    var onRecorded: suspend (Long) -> Unit = {}
    val settings: SettingsRepository by lazy { FakeSettingsRepository(fakeStore) }
    val benchmark: BenchmarkRepository by lazy { FakeBenchmarkRepository() }

    fun newPlayer(conversationId: Long): AudioPlayer = FakeAudioPlayer()

    fun init(context: Context) {
        appContext = context.applicationContext
        database = NishuDatabase.create(appContext)
        conversations = RoomConversationRepository(database)
        memory = RoomMemoryRepository(database, QuestionAnswerer { _, _ -> flowOf("The on-device model is not connected yet.") })
        search = RoomSearchRepository(database)
        recording = RealRecordingRepository(appContext, database)
        scope.launch { RecordingRecovery.run(database) { onRecorded(it) } }
    }
}
