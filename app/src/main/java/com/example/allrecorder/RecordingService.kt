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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private lateinit var handler: Handler
    private var recordingStartTime: Long = 0L
    private var sessionStartTime: Long = 0L

    private val recordingRunnable = object : Runnable {
        override fun run() {
            stopAndSaveRecording()
            startNewRecording()
            handler.postDelayed(this, recordingDurationMillis)
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (_isRecording.value) {
                _elapsedTime.value = System.currentTimeMillis() - sessionStartTime
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val recordingDurationMillis = 30 * 1000L

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "RecordingServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "RecordingService"

        private val _isRecording = MutableStateFlow(false)
        val isRecording = _isRecording.asStateFlow()

        private val _elapsedTime = MutableStateFlow(0L)
        val elapsedTime = _elapsedTime.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        recordingDao = AppDatabase.getDatabase(this).recordingDao()
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d(TAG, "Recording service started.")

        _isRecording.value = true
        sessionStartTime = System.currentTimeMillis()
        _elapsedTime.value = 0L // Reset timer on start
        handler.post(timerRunnable)

        startNewRecording()
        handler.postDelayed(recordingRunnable, recordingDurationMillis)

        return START_STICKY
    }

    private fun startNewRecording() {
        recordingStartTime = System.currentTimeMillis()
        currentFilePath = getOutputFilePath()
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder?.apply {
            try {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentFilePath)
                prepare()
                start()
                Log.d(TAG, "Started recording to: $currentFilePath")
            } catch (e: Exception) {
                Log.e(TAG, "MediaRecorder setup failed. Cleaning up.", e)
                // Cleanup on failure
                releaseRecorder()
            }
        }
    }

    private fun stopAndSaveRecording() {
        if (mediaRecorder == null) return

        val endTime = System.currentTimeMillis()
        val duration = endTime - recordingStartTime
        val filePath = currentFilePath

        releaseRecorder()

        // Only save if the recording is a valid length
        if (filePath != null && duration > 500) { // e.g., longer than 0.5s
            val recording = Recording(
                filePath = filePath,
                startTime = recordingStartTime,
                duration = duration
            )
            serviceScope.launch {
                recordingDao.insert(recording)
                Log.d(TAG, "Saved recording chunk: $filePath")
            }
        }
        currentFilePath = null
    }

    private fun releaseRecorder() {
        if (mediaRecorder == null) return
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "RuntimeException on stopping MediaRecorder. File may be corrupted.", e)
            currentFilePath?.let { File(it).delete() } // Delete corrupted file
        } finally {
            mediaRecorder = null
        }
    }


    private fun getOutputFilePath(): String {
        val outputDir = File(filesDir, "audio_chunks")
        if (!outputDir.exists()) outputDir.mkdirs()
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(outputDir, "AUDIO_$timeStamp.aac").absolutePath
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Recording Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Recorder Active")
            .setContentText("Monitoring audio in the background.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Recording service destroyed.")
        handler.removeCallbacks(recordingRunnable)
        handler.removeCallbacks(timerRunnable)
        stopAndSaveRecording()
        serviceJob.cancel()
        _isRecording.value = false
        _elapsedTime.value = 0L
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

