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
import android.widget.EditText
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import com.google.android.material.navigation.NavigationView


class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityMainBinding
    private var isRecording = false // Simple state tracking

    private lateinit var toggle: ActionBarDrawerToggle


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

        // --- Setup Navigation Drawer ---
        toggle = ActionBarDrawerToggle(this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        // --- End Setup ---

        setupViewPager()
        setupFab()
    }
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_chunk_duration -> {
                showInputDialog("Set Chunk Duration (seconds)", SettingsManager.chunkDurationSeconds) { newValue ->
                    SettingsManager.chunkDurationSeconds = newValue
                    Toast.makeText(this, "Chunk duration set to $newValue seconds.", Toast.LENGTH_SHORT).show()
                }
            }
            R.id.nav_silence_threshold -> {
                showInputDialog("Set Silence Threshold (seconds)", SettingsManager.silenceThresholdSeconds) { newValue ->
                    SettingsManager.silenceThresholdSeconds = newValue
                    Toast.makeText(this, "Silence threshold set to $newValue seconds.", Toast.LENGTH_SHORT).show()
                }
            }
            R.id.nav_smart_detection -> {
                // This item contains the switch, so we handle its click
                val switch = item.actionView?.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.nav_switch)
                switch?.let {
                    it.isChecked = !it.isChecked
                    SettingsManager.isSmartDetectionEnabled = it.isChecked
                }
                // We return false here to allow the checkable behavior to work correctly
                return false
            }
        }
        binding.drawerLayout.closeDrawers()
        return true
    }

    override fun onResume() {
        super.onResume()
        // Update the switch state every time the drawer is opened
        val smartDetectionItem = binding.navView.menu.findItem(R.id.nav_smart_detection)
        val switch = smartDetectionItem.actionView?.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.nav_switch)
        switch?.isChecked = SettingsManager.isSmartDetectionEnabled
    }

    private fun showInputDialog(title: String, currentValue: Int, onSave: (Int) -> Unit) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(title)

        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.setText(currentValue.toString())
        builder.setView(input)

        builder.setPositiveButton("Save") { _, _ ->
            val newValue = input.text.toString().toIntOrNull()
            if (newValue != null && newValue > 0) {
                onSave(newValue)
            } else {
                Toast.makeText(this, "Please enter a valid number.", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = ViewPagerAdapter(this)
        // ADD THIS LINE to improve swipe performance
        binding.viewPager.offscreenPageLimit = 1

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
