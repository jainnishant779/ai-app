package com.nishu.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class, TranscriptSegmentEntity::class, TranscriptFts::class,
        SummaryEntity::class, TaskEntity::class, DecisionEntity::class, MemoryFactEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NishuDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun transcripts(): TranscriptDao
    abstract fun summaries(): SummaryDao
    abstract fun tasks(): TaskDao
    abstract fun decisions(): DecisionDao
    abstract fun memory(): MemoryDao

    companion object {
        fun create(context: Context): NishuDatabase =
            Room.databaseBuilder(context.applicationContext, NishuDatabase::class.java, "nishu.db").build()

        fun inMemory(context: Context): NishuDatabase =
            Room.inMemoryDatabaseBuilder(context, NishuDatabase::class.java).allowMainThreadQueries().build()
    }
}
