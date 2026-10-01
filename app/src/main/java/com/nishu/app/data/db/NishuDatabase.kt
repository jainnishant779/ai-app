package com.nishu.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class, TranscriptSegmentEntity::class, TranscriptFts::class,
        SummaryEntity::class, TaskEntity::class, DecisionEntity::class, MemoryFactEntity::class,
        SpeakerNameEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class NishuDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun transcripts(): TranscriptDao
    abstract fun summaries(): SummaryDao
    abstract fun tasks(): TaskDao
    abstract fun decisions(): DecisionDao
    abstract fun memory(): MemoryDao
    abstract fun speakers(): SpeakerDao

    companion object {
        /** v2: user-editable speaker names. Existing recordings keep working; their segments simply have no speaker. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `speaker_name` (`conversationId` INTEGER NOT NULL, `label` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, PRIMARY KEY(`conversationId`, `label`), " +
                        "FOREIGN KEY(`conversationId`) REFERENCES `conversation`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }

        fun create(context: Context): NishuDatabase =
            Room.databaseBuilder(context.applicationContext, NishuDatabase::class.java, "nishu.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        fun inMemory(context: Context): NishuDatabase =
            Room.inMemoryDatabaseBuilder(context, NishuDatabase::class.java).allowMainThreadQueries().build()
    }
}
