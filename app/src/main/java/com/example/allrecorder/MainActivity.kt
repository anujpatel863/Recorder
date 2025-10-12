package com.example.allrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.*
import com.example.allrecorder.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recordingDao: RecordingDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var recordingAdapter: RecordingAdapter
    private lateinit var conversationAdapter: ConversationAdapter

    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlaying: Recording? = null
    private val handler = Handler(Looper.getMainLooper())

    private val permissionsToRequest = mutableListOf<String>()

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                startRecordingService()
            } else {
                Toast.makeText(this, "Permissions not granted. Cannot start service.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = AppDatabase.getDatabase(applicationContext)
        recordingDao = database.recordingDao()
        conversationDao = database.conversationDao()

        setupRecyclerViews()
        observeData()

        binding.btnStartService.setOnClickListener {
            checkAndRequestPermissions()
        }

        binding.btnStopService.setOnClickListener {
            stopRecordingService()
        }

        binding.btnRunDiarization.setOnClickListener {
            runDiarizationWorkerNow()
        }

        scheduleDiarizationWorker()
    }

    private fun runDiarizationWorkerNow() {
        // Enqueue a one-time request to run the worker immediately.
        val workRequest = OneTimeWorkRequestBuilder<DiarizationWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "ManualDiarization",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        Toast.makeText(this, "Grouping process started.", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleDiarizationWorker() {
        val constraints = Constraints.Builder()
            .setRequiresDeviceIdle(true)
            .setRequiresCharging(true)
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<DiarizationWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DiarizationWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )
    }

    private fun setupRecyclerViews() {
        // Conversations RecyclerView
        conversationAdapter = ConversationAdapter()
        binding.rvConversations.apply {
            adapter = conversationAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        // Recordings RecyclerView
        recordingAdapter = RecordingAdapter(
            onPlayClicked = { recording -> playRecording(recording) },
            onPauseClicked = { pauseRecording() },
            onSeekBarChanged = { newPosition -> mediaPlayer?.seekTo(newPosition) },
            onEditClicked = { recording -> showRenameDialog(recording) },
            onDeleteClicked = { recording -> deleteRecording(recording) }
        )
        binding.rvRecordings.apply {
            adapter = recordingAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            // Observe conversations
            conversationDao.getAllConversations().collect { conversations ->
                conversationAdapter.submitList(conversations)
            }
        }
        lifecycleScope.launch {
            // Observe individual recordings
            recordingDao.getAllRecordings().collect { recordings ->
                recordingAdapter.submitList(recordings)
            }
        }
    }

    // --- Media Player and CRUD operation methods remain the same ---
    private val updateSeekBar = object : Runnable {
        override fun run() {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    val currentPosition = it.currentPosition
                    recordingAdapter.setPlaybackState(currentlyPlaying?.id, currentPosition)
                    handler.postDelayed(this, 100)
                }
            }
        }
    }

    private fun playRecording(recording: Recording) {
        stopPlayback()
        currentlyPlaying = recording
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(recording.filePath)
                prepareAsync()
                setOnPreparedListener {
                    it.start()
                    handler.post(updateSeekBar)
                    recordingAdapter.setPlaybackState(recording.id, 0)
                }
                setOnCompletionListener {
                    stopPlayback()
                }
            } catch (e: IOException) {
                Toast.makeText(this@MainActivity, "Could not play file", Toast.LENGTH_SHORT).show()
                stopPlayback()
            }
        }
    }

    private fun pauseRecording() {
        mediaPlayer?.pause()
        handler.removeCallbacks(updateSeekBar)
        recordingAdapter.setPlaybackState(null, 0)
    }

    private fun stopPlayback() {
        mediaPlayer?.release()
        mediaPlayer = null
        handler.removeCallbacks(updateSeekBar)
        recordingAdapter.setPlaybackState(null, 0)
        currentlyPlaying = null
    }

    private fun showRenameDialog(recording: Recording) {
        val editText = EditText(this).apply {
            setText(File(recording.filePath).nameWithoutExtension)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Recording")
            .setView(editText)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString()
                if (newName.isNotBlank()) {
                    renameRecording(recording, "$newName.aac")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameRecording(recording: Recording, newFileName: String) {
        val oldFile = File(recording.filePath)
        val newFile = File(oldFile.parent, newFileName)

        if (newFile.exists()) {
            Toast.makeText(this, "A file with this name already exists", Toast.LENGTH_SHORT).show()
            return
        }

        if (oldFile.renameTo(newFile)) {
            val updatedRecording = recording.copy(filePath = newFile.absolutePath)
            lifecycleScope.launch {
                recordingDao.update(updatedRecording)
            }
            Toast.makeText(this, "Renamed successfully", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteRecording(recording: Recording) {
        if(recording.id == currentlyPlaying?.id) {
            stopPlayback()
        }

        lifecycleScope.launch {
            recordingDao.delete(recording)
            try {
                File(recording.filePath).delete()
            } catch (e: Exception) {
                // Log error if needed
            }
            Toast.makeText(this@MainActivity, "Recording deleted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        permissionsToRequest.clear()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            startRecordingService()
        }
    }

    private fun startRecordingService() {
        val serviceIntent = Intent(this, RecordingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Recording service started", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecordingService() {
        val serviceIntent = Intent(this, RecordingService::class.java)
        stopService(serviceIntent)
        Toast.makeText(this, "Recording service stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

