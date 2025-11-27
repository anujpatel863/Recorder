package com.example.allrecorder.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.allrecorder.SettingsManager
import com.example.allrecorder.data.RecordingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class CleanupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CleanupWorkerEntryPoint {
        fun recordingsRepository(): RecordingsRepository
    }

    override suspend fun doWork(): Result {
        if (!SettingsManager.autoDeleteEnabled) {
            return Result.success()
        }

        return try {
            val appContext = applicationContext
            val entryPoint = EntryPointAccessors.fromApplication(
                appContext,
                CleanupWorkerEntryPoint::class.java
            )
            val repository = entryPoint.recordingsRepository()

            val days = SettingsManager.retentionDays
            val tag = SettingsManager.protectedTag

            Log.d("CleanupWorker", "Starting cleanup: keeping last $days days, protected tag: '$tag'")

            val deletedCount = repository.cleanUpOldRecordings(days, tag)

            Log.d("CleanupWorker", "Cleanup complete. Deleted $deletedCount files.")
            Result.success()
        } catch (e: Exception) {
            Log.e("CleanupWorker", "Error during cleanup", e)
            Result.retry()
        }
    }
}