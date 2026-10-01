package com.nishu.app

import com.nishu.app.data.fake.FakeAudioPlayer
import com.nishu.app.domain.repo.AudioPlayer
import com.nishu.app.data.fake.FakeBenchmarkRepository
import com.nishu.app.data.fake.FakeConversationRepository
import com.nishu.app.data.fake.FakeMemoryRepository
import com.nishu.app.data.fake.FakeRecordingRepository
import com.nishu.app.data.fake.FakeSearchRepository
import com.nishu.app.data.fake.FakeSettingsRepository
import com.nishu.app.data.fake.FakeStore
import com.nishu.app.domain.repo.BenchmarkRepository
import com.nishu.app.domain.repo.ConversationRepository
import com.nishu.app.domain.repo.MemoryRepository
import com.nishu.app.domain.repo.RecordingRepository
import com.nishu.app.domain.repo.SearchRepository
import com.nishu.app.domain.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The only place that chooses fake or real implementations. Each backend milestone
 * swaps exactly one line here.
 */
object AppGraph {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = FakeStore(scope)

    val conversations: ConversationRepository = FakeConversationRepository(store)
    val recording: RecordingRepository = FakeRecordingRepository(store, scope)
    val memory: MemoryRepository = FakeMemoryRepository(store)
    val search: SearchRepository = FakeSearchRepository(store)
    val settings: SettingsRepository = FakeSettingsRepository(store)
    val benchmark: BenchmarkRepository = FakeBenchmarkRepository()

    fun newPlayer(conversationId: Long): AudioPlayer = FakeAudioPlayer()
}
