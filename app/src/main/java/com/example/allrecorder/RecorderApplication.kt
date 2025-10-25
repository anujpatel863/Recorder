package com.example.allrecorder

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class RecorderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        setupRecurringWork()
    }

    private fun setupRecurringWork() {
        val constraints = Constraints.Builder()
            // Optional: Define constraints, e.g., run only when connected to a network
            // .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // FIX: Use 15L (Long) instead of 15 (Int)
        val repeatingRequest =
            PeriodicWorkRequestBuilder<ProcessingWorker>(15L, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            ProcessingWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // Keep the existing worker if it's already running
            repeatingRequest
        )
    }
}