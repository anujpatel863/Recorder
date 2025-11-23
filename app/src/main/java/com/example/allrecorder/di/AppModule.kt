package com.example.allrecorder.di

import android.app.Application
import android.content.Context
import com.example.allrecorder.AppDatabase
import com.example.allrecorder.EmbeddingManager
import com.example.allrecorder.RecordingDao
import com.example.allrecorder.TranscriptionOrchestrator
import com.example.allrecorder.models.ModelManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideRecordingDao(database: AppDatabase): RecordingDao {
        return database.recordingDao()
    }

    @Provides
    @Singleton
    fun provideModelManager(@ApplicationContext context: Context): ModelManager {
        return ModelManager(context)
    }

    @Provides
    @Singleton
    fun provideEmbeddingManager(
        @ApplicationContext context: Context,
        modelManager: ModelManager // [FIX] Injected
    ): EmbeddingManager {
        return EmbeddingManager(context, modelManager)
    }

    @Provides
    @Singleton
    fun provideTranscriptionOrchestrator(
        @ApplicationContext context: Context,
        modelManager: ModelManager // [FIX] Injected
    ): TranscriptionOrchestrator {
        // TranscriptionOrchestrator expects Application, so we cast context
        return TranscriptionOrchestrator(context as Application, modelManager)
    }
}