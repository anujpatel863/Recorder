package com.example.allrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class RecordingService : Service() {

    @Inject
    lateinit var recordingDao: RecordingDao

    // --- Audio Engines ---
    private var audioRecord: AudioRecord? = null
    private var mediaRecorder: MediaRecorder? = null

    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO) // Service Scope

    // --- WAV Configuration ---
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // --- State ---
    private var isRecordingInternal = false
    private var recordingStartTime: Long = 0
    private lateinit var filePath: String
    private var currentFormat: SettingsManager.RecordingFormat = SettingsManager.RecordingFormat.M4A

    private val chunkHandler = Handler(Looper.getMainLooper())
    private var chunkRunnable: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val binder = LocalBinder()
    private val _audioDataFlow = MutableStateFlow(ByteArray(0))
    val audioDataFlow: StateFlow<ByteArray> = _audioDataFlow.asStateFlow()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIFICATION_CHANNEL_ID = "RecordingChannel"
        private const val NOTIFICATION_ID = 12345
        var isRecording = false
        const val ACTION_STOP = "com.example.allrecorder.ACTION_STOP"
        const val ACTION_START = "com.example.allrecorder.ACTION_START"
    }

    override fun onCreate() {
        super.onCreate()
        SettingsManager.init(this)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AllRecorder::RecordingWakeLock").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // [FIX] Launch coroutine to handle suspend function
                scope.launch { stopRecording(stopService = true) }
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startRecording()
                return START_STICKY
            }
            else -> {
                startRecording()
                return START_STICKY
            }
        }
    }

    private fun startRecording() {
        if (isRecordingInternal) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        try { wakeLock?.acquire(10*60*1000L) } catch (e: Exception) {}

        currentFormat = SettingsManager.recordingFormat
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Rec_$timeStamp${currentFormat.extension}"
        filePath = File(filesDir, fileName).absolutePath

        try {
            if (currentFormat == SettingsManager.RecordingFormat.WAV) {
                startWavRecording(File(filePath))
            } else {
                startM4aRecording(filePath)
            }

            isRecordingInternal = true
            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            startForeground(NOTIFICATION_ID, createNotification(isRecording = true))
            scheduleNextChunk()

        } catch (e: Exception) {
            Log.e(TAG, "Start recording failed", e)
            // [FIX] Launch stop if start fails
            scope.launch { stopRecording(stopService = true) }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startWavRecording(file: File) {
        writePlaceholderWavHeader(file)
        val bufferSizeInBytes = bufferSize * 2
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, channelConfig, audioFormat, bufferSizeInBytes).apply {
            if (state != AudioRecord.STATE_INITIALIZED) throw IOException("AudioRecord init failed")
            startRecording()
        }
        recordingJob = scope.launch { writeAudioDataToFile(file.absolutePath) }
    }

    private fun startM4aRecording(path: String) {
        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64000)
            setAudioSamplingRate(16000)
            setOutputFile(path)
            prepare()
            start()
        }
    }

    private suspend fun writeAudioDataToFile(path: String) {
        val data = ByteArray(bufferSize)
        val fileOutputStream = FileOutputStream(path, true)
        var totalBytesWritten = 0L
        try {
            while (isRecordingInternal) {
                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    _audioDataFlow.value = data.clone()
                    fileOutputStream.write(data, 0, read)
                    totalBytesWritten += read
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error writing WAV", e)
        } finally {
            try {
                fileOutputStream.close()
                updateWavHeader(File(path), totalBytesWritten.toInt())
            } catch (e: Exception) {}
        }
    }

    // [FIX] Made this function SUSPEND so we can wait for jobs and DB
    private suspend fun stopRecording(stopService: Boolean = true) {
        if (!isRecordingInternal) return

        isRecordingInternal = false
        isRecording = false
        cancelChunking()

        val duration = System.currentTimeMillis() - recordingStartTime
        val savedPath = filePath // Copy for safe keeping

        try {
            if (currentFormat == SettingsManager.RecordingFormat.WAV) {
                stopWavEngine() // This is suspend, now valid
            } else {
                stopM4aEngine()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
        }

        // [FIX] Now calling DB insert inside suspend function is valid
        saveRecordingToDatabase(savedPath, recordingStartTime, duration)

        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}

        if (stopService) {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        } else {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(isRecording = false))
        }
    }

    // [FIX] Marked as suspend because it calls join()
    private suspend fun stopWavEngine() {
        audioRecord?.apply {
            try { stop() } catch (e: Exception) {}
            release()
        }
        audioRecord = null
        recordingJob?.join() // Wait for file writer to finish
        recordingJob = null
    }

    private fun stopM4aEngine() {
        mediaRecorder?.apply {
            try { stop() } catch (e: RuntimeException) {}
            release()
        }
        mediaRecorder = null
    }

    // [FIX] Made suspend to support DAO call
    private suspend fun saveRecordingToDatabase(path: String, startTime: Long, duration: Long) {
        val newRecording = Recording(filePath = path, startTime = startTime, duration = duration, processingStatus = 0)
        recordingDao.insert(newRecording) // Suspend call OK here
    }

    private fun scheduleNextChunk() {
        val chunkDurationMillis = SettingsManager.chunkDurationMillis
        if (chunkDurationMillis > 0) {
            chunkRunnable = Runnable {
                // [FIX] Launch coroutine to ensure sequential execution: Stop -> Start
                scope.launch {
                    stopRecording(stopService = false)
                    startRecording()
                }
            }
            chunkHandler.postDelayed(chunkRunnable!!, chunkDurationMillis)
        }
    }

    private fun cancelChunking() {
        chunkRunnable?.let { chunkHandler.removeCallbacks(it) }
        chunkRunnable = null
    }

    override fun onDestroy() {
        if (isRecordingInternal) {
            scope.launch { stopRecording(stopService = true) }
        }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch(e:Exception){}
        super.onDestroy()
    }

    // ... (Keep createNotification, binder, writePlaceholderWavHeader, updateWavHeader as they were) ...
    // NOTE: Be sure to keep the Helper methods for WAV Header!
    // Just omitting here for brevity, but they must be in the file.

    // --- Copied helpers for completeness if you need them ---
    @Throws(IOException::class)
    private fun writePlaceholderWavHeader(file: File) {
        FileOutputStream(file).use { out -> out.write(ByteArray(44)) }
    }

    @Throws(IOException::class)
    private fun updateWavHeader(file: File, dataSize: Int) {
        val channels = 1; val bitDepth = 16; val byteRate = sampleRate * channels * bitDepth / 8; val totalDataLen = dataSize + 36
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = (totalDataLen shr 8 and 0xff).toByte(); header[6] = (totalDataLen shr 16 and 0xff).toByte(); header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = 1.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] = (sampleRate shr 8 and 0xff).toByte(); header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte(); header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * bitDepth / 8).toByte(); header[33] = 0
        header[34] = bitDepth.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (dataSize and 0xff).toByte(); header[41] = (dataSize shr 8 and 0xff).toByte(); header[42] = (dataSize shr 16 and 0xff).toByte(); header[43] = (dataSize shr 24 and 0xff).toByte()
        RandomAccessFile(file, "rw").use { raf -> raf.seek(0); raf.write(header) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotification(isRecording: Boolean = true): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Recording Service", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AllRecorder is active")
            .setContentText(if (isRecording) "Recording (${currentFormat.name})..." else "Saving chunk...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher_foreground, "Stop", stopPendingIntent)
            .build()
    }
}