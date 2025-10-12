package com.example.allrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.allrecorder.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayoutMediator
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import android.view.View
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import com.google.gson.Gson


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRecording = false // Simple state tracking


    private val permissionsToRequest = mutableListOf<String>()
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                toggleRecording()
            } else {
                Toast.makeText(this, "Permissions not granted. Cannot record.", Toast.LENGTH_SHORT).show()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupViewPager()
        setupFab()
        observeRecordingState()
    }


    private fun setupViewPager() {
        binding.viewPager.adapter = ViewPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_conversations)
                1 -> getString(R.string.tab_recordings)
                else -> null
            }
        }.attach()
    }

    private fun setupFab() {
        binding.fab.setOnClickListener {
            checkAndRequestPermissions()
        }
    }

    private fun toggleRecording() {
        isRecording = !isRecording
        if (isRecording) {
            startRecordingService()
            binding.fab.setImageResource(android.R.drawable.ic_media_pause) // Icon for "stop"
        } else {
            stopRecordingService()
            binding.fab.setImageResource(android.R.drawable.ic_btn_speak_now) // Icon for "record"
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
            toggleRecording()
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_group_conversations -> {
                runDiarizationWorkerNow()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun runDiarizationWorkerNow() {
        val workRequest = OneTimeWorkRequestBuilder<DiarizationWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "ManualDiarization",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        Toast.makeText(this, "Grouping process started.", Toast.LENGTH_SHORT).show()

        // Observe the worker's progress
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(workRequest.id)
            .observe(this, Observer { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.RUNNING -> {
                            binding.progressLayout.visibility = View.VISIBLE
                            val progressDataString = workInfo.progress.getString(DiarizationWorker.PROGRESS)
                            if (progressDataString != null) {
                                val progress = Gson().fromJson(progressDataString, DiarizationProgress::class.java)

                                // Update UI with every bit of info
                                binding.progressBar.progress = progress.progressPercentage
                                binding.progressStatusText.text = progress.statusMessage
                                val details = "${progress.processedRecordings}/${progress.totalRecordings} recordings analyzed"
                                binding.progressDetailsText.text = details
                            }
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            binding.progressLayout.visibility = View.GONE
                            Toast.makeText(this, "Grouping finished successfully.", Toast.LENGTH_SHORT).show()
                        }
                        WorkInfo.State.FAILED -> {
                            binding.progressLayout.visibility = View.GONE
                            Toast.makeText(this, "Grouping failed. Please try again.", Toast.LENGTH_LONG).show()
                        }
                        else -> { // Handles ENQUEUED, CANCELLED, BLOCKED
                            binding.progressLayout.visibility = View.GONE
                        }
                    }
                }
            })
    }
    private fun observeRecordingState() {
        lifecycleScope.launch {
            RecordingService.isRecording.collect { isRecording ->
                this@MainActivity.isRecording = isRecording
                if (isRecording) {
                    binding.fab.setImageResource(android.R.drawable.ic_media_pause)
                } else {
                    binding.fab.setImageResource(android.R.drawable.ic_btn_speak_now)
                }
            }
        }
    }
}
