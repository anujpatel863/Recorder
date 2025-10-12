package com.example.allrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service(), AudioManager.OnAudioFocusChangeListener {

    private var mediaRecorder: MediaRecorder? = null
    private var currentFilePath: String? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var recordingDao: RecordingDao

    private lateinit var handler: Handler
    private var recordingStartTime: Long = 0L
    private var sessionStartTime: Long = 0L

    // --- Audio Focus Members ---
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wasRecordingBeforeInterrupt = false

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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (requestAudioFocus()) {
            startForeground(NOTIFICATION_ID, createNotification())
            Log.d(TAG, "Recording service started.")
            _isRecording.value = true
            sessionStartTime = System.currentTimeMillis()
            _elapsedTime.value = 0L
            handler.post(timerRunnable)
            startNewRecording()
            handler.postDelayed(recordingRunnable, recordingDurationMillis)
        } else {
            // Could not get audio focus, so we can't record.
            stopSelf()
        }
        return START_STICKY
    }

    // --- Core Audio Focus Logic ---

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained.")
                if (wasRecordingBeforeInterrupt) {
                    // Resume recording
                    resumeRecording()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio focus lost permanently.")
                // Stop recording permanently
                stopRecordingPermanently()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus lost transiently.")
                // Pause recording
                pauseRecording()
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val result: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(this)
                .build()
            result = audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            result = audioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
    }

    // --- Recording State Management for Interruptions ---

    private fun pauseRecording() {
        if (!_isRecording.value) return
        wasRecordingBeforeInterrupt = true
        Log.d(TAG, "Pausing recording due to interruption.")
        handler.removeCallbacks(recordingRunnable)
        stopAndSaveRecording() // Save the current chunk before pausing
        _isRecording.value = false
    }

    private fun resumeRecording() {
        Log.d(TAG, "Resuming recording after interruption.")
        // Restart the recording cycle
        _isRecording.value = true
        startNewRecording()
        handler.postDelayed(recordingRunnable, recordingDurationMillis)
    }

    private fun stopRecordingPermanently() {
        if (_isRecording.value) {
            wasRecordingBeforeInterrupt = false
            _isRecording.value = false
        }
        // This will trigger onDestroy and full cleanup
        stopSelf()
    }


    // --- Original Recording Logic (mostly unchanged) ---

    private fun startNewRecording() {
        if (mediaRecorder != null) return
        recordingStartTime = System.currentTimeMillis()
        currentFilePath = getOutputFilePath()
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
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
                releaseRecorder()
            }
        }
    }

    private fun stopAndSaveRecording() {
        if (mediaRecorder == null) return
        val duration = System.currentTimeMillis() - recordingStartTime
        val filePath = currentFilePath
        releaseRecorder()
        if (filePath != null && duration > 500) {
            val recording = Recording(filePath = filePath, startTime = recordingStartTime, duration = duration)
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
        } catch (e: RuntimeException) {
            Log.w(TAG, "RuntimeException stopping MediaRecorder.", e)
            currentFilePath?.let { File(it).delete() }
        } finally {
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Recording service destroyed.")
        handler.removeCallbacks(recordingRunnable)
        handler.removeCallbacks(timerRunnable)
        stopAndSaveRecording()
        abandonAudioFocus() // Crucial: release focus
        serviceJob.cancel()
        _isRecording.value = false
        _elapsedTime.value = 0L
    }

    // --- Utility and Notification Code (unchanged) ---

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

    override fun onBind(intent: Intent?): IBinder? = null
}