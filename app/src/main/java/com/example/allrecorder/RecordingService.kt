package com.example.allrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var currentFilePath: String? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var recordingDao: RecordingDao

    // Handler for reliable periodic tasks
    private lateinit var handler: Handler
    private var recordingStartTime: Long = 0L

    private val recordingRunnable = Runnable {
        // Stop the previous recording
        stopAndSaveRecording(recordingStartTime)

        // Start the new one and schedule the next
        startNewRecordingAndScheduleNext()
    }

    private val recordingDurationMillis = 30 * 1000L // 30 seconds for testing

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "RecordingServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "RecordingService"
    }

    override fun onCreate() {
        super.onCreate()
        recordingDao = AppDatabase.getDatabase(this).recordingDao()
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        Log.d(TAG, "Recording service started.")
        // Remove any existing callbacks to prevent duplicates if service is restarted
        handler.removeCallbacks(recordingRunnable)
        // Start the first recording immediately
        startNewRecordingAndScheduleNext()

        return START_STICKY
    }

    private fun startNewRecordingAndScheduleNext() {
        recordingStartTime = System.currentTimeMillis()
        startNewRecording()
        handler.postDelayed(recordingRunnable, recordingDurationMillis)
    }

    private fun startNewRecording() {
        currentFilePath = getOutputFilePath()
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(currentFilePath)
            try {
                prepare()
                start()
                Log.d(TAG, "Started recording to: $currentFilePath")
            } catch (e: IOException) {
                Log.e(TAG, "MediaRecorder prepare() failed", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "MediaRecorder start() failed", e)
            }
        }
    }

    private fun stopAndSaveRecording(startTime: Long) {
        // Check if recorder is even active
        if (mediaRecorder == null || startTime == 0L) return

        mediaRecorder?.apply {
            try {
                stop()
                release()
                Log.d(TAG, "Stopped and released MediaRecorder.")
            } catch (e: RuntimeException) {
                // This can happen if stop() is called after an error.
                Log.w(TAG, "RuntimeException on stopping MediaRecorder. Possibly already stopped.", e)
            }
        }
        mediaRecorder = null

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        val filePath = currentFilePath
        if (filePath != null) {
            val recording = Recording(
                filePath = filePath,
                startTime = startTime,
                duration = duration
            )
            // Use the service scope for this DB operation
            serviceScope.launch {
                recordingDao.insert(recording)
                Log.d(TAG, "Saved recording entry to database: $filePath")
            }
        }
        currentFilePath = null
    }

    private fun getOutputFilePath(): String {
        val outputDir = File(filesDir, "audio_chunks")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(outputDir, "AUDIO_$timeStamp.aac").absolutePath
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Recording Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Audio Recorder Active")
            .setContentText("Recording audio in the background.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your own icon
            .setContentIntent(pendingIntent)
            .build()
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Recording service destroyed.")
        handler.removeCallbacks(recordingRunnable) // Stop the loop
        stopAndSaveRecording(recordingStartTime) // Attempt to save the last chunk
        serviceJob.cancel() // Cancel all coroutines in this scope
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}

