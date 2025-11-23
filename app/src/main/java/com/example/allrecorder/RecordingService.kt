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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint // [1] Required for Injection
class RecordingService : Service() {

    @Inject // [2] Inject Singleton DAO
    lateinit var recordingDao: RecordingDao

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private var isRecordingInternal = false
    // removed manual dao property

    private var recordingStartTime: Long = 0
    private lateinit var filePath: String

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
        // [3] Removed manual DB creation. Hilt injects it automatically.
        SettingsManager.init(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AllRecorder::RecordingWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    // ... [Rest of the file is identical to your upload] ...
    // Copy the onStartCommand, startRecording, writeAudioDataToFile, etc. from your existing file.
    // They do not need changes, as 'recordingDao' is now provided via @Inject.

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Received ACTION_STOP from notification.")
                stopRecording(stopService = true)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                Log.d(TAG, "Received ACTION_START.")
                startRecording()
                return START_STICKY
            }
            null -> {
                Log.d(TAG, "Service restarting (null intent). Starting recording.")
                startRecording()
                return START_STICKY
            }
            else -> {
                Log.d(TAG, "Default onStartCommand. Starting recording.")
                startRecording()
                return START_STICKY
            }
        }
    }

    private fun startRecording() {
        if (isRecordingInternal) {
            Log.w(TAG, "Recording is already in progress.")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted. Stopping service.")
            stopSelf()
            return
        }

        try {
            wakeLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wakeLock", e)
        }

        val fileName = "Rec_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.wav"
        filePath = File(filesDir, fileName).absolutePath

        try {
            writePlaceholderWavHeader(File(filePath))

            val bufferSizeInBytes = bufferSize * 2
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSizeInBytes
            )
            bufferSize = bufferSizeInBytes

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize.")
                stopSelf()
                return
            }

            audioRecord?.startRecording()
            isRecordingInternal = true
            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            startForeground(NOTIFICATION_ID, createNotification(isRecording = true))
            Log.i(TAG, "Recording started: $filePath")

            recordingJob = scope.launch {
                writeAudioDataToFile(filePath)
            }
            scheduleNextChunk()

        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord start() failed", e)
            scope.launch {
                releaseRecorder()
            }
            stopSelf()
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
            Log.e(TAG, "Error writing WAV file", e)
        } finally {
            try {
                fileOutputStream.close()
                updateWavHeader(File(path), totalBytesWritten.toInt())
                Log.d(TAG, "WAV header updated with final size: $totalBytesWritten bytes")
            } catch (e: IOException) {
                Log.e(TAG, "Error closing file output stream or updating header", e)
            }
        }
    }

    private fun stopRecording(stopService: Boolean = true) {
        if (!isRecordingInternal) {
            return
        }
        isRecordingInternal = false
        isRecording = false

        cancelChunking()
        val recordingDuration = System.currentTimeMillis() - recordingStartTime

        scope.launch {
            releaseRecorder()
            saveRecordingToDatabase(filePath, recordingStartTime, recordingDuration)
        }

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wakeLock", e)
        }

        if (stopService) {
            Log.d(TAG, "Stopping service.")
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        } else {
            Log.d(TAG, "Cycling chunk, service instance will remain.")
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(isRecording = false))
        }
    }

    private suspend fun releaseRecorder() {
        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping AudioRecord", e)
                }
            }
            release()
        }
        audioRecord = null

        recordingJob?.join()
        recordingJob = null
    }

    private fun scheduleNextChunk() {
        val chunkDurationMillis = SettingsManager.chunkDurationMillis
        if (chunkDurationMillis > 0) {
            Log.i(TAG, "Scheduling next recording chunk in ${chunkDurationMillis / 1000} seconds.")
            chunkRunnable = Runnable {
                Log.i(TAG, "Chunk time reached. Cycling recording.")

                stopRecording(stopService = false)

                startRecording()
            }
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
        scope.launch {
            val newRecording = Recording(
                filePath = path,
                startTime = startTime,
                duration = duration,
                processingStatus = 0
            )
            recordingDao.insert(newRecording)
            Log.i(TAG, "Recording saved to database: $path")
        }
    }

    private fun createNotification(isRecording: Boolean = true): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Recording Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = NotificationCompat.Action(
            R.drawable.ic_launcher_foreground,
            "Stop",
            stopPendingIntent
        )

        val contentText = if (isRecording) "Recording in progress..." else "Saving chunk..."

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AllRecorder is active")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    override fun onDestroy() {
        if (isRecordingInternal) {
            stopRecording(stopService = true)
        }
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released in onDestroy.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in final wakelock release", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    @Throws(IOException::class)
    private fun writePlaceholderWavHeader(file: File) {
        FileOutputStream(file).use { out ->
            val header = ByteArray(44)
            out.write(header)
        }
    }

    @Throws(IOException::class)
    private fun updateWavHeader(file: File, dataSize: Int) {
        val channels = 1
        val bitDepth = 16
        val byteRate = sampleRate * channels * bitDepth / 8
        val totalDataLen = dataSize + 36
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = (totalDataLen shr 8 and 0xff).toByte(); header[6] = (totalDataLen shr 16 and 0xff).toByte(); header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] = (sampleRate shr 8 and 0xff).toByte(); header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte(); header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * bitDepth / 8).toByte(); header[33] = 0
        header[34] = bitDepth.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (dataSize and 0xff).toByte(); header[41] = (dataSize shr 8 and 0xff).toByte(); header[42] = (dataSize shr 16 and 0xff).toByte(); header[43] = (dataSize shr 24 and 0xff).toByte()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }
}