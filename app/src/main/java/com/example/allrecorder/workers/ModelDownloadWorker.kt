package com.example.allrecorder.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.allrecorder.models.ModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import android.content.pm.ServiceInfo

class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString("model_id") ?: return@withContext Result.failure()
        val modelSpec = try { ModelRegistry.getSpec(modelId) } catch (e: Exception) { return@withContext Result.failure() }

        // Setup Foreground Service
        createNotificationChannel()
        setForeground(createForegroundInfo(modelSpec.description, 0, true))

        val modelsDir = File(applicationContext.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val finalFile = File(modelsDir, modelSpec.fileName)
        val tempFile = File(modelsDir, "${modelSpec.fileName}.tmp")

        try {
            val request = Request.Builder().url(modelSpec.url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("Network Error: ${response.code}")

            val body = response.body ?: throw IOException("Empty Body")
            val totalLength = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")

            body.byteStream().use { rawInput ->
                DigestInputStream(rawInput, digest).use { digestInput ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesCopied: Long = 0
                        var len: Int
                        var lastUpdate = 0L

                        while (digestInput.read(buffer).also { len = it } != -1) {
                            if (isStopped) {
                                output.close()
                                tempFile.delete()
                                return@withContext Result.failure()
                            }
                            output.write(buffer, 0, len)
                            bytesCopied += len

                            val now = System.currentTimeMillis()
                            if (totalLength > 0 && (now - lastUpdate > 500)) {
                                val progress = ((bytesCopied * 100) / totalLength).toInt()
                                setForeground(createForegroundInfo(modelSpec.description, progress, true))
                                setProgress(workDataOf("progress" to progress))
                                lastUpdate = now
                            }
                        }
                    }
                }
            }

            // Verification
            if (modelSpec.sha256 != null) {
                val calculatedHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!calculatedHash.equals(modelSpec.sha256, ignoreCase = true)) {
                    tempFile.delete()
                    return@withContext Result.failure(workDataOf("error" to "Checksum mismatch"))
                }
            }

            if (finalFile.exists()) finalFile.delete()
            if (tempFile.renameTo(finalFile)) {
                return@withContext Result.success()
            } else {
                return@withContext Result.failure()
            }

        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            return@withContext Result.retry()
        }
    }

    private fun createForegroundInfo(title: String, progress: Int, indeterminate: Boolean): ForegroundInfo {
        val id = "model_download_channel"
        val notificationId = title.hashCode()

        val cancelIntent = androidx.work.WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(getId())

        val notification = NotificationCompat.Builder(applicationContext, id)
            .setContentTitle("Downloading $title")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelIntent)
            .setProgress(100, progress, progress == 0 && indeterminate)
            .build()

        // FIX: Explicitly provide the foreground service type for Android 14+
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "model_download_channel",
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }
}