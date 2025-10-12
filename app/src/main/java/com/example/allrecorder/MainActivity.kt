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
    }
}
