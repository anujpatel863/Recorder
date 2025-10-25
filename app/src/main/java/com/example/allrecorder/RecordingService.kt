package com.example.allrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecordingInternal = false
    private lateinit var recordingDao: RecordingDao
    private var recordingStartTime: Long = 0
    private lateinit var filePath: String

    // Handler for timed chunking
    private val chunkHandler = Handler(Looper.getMainLooper())
    private var chunkRunnable: Runnable? = null

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIFICATION_CHANNEL_ID = "RecordingChannel"
        private const val NOTIFICATION_ID = 12345
        var isRecording = false
    }

    override fun onCreate() {
        super.onCreate()
        recordingDao = AppDatabase.getDatabase(this).recordingDao()
        SettingsManager.init(this) // Initialize settings
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startRecording()
        return START_STICKY
    }

    private fun startRecording() {
        if (isRecordingInternal) {
            Log.w(TAG, "Recording is already in progress.")
            return
        }

        val fileName = "Rec_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp3"
        filePath = File(filesDir, fileName).absolutePath

        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder()
        }).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(filePath)
            try {
                prepare()
                start()
                isRecordingInternal = true
                isRecording = true
                recordingStartTime = System.currentTimeMillis()
                startForeground(NOTIFICATION_ID, createNotification())
                Log.i(TAG, "Recording started: $filePath")
                scheduleNextChunk() // Schedule the auto-restart if enabled
            } catch (e: IOException) {
                Log.e(TAG, "MediaRecorder prepare() failed", e)
                releaseRecorder() // Clean up on failure
                stopSelf()
            }
        }
    }

    private fun stopRecording() {
        if (!isRecordingInternal) {
            return
        }

        cancelChunking() // Always cancel any pending chunk task

        val recordingDuration = System.currentTimeMillis() - recordingStartTime
        saveRecordingToDatabase(filePath, recordingStartTime, recordingDuration)

        releaseRecorder()
        stopForeground(true)
        stopSelf() // Stop the service if not chunking
    }

    private fun releaseRecorder() {
        mediaRecorder?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping or releasing MediaRecorder", e)
            }
        }
        mediaRecorder = null
        isRecordingInternal = false
        isRecording = false
    }

    private fun scheduleNextChunk() {
        // Read the duration in milliseconds
        val chunkDurationMillis = SettingsManager.chunkDurationMillis

        // Check if a valid duration (greater than 0) is set
        if (chunkDurationMillis > 0) {
            Log.i(TAG, "Scheduling next recording chunk in ${chunkDurationMillis / 1000} seconds.")
            chunkRunnable = Runnable {
                Log.i(TAG, "Chunk time reached. Restarting recording.")
                val recordingDuration = System.currentTimeMillis() - recordingStartTime
                saveRecordingToDatabase(filePath, recordingStartTime, recordingDuration)
                releaseRecorder()
                startRecording()
            }
            // Use the millisecond value directly
            chunkHandler.postDelayed(chunkRunnable!!, chunkDurationMillis)
        }
    }

    private fun cancelChunking() {
        chunkRunnable?.let {
            chunkHandler.removeCallbacks(it)
            Log.d(TAG, "Cancelled pending recording chunk.")
        }
        chunkRunnable = null
    }





    private fun saveRecordingToDatabase(path: String, startTime: Long, duration: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val newRecording = Recording(
                filePath = path,
                startTime = startTime,
                duration = duration,
                isProcessed = false
            )
            recordingDao.insert(newRecording)
            Log.i(TAG, "Recording saved to database: $path")
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Recording Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Recording Audio")
            .setContentText("Your device is currently recording.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your app's icon
            .build()
    }

    override fun onDestroy() {
        if (isRecordingInternal) {
            stopRecording() // Ensure recording is stopped and saved if service is destroyed
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}