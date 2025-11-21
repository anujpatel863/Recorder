package com.example.allrecorder.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.allrecorder.models.ModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString("model_id") ?: return Result.failure()
        val modelSpec = try { ModelRegistry.getSpec(modelId) } catch (e: Exception) { return Result.failure() }

        val modelsDir = File(applicationContext.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val finalFile = File(modelsDir, modelSpec.fileName)
        val tempFile = File(modelsDir, "${modelSpec.fileName}.tmp")

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(modelSpec.url)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext Result.failure()

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
                        tempFile.delete() // Cleanup partial download
                        return@withContext Result.failure()
                    }
                    total += count
                    output.write(data, 0, count)
                    if (fileLength > 0) {
                        setProgress(workDataOf("progress" to ((total * 100) / fileLength).toInt()))
                    }
                }

                output.flush()
                output.close()
                input.close()

                // Atomic Swap
                if (finalFile.exists()) finalFile.delete()
                if (tempFile.renameTo(finalFile)) Result.success() else Result.failure()

            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                Result.retry()
            }
        }
    }
}