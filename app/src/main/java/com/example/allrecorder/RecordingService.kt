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
import android.content.pm.ServiceInfo
import android.media.*
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
import android.media.AudioDeviceInfo
import androidx.annotation.RequiresApi
import com.example.allrecorder.widgets.WidgetManager
import kotlinx.coroutines.delay

@AndroidEntryPoint
class RecordingService : Service() {

    @Inject
    lateinit var recordingDao: RecordingDao

    // --- Audio Engine ---
    private var audioRecord: AudioRecord? = null

    // --- AAC Encoding (for M4A) ---
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var audioTrackIndex = -1
    private var isMuxerStarted = false
    private var presentationTimeUs = 0L

    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // --- Config ---
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // --- State ---
    private var isRecordingInternal = false
    var recordingStartTime: Long = 0
    private lateinit var filePath: String
    private var currentFormat: SettingsManager.RecordingFormat = SettingsManager.RecordingFormat.M4A

    private val chunkHandler = Handler(Looper.getMainLooper())
    private var chunkRunnable: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentCallerInfo: String = ""
    private var isPhoneCallMode = false

    private val binder = LocalBinder()
    private val _audioDataFlow = MutableStateFlow(ByteArray(0))
    val audioDataFlow: StateFlow<ByteArray> = _audioDataFlow.asStateFlow()
    private lateinit var audioManager: AudioManager
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null

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
        // [NEW] Action to restart recording immediately (for format changes)
        const val ACTION_RESTART = "com.example.allrecorder.ACTION_RESTART"
    }

    override fun onCreate() {
        super.onCreate()
        SettingsManager.init(this)
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AllRecorder::RecordingWakeLock").apply {
            setReferenceCounted(false)
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Register callback to detect if WE are being silenced by another app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioRecordingCallback = object : AudioManager.AudioRecordingCallback() {

                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    super.onRecordingConfigChanged(configs)
                    checkIfWeAreSilenced(configs)
                }
            }
            audioManager.registerAudioRecordingCallback(audioRecordingCallback!!, null)
        }
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun checkIfWeAreSilenced(configs: List<AudioRecordingConfiguration>) {
        if (!isRecordingInternal) return

        // 1. Find our own session
        val myConfig = configs.find { it.clientAudioSessionId == audioRecord?.audioSessionId }

        // 2. Check if we are "silenced" by the OS
        if (myConfig != null) {
            if (myConfig.isClientSilenced) {
                Log.e(TAG, "CRITICAL: Microphone is being used by another app (System/Assistant). We are recording silence.")
                // Optional: Send broadcast to UI to show a Toast/Warning
                // sendBroadcast(Intent("com.example.allrecorder.SHOW_TOAST").putExtra("msg", "Microphone blocked by another app!"))
            } else {
                Log.i(TAG, "Microphone is active and capturing audio.")
            }
        }

        // 3. Debug: List all other apps using the Mic
        configs.forEach { config ->
            if (config.clientAudioSessionId != audioRecord?.audioSessionId) {
                Log.w(TAG, "Another App is recording! Source: ${config.clientAudioSource}, ID: ${config.clientAudioSessionId}")
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val restartServiceIntent = Intent(applicationContext, this.javaClass).apply {
            setPackage(packageName)
            action = ACTION_START
        }
        val restartServicePendingIntent = PendingIntent.getService(
            applicationContext, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmService.set(
            android.app.AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { stopRecording(stopService = true) }
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                // [NEW] Hot Swap Logic
                if (isRecordingInternal) {
                    Log.i(TAG, "Restarting recording due to format change...")
                    scope.launch {
                        // Stop current, but keep service alive (false)
                        stopRecording(stopService = false)
                        // Start new immediately (will pick up new settings)
                        startRecording()
                    }
                }
                return START_STICKY
            }
            ACTION_START, null -> {
                isPhoneCallMode = intent?.getBooleanExtra("IS_PHONE_CALL", false) ?: false
                val number = intent?.getStringExtra("CALLER_NUMBER")
                currentCallerInfo = if (isPhoneCallMode) getContactName(number) else ""
                startForegroundSafely()
                startRecording()
                return START_STICKY
            }
            else -> {
                startRecording()
                return START_STICKY
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startForegroundSafely() {
        try {
            val notification = createNotification(isRecording = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
        }
    }

    private fun startRecording() {
        if (isRecordingInternal) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        try { if (wakeLock?.isHeld == false) wakeLock?.acquire() } catch (e: Exception) {}

        // Read the LATEST format from settings
        currentFormat = SettingsManager.recordingFormat
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = if (isPhoneCallMode && currentCallerInfo.isNotEmpty()) {
            "${currentCallerInfo}_$timeStamp${currentFormat.extension}"
        } else {
            "Rec_$timeStamp${currentFormat.extension}"
        }
        filePath = File(filesDir, fileName).absolutePath

        try {
            prepareAudioRecord()

            if (currentFormat == SettingsManager.RecordingFormat.WAV) {
                prepareWavHeader(File(filePath))
            } else {
                prepareAacEncoder(filePath)
            }

            audioRecord?.startRecording()
            isRecordingInternal = true
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            scope.launch {
                WidgetManager.updateWidgets(applicationContext, true, recordingStartTime)
            }
            if (isPhoneCallMode) {
                scope.launch(Dispatchers.Main) {
                    delay(1500)
                    setSpeakerphoneOn(true)
                }
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(isRecording = true))

            recordingJob = scope.launch { processAudioLoop() }

            scheduleNextChunk()

        } catch (e: Exception) {
            Log.e(TAG, "Start recording failed", e)
            scope.launch { stopRecording(stopService = true) }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun prepareAudioRecord() {
        val bufferSizeInBytes = bufferSize * 2

        // [NEW] Choose source based on mode

        val audioSource = MediaRecorder.AudioSource.MIC


        try {
            audioRecord = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSizeInBytes)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "Preferred source failed, falling back to MIC")
                // Release the broken instance
                audioRecord?.release()
                // Fallback to basic MIC
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSizeInBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init AudioRecord", e)
            throw IOException("AudioRecord init failed")
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) throw IOException("AudioRecord init failed")
    }
    private fun setSpeakerphoneOn(enable: Boolean) {
        if (!isPhoneCallMode) return

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

        try {
            // MODE_IN_CALL required for the routing to apply to the voice call
            audioManager.mode = android.media.AudioManager.MODE_IN_CALL

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31+): Use setCommunicationDevice
                val devices = audioManager.availableCommunicationDevices
                if (enable) {
                    val speaker = devices.find { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) {
                        val result = audioManager.setCommunicationDevice(speaker)
                        Log.i(TAG, "Speakerphone set (Modern API): $result")
                    }
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                // Android 11 and below: Use deprecated methods
                @Suppress("DEPRECATION")
                if (audioManager.isSpeakerphoneOn != enable) {
                    audioManager.isSpeakerphoneOn = enable
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle speakerphone", e)
        }
    }

    private fun prepareWavHeader(file: File) {
        FileOutputStream(file).use { out -> out.write(ByteArray(44)) }
    }

    private fun prepareAacEncoder(path: String) {
        presentationTimeUs = 0L
        isMuxerStarted = false
        audioTrackIndex = -1

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec?.start()

        mediaMuxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun processAudioLoop() {
        val data = ByteArray(bufferSize)
        val wavOutputStream = if (currentFormat == SettingsManager.RecordingFormat.WAV) FileOutputStream(filePath, true) else null
        var totalBytesWritten = 0L

        try {
            while (isRecordingInternal) {
                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    _audioDataFlow.value = data.clone()

                    if (currentFormat == SettingsManager.RecordingFormat.WAV) {
                        wavOutputStream?.write(data, 0, read)
                        totalBytesWritten += read
                    } else {
                        encodeAac(data, read)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording Loop Error", e)
        } finally {
            if (currentFormat == SettingsManager.RecordingFormat.WAV) {
                try {
                    wavOutputStream?.close()
                    updateWavHeader(File(filePath), totalBytesWritten.toInt())
                } catch (e: Exception) {}
            }
        }
    }

    private fun encodeAac(pcmData: ByteArray, length: Int) {
        val codec = mediaCodec ?: return

        try {
            val inputIndex = codec.dequeueInputBuffer(0)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(pcmData, 0, length)

                val samples = length / 2
                val currentPts = presentationTimeUs
                presentationTimeUs += (samples * 1000000L / sampleRate)

                codec.queueInputBuffer(inputIndex, 0, length, currentPts, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)

            // [COMPLETE] Robust loop handling Format Change
            while (outputIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Muxer must start only after format change
                    audioTrackIndex = mediaMuxer?.addTrack(codec.outputFormat) ?: -1
                    mediaMuxer?.start()
                    isMuxerStarted = true
                } else if (outputIndex >= 0) {
                    val encodedData = codec.getOutputBuffer(outputIndex)
                    if (encodedData != null && isMuxerStarted && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        mediaMuxer?.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }

        } catch (e: Exception) {
            Log.e(TAG, "AAC Encoding failed", e)
        }
    }

    private suspend fun stopRecording(stopService: Boolean = true) {
        if (!isRecordingInternal) return

        isRecordingInternal = false
        isRecording = false
        scope.launch {
            WidgetManager.updateWidgets(applicationContext, false, 0L)
        }
        cancelChunking()

        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null

        recordingJob?.join()
        recordingJob = null

        if (currentFormat == SettingsManager.RecordingFormat.M4A) {
            try {
                mediaCodec?.stop()
                mediaCodec?.release()
                if (isMuxerStarted) {
                    mediaMuxer?.stop()
                }
                mediaMuxer?.release()
            } catch (e: Exception) { Log.e(TAG, "Muxer closing error", e) }
        }

        saveRecordingToDatabase(filePath, recordingStartTime, System.currentTimeMillis() - recordingStartTime)
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}

        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(isRecording = false))
        }
    }

    private suspend fun saveRecordingToDatabase(path: String, startTime: Long, duration: Long) {
        val tagsList = if (isPhoneCallMode) listOf("call") else emptyList()

        val newRecording = Recording(
            filePath = path,
            startTime = startTime,
            duration = duration,
            processingStatus = 0,
            tags = tagsList
        )
        recordingDao.insert(newRecording)
    }

    private fun scheduleNextChunk() {
        val chunkDurationMillis = SettingsManager.chunkDurationMillis
        if (chunkDurationMillis > 0) {
            chunkRunnable = Runnable {
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
        if (isRecordingInternal) scope.launch { stopRecording(stopService = true) }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch(e:Exception){}
        if (true && audioRecordingCallback != null) {
            audioManager.unregisterAudioRecordingCallback(audioRecordingCallback!!)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotificationChannel() {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Recording Service", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(isRecording: Boolean = true): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AllRecorder 24/7")
            .setContentText(if (isRecording) "Recording continuously..." else "Saving...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Stop", stopPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
    private fun getContactName(phoneNumber: String?): String {
        if (phoneNumber.isNullOrEmpty()) return "Unknown_Call"

        // Sanitize number for filename safety
        val safeNumber = phoneNumber.replace(Regex("[^a-zA-Z0-9]"), "")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "Call_$safeNumber"
        }

        val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(phoneNumber))
        val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)

        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        val name = cursor.getString(nameIndex)
                        // Sanitize name for filename (remove spaces, special chars)
                        return name.replace(Regex("[^a-zA-Z0-9]"), "_")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed", e)
        }
        return "Call_$safeNumber"
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
}