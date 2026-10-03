package com.paa.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.paa.assistant.data.models.FileIndexEntity
import com.paa.assistant.data.models.MemoryFactEntity
import com.paa.assistant.data.models.TaskEntity
import com.paa.assistant.data.models.TaskFeedbackEntity

/**
 * PAA local Room database.
 * Contains three tables:
 *  - tasks:        All user tasks, reminders and scheduled items
 *  - memory_facts: Long-term personal context injected into AI prompts
 *  - file_index:   SAF URI references for voice-accessible files
 */
@Database(
    entities = [TaskEntity::class, MemoryFactEntity::class, FileIndexEntity::class, TaskFeedbackEntity::class, com.paa.assistant.data.models.ChatLineEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun memoryDao(): MemoryDao
    abstract fun fileIndexDao(): FileIndexDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun chatHistoryDao(): ChatHistoryDao

    companion object {
        const val DATABASE_NAME = "paa_database"

        /** v3: source message + AI reason on tasks, and the feedback table. Keeps existing tasks. */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN sourceMessage TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN aiReason TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_feedback` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `message` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `isTask` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
            }
        }

        /** v4: saved chat history (the model reads conversations across restarts). */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`chatKey` TEXT NOT NULL, `line` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_history_chatKey` ON `chat_history` (`chatKey`)")
            }
        }

        /** v5: chat lines remember whether their burst was read (recovery after the app is killed). */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_history` ADD COLUMN `pending` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `chat_history` ADD COLUMN `isGroup` INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE?.let { return it }
                val appContext = context.applicationContext

                // Encrypted at rest with SQLCipher; the passphrase is protected by Android Keystore
                System.loadLibrary("sqlcipher")
                val passphrase = DatabaseEncryption.getPassphrase(appContext)
                DatabaseEncryption.migratePlaintextIfNeeded(appContext, DATABASE_NAME, passphrase)

                Room.databaseBuilder(
                    appContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray()))
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
