package com.example.allrecorder.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.allrecorder.models.ModelRegistry
import com.example.allrecorder.models.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelBundleDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_BUNDLE_ID = "bundle_id"
        const val KEY_PROGRESS = "progress"
        private const val TAG = "ModelBundleDownloadWorker"
    }

    override suspend fun doWork(): Result {
        val bundleId = inputData.getString(KEY_BUNDLE_ID) ?: return Result.failure()
        val bundle = ModelRegistry.getBundle(bundleId) ?: return Result.failure()

        val modelsDir = File(applicationContext.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val totalFiles = bundle.modelIds.size
        if (totalFiles == 0) return Result.success()

        bundle.modelIds.forEachIndexed { index, modelId ->
            val modelSpec = try { ModelRegistry.getSpec(modelId) } catch (e: Exception) {
                Log.e(TAG, "Invalid modelId '$modelId' in bundle '$bundleId'", e)
                return Result.failure() // Fail the whole bundle if a spec is missing
            }

            val finalFile = File(modelsDir, modelSpec.fileName)
            if (finalFile.exists() && finalFile.length() > 1024) {
                 Log.i(TAG, "Model '${modelSpec.fileName}' already exists. Skipping.")
                // Update progress as if this file was just completed
                val bundleProgress = ((index + 1) * 100) / totalFiles
                setProgress(workDataOf(KEY_PROGRESS to bundleProgress))
                // Using `return@forEachIndexed` is like `continue` in a standard loop
                return@forEachIndexed
            }

            val result = downloadFile(modelSpec, index, totalFiles)
            if (result != Result.success()) {
                // If any file fails, the whole bundle download fails.
                // The downloadFile function should handle cleanup of its .tmp file.
                return result
            }
        }

        return Result.success()
    }

    private suspend fun downloadFile(modelSpec: ModelSpec, fileIndex: Int, totalFiles: Int): Result {
        val modelsDir = File(applicationContext.filesDir, "models")
        val finalFile = File(modelsDir, modelSpec.fileName)
        val tempFile = File(modelsDir, "${modelSpec.fileName}.tmp")

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting download for ${modelSpec.fileName} from ${modelSpec.url}")
                val url = URL(modelSpec.url)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "Server returned ${connection.responseCode} for ${modelSpec.url}")
                    return@withContext Result.failure()
                }

                val fileLength = connection.contentLength
                val input = connection.inputStream
                val output = FileOutputStream(tempFile)

                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int

                while (input.read(data).also { count = it } != -1) {
                    if (isStopped) {
                        output.close()
                        input.close()
                        tempFile.delete()
                        Log.i(TAG, "Download cancelled for ${modelSpec.fileName}")
                        return@withContext Result.failure()
                    }

                    total += count
                    output.write(data, 0, count)

                    // Calculate progress
                    if (fileLength > 0) {
                        val fileProgress = (total * 100 / fileLength).toInt()
                        // Calculate overall bundle progress
                        // Progress for previous files is fileIndex * 100.
                        // Progress for current file is fileProgress.
                        // Total possible points is totalFiles * 100.
                        val bundleProgress = ((fileIndex * 100) + fileProgress) / totalFiles
                        setProgress(workDataOf(KEY_PROGRESS to bundleProgress))
                    }
                }

                output.flush()
                output.close()
                input.close()

                if (fileLength > 0 && total != fileLength.toLong()) {
                    Log.e(TAG, "Integrity check failed for ${modelSpec.fileName}: Expected $fileLength, got $total")
                    tempFile.delete()
                    return@withContext Result.retry()
                }

                if (finalFile.exists()) finalFile.delete()
                if (tempFile.renameTo(finalFile)) {
                    Log.i(TAG, "Successfully downloaded ${modelSpec.fileName}")
                    // After finishing a file, update progress to reflect completion of this file
                    val bundleProgress = ((fileIndex + 1) * 100) / totalFiles
                    setProgress(workDataOf(KEY_PROGRESS to bundleProgress))
                    Result.success()
                } else {
                    Log.e(TAG, "Failed to rename temp file for ${modelSpec.fileName}")
                    if(tempFile.exists()) tempFile.delete()
                    Result.failure()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${modelSpec.fileName}", e)
                if (tempFile.exists()) tempFile.delete()
                Result.retry()
            }
        }
    }
}
