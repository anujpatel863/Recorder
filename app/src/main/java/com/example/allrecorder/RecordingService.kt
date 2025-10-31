package com.example.allrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {

    // --- NEW AudioRecord Implementation ---
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    // --- End new properties ---

    private var isRecordingInternal = false
    private lateinit var recordingDao: RecordingDao
    private var recordingStartTime: Long = 0
    private lateinit var filePath: String
    private lateinit var rawFilePath: String // For temporary raw PCM data

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
        SettingsManager.init(this)
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted. Stopping service.")
            stopSelf()
            return
        }

        // --- REFACTOR: Use .wav extension ---
        val fileName = "Rec_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.wav"
        filePath = File(filesDir, fileName).absolutePath
        rawFilePath = File(filesDir, "temp_rec.raw").absolutePath // Temp file

        try {
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

            // --- REFACTOR: Start background coroutine to write file ---
            recordingJob = scope.launch {
                writeAudioDataToFile(rawFilePath)
            }
            scheduleNextChunk()

        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord start() failed", e)
            releaseRecorder()
            stopSelf()
        }
    }

    private suspend fun writeAudioDataToFile(path: String) {
        val data = ByteArray(bufferSize)
        var fileOutputStream: FileOutputStream? = null
        try {
            fileOutputStream = FileOutputStream(path)
            while (isRecordingInternal) {
                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    fileOutputStream.write(data, 0, read)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error writing raw audio file", e)
        } finally {
            try {
                fileOutputStream?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing file output stream", e)
            }
        }
    }

    private fun stopRecording() {
        if (!isRecordingInternal) {
            return
        }

        cancelChunking()

        val recordingDuration = System.currentTimeMillis() - recordingStartTime
        releaseRecorder() // This will stop the coroutine loop

        // --- NEW: Create .wav file from raw PCM data ---
        val rawFile = File(rawFilePath)
        val wavFile = File(filePath)
        try {
            rawFile.renameTo(wavFile)
            writeWavHeader(wavFile, channelConfig, sampleRate, audioFormat, wavFile.length().toInt())
            Log.i(TAG, "WAV file created: $filePath")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create WAV file", e)
        }
        if(rawFile.exists()) rawFile.delete() // Clean up temp file

        saveRecordingToDatabase(filePath, recordingStartTime, recordingDuration)

        stopForeground(true)
        stopSelf()
    }

    private fun releaseRecorder() {
        if (isRecordingInternal) {
            isRecordingInternal = false
            isRecording = false
        }

        recordingJob?.cancel() // Stop coroutine
        recordingJob = null

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
    }

    private fun scheduleNextChunk() {
        val chunkDurationMillis = SettingsManager.chunkDurationMillis
        if (chunkDurationMillis > 0) {
            Log.i(TAG, "Scheduling next recording chunk in ${chunkDurationMillis / 1000} seconds.")
            chunkRunnable = Runnable {
                Log.i(TAG, "Chunk time reached. Restarting recording.")

                val recordingDuration = System.currentTimeMillis() - recordingStartTime
                releaseRecorder() // This will stop the coroutine

                // --- REFACTOR: Save chunk as WAV ---
                val rawFile = File(rawFilePath)
                val chunkWavFile = File(filePath) // Use the path already set
                try {
                    rawFile.renameTo(chunkWavFile)
                    writeWavHeader(chunkWavFile, channelConfig, sampleRate, audioFormat, chunkWavFile.length().toInt())
                    Log.i(TAG, "WAV chunk file created: $filePath")
                    saveRecordingToDatabase(filePath, recordingStartTime, recordingDuration)
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to create WAV chunk file", e)
                }
                if(rawFile.exists()) rawFile.delete()

                // Start new recording
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
        // ... (This function is unchanged) ...
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
            stopRecording() // Ensure recording is stopped and saved
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- NEW: WAV Header Writer ---
    @Throws(IOException::class)
    private fun writeWavHeader(file: File, channelConfig: Int, sampleRate: Int, audioFormat: Int, dataSize: Int) {
        val channels = if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        val bitDepth = if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) 16 else 8
        val byteRate = sampleRate * channels * bitDepth / 8
        val totalDataLen = dataSize + 36
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk (16 for PCM)
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // 2 bytes: AudioFormat (1 for PCM)
        header[21] = 0
        header[22] = channels.toByte() // 2 bytes: NumChannels
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte() // 4 bytes: SampleRate
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte() // 4 bytes: ByteRate
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * bitDepth / 8).toByte() // 2 bytes: BlockAlign
        header[33] = 0
        header[34] = bitDepth.toByte() // 2 bytes: BitsPerSample
        header[35] = 0
        header[36] = 'd'.code.toByte() // 'data' chunk
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (dataSize and 0xff).toByte() // 4 bytes: DataSize
        header[41] = (dataSize shr 8 and 0xff).toByte()
        header[42] = (dataSize shr 16 and 0xff).toByte()
        header[43] = (dataSize shr 24 and 0xff).toByte()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }
}