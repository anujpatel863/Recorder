package com.example.allrecorder

import android.app.Application
import androidx.work.*
import java.util.concurrent.TimeUnit

class RecorderApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleDiarizationWorker()
    }

    private fun scheduleDiarizationWorker() {
        // Define the constraints for the work
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)      // Must be charging
            .setRequiresDeviceIdle(true)    // Must be idle (screen off, unused for a while)
            .build()

        // Create a periodic request that runs roughly every 6 hours
        val periodicWorkRequest = PeriodicWorkRequestBuilder<DiarizationWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        // Enqueue the unique work. `KEEP` ensures that if a periodic work is already scheduled,
        // it is not replaced.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "AutomaticDiarization",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )
    }
}