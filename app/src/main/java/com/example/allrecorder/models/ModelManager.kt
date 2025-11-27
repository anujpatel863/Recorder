package com.example.allrecorder.models

import android.content.Context
import androidx.work.*
import com.example.allrecorder.workers.ModelDownloadWorker
import java.io.File

class ModelManager(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    /**
     * Checks if a single model file exists and has content.
     */
    fun isModelReady(spec: ModelSpec): Boolean {
        val file = File(context.filesDir, "models/${spec.fileName}")
        return file.exists() && file.length() > 0
    }

    /**
     * //[FIX] This is the function that was missing.
     * It returns the absolute path to the model file so Mediapipe can load it.
     */
    fun getModelPath(spec: ModelSpec): String {
        return File(context.filesDir, "models/${spec.fileName}").absolutePath
    }

    /**
     * Checks if ALL models in a bundle are ready.
     */
    fun isBundleReady(bundle: ModelBundle): Boolean {
        return bundle.modelIds.all { id -> isModelReady(ModelRegistry.getSpec(id)) }
    }

    /**
     * Downloads all missing files for a bundle.
     */
    fun downloadBundle(bundle: ModelBundle) {
        bundle.modelIds.forEach { modelId ->
            val spec = ModelRegistry.getSpec(modelId)
            if (!isModelReady(spec)) {
                enqueueDownload(spec, bundle.id)
            }
        }
    }

    private fun enqueueDownload(spec: ModelSpec, bundleId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf("model_id" to spec.id))
            .setConstraints(constraints)
            .addTag(spec.id)      // Tag 1: The specific model
            .addTag(bundleId)     // Tag 2: The bundle (for UI tracking)
            .build()

        workManager.enqueueUniqueWork(
            "download_${spec.id}",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun deleteBundle(bundle: ModelBundle) {
        // 1. Cancel all workers for this bundle
        workManager.cancelAllWorkByTag(bundle.id)

        // 2. Delete actual files
        bundle.modelIds.forEach { id ->
            val spec = ModelRegistry.getSpec(id)
            val file = File(context.filesDir, "models/${spec.fileName}")
            if (file.exists()) file.delete()

            // Delete temp files too
            val temp = File(context.filesDir, "models/${spec.fileName}.tmp")
            if (temp.exists()) temp.delete()
        }
    }
}