package com.example.allrecorder.models

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.*
import com.example.allrecorder.workers.ModelDownloadWorker
import java.io.File

class ModelManager(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    fun isModelReady(spec: ModelSpec): Boolean {
        val file = File(context.filesDir, "models/${spec.fileName}")
        // Add a minimum size check (e.g. > 1KB) to avoid empty placeholder files
        return file.exists() && file.length() > 1024
    }

    fun getModelPath(spec: ModelSpec): String {
        return File(context.filesDir, "models/${spec.fileName}").absolutePath
    }

    fun downloadModel(spec: ModelSpec): LiveData<WorkInfo?> {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf("model_id" to spec.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(spec.id) // Important for tracking progress
            .build()

        workManager.enqueueUniqueWork(
            "download_${spec.id}",
            ExistingWorkPolicy.KEEP,
            request
        )
        return workManager.getWorkInfoByIdLiveData(request.id)
    }

    // --- Bundle Operations ---

    fun isBundleReady(bundle: ModelBundle): Boolean {
        return bundle.modelIds.all { id -> isModelReady(ModelRegistry.getSpec(id)) }
    }

    fun downloadBundle(bundle: ModelBundle) {
        bundle.modelIds.forEach { id ->
            if (!isModelReady(ModelRegistry.getSpec(id))) {
                downloadModel(ModelRegistry.getSpec(id))
            }
        }
    }

    fun cancelBundleDownload(bundle: ModelBundle) {
        bundle.modelIds.forEach { id ->
            // This stops the worker and triggers the 'isStopped' check in doWork
            workManager.cancelUniqueWork("download_$id")

            // Also delete the file just in case the worker hadn't started writing yet
            val spec = ModelRegistry.getSpec(id)
            val file = File(context.filesDir, "models/${spec.fileName}")
            if (file.exists()) file.delete()
        }
    }

    fun deleteBundle(bundle: ModelBundle) {
        // Cancel any active downloads first
        cancelBundleDownload(bundle)
        // Delete files
        bundle.modelIds.forEach { id ->
            val spec = ModelRegistry.getSpec(id)
            val file = File(context.filesDir, "models/${spec.fileName}")
            if (file.exists()) file.delete()
        }
    }

    // Used by UI to observe progress
    fun getWorkInfosForBundle(bundle: ModelBundle): LiveData<List<WorkInfo>> {
        // We observe all workers tagged with the model IDs
        // Note: This is a simplification. Ideally, you'd combine LiveData.
        // For now, we usually only download one big file per bundle anyway.
        val firstId = bundle.modelIds.first()
        return workManager.getWorkInfosByTagLiveData(firstId)
    }
}