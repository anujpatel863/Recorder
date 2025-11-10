package com.example.allrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private var isRecordingInternal = false
    private lateinit var recordingDao: RecordingDao
    private var recordingStartTime: Long = 0
    private lateinit var filePath: String

    private val chunkHandler = Handler(Looper.getMainLooper())
    private var chunkRunnable: Runnable? = null

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
    }

    override fun onCreate() {
        super.onCreate()
        recordingDao = AppDatabase.getDatabase(this).recordingDao()
        SettingsManager.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        startRecording()
        return START_STICKY
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

        val fileName = "Rec_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.wav"
        filePath = File(filesDir, fileName).absolutePath

        try {
            // --- ROBUSTNESS: Write placeholder header first ---
            writePlaceholderWavHeader(File(filePath))

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize.")
                stopSelf()
                return
            }

            audioRecord?.startRecording()
            isRecordingInternal = true
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            startForeground(NOTIFICATION_ID, createNotification())
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
        // --- ROBUSTNESS: Append to the existing file ---
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
                // --- ROBUSTNESS: Update header after closing stream ---
                updateWavHeader(File(path), totalBytesWritten.toInt())
                Log.d(TAG, "WAV header updated with final size: $totalBytesWritten bytes")
            } catch (e: IOException) {
                Log.e(TAG, "Error closing file output stream or updating header", e)
            }
        }
    }

    private fun stopRecording() {
        if (!isRecordingInternal) {
            return
        }
        isRecordingInternal = false
        isRecording = false

        cancelChunking()
        val recordingDuration = System.currentTimeMillis() - recordingStartTime

        // Launch a coroutine to handle the suspension and DB operation
        scope.launch {
            // This will trigger the 'finally' block in writeAudioDataToFile, which updates the header
            releaseRecorder()
            saveRecordingToDatabase(filePath, recordingStartTime, recordingDuration)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun releaseRecorder() { // MODIFIED to be suspend
        // This will cause the while-loop in writeAudioDataToFile to exit
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

        // Wait for the writing job to finish to ensure the header is updated
        recordingJob?.join()
        recordingJob = null
    }

    private fun scheduleNextChunk() {
        val chunkDurationMillis = SettingsManager.chunkDurationMillis
        if (chunkDurationMillis > 0) {
            Log.i(TAG, "Scheduling next recording chunk in ${chunkDurationMillis / 1000} seconds.")
            chunkRunnable = Runnable {
                Log.i(TAG, "Chunk time reached. Restarting recording.")
                // Stop the current recording, which saves it
                stopRecording()
                // Start a new one
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
                isProcessed = false,
                conversationId = null // REMOVED
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
                NotificationManager.IMPORTANCE_MIN // MODIFIED: Make notification less intrusive
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AllRecorder is active")
            .setContentText("Recording in progress...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN) // MODIFIED
            .build()
    }

    override fun onDestroy() {
        if (isRecordingInternal) {
            stopRecording()
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
            // Write a dummy header that will be overwritten later
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