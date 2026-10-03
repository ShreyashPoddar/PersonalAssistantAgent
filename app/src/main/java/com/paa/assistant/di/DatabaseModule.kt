package com.paa.assistant.di

import android.content.Context
import com.paa.assistant.data.db.AppDatabase
import com.paa.assistant.data.db.FileIndexDao
import com.paa.assistant.data.db.MemoryDao
import com.paa.assistant.data.db.TaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI module providing Room Database and all DAO instances as singletons.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideTaskDao(db: AppDatabase): TaskDao = db.taskDao()

    @Provides
    @Singleton
    fun provideMemoryDao(db: AppDatabase): MemoryDao = db.memoryDao()

    @Provides
    @Singleton
    fun provideFileIndexDao(db: AppDatabase): FileIndexDao = db.fileIndexDao()

    @Provides
    @Singleton
    fun provideFeedbackDao(db: AppDatabase): com.paa.assistant.data.db.FeedbackDao = db.feedbackDao()

    @Provides
    @Singleton
    fun provideChatHistoryDao(db: AppDatabase): com.paa.assistant.data.db.ChatHistoryDao = db.chatHistoryDao()
}
