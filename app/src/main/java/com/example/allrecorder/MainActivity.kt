package com.example.allrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.allrecorder.databinding.ActivityMainBinding
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.google.gson.Gson
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var toggle: ActionBarDrawerToggle

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                toggleRecordingService()
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupNavigationDrawer()
        setupNavigationMenu()
        setupViewPager()
        setupFab()
        observeRecordingState()
    }

    private fun setupNavigationDrawer() {
        toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        binding.drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerOpened(drawerView: View) {
                updateNavigationMenuTitles()
            }
            override fun onDrawerClosed(drawerView: View) {}
            override fun onDrawerStateChanged(newState: Int) {}
        })
    }

    private fun setupNavigationMenu() {
        val smartDetectionItem = binding.navView.menu.findItem(R.id.nav_smart_detection)
        val switchView = smartDetectionItem.actionView?.findViewById<SwitchCompat>(R.id.nav_switch)
        switchView?.isChecked = SettingsManager.isSmartDetectionEnabled
        switchView?.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.isSmartDetectionEnabled = isChecked
        }
        updateNavigationMenuTitles()
    }

    private fun updateNavigationMenuTitles() {
        val menu = binding.navView.menu
        val chunkItem = menu.findItem(R.id.nav_chunk_duration)
        chunkItem.title = "Chunk Duration: ${SettingsManager.chunkDurationSeconds}s"
        val silenceItem = menu.findItem(R.id.nav_silence_threshold)
        silenceItem.title = "Silence Threshold: ${SettingsManager.silenceThresholdSeconds}s"
        val modelItem = menu.findItem(R.id.nav_model_selection)
        val modelName = if (SettingsManager.selectedDiarizationModel == SettingsManager.MODEL_SMALL) "Small" else "Medium"
        modelItem.title = "Diarization Model: $modelName"
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        item.isChecked = false
        when (item.itemId) {
            R.id.nav_chunk_duration -> showInputDialog("Set Chunk Duration", SettingsManager.chunkDurationSeconds) {
                SettingsManager.chunkDurationSeconds = it
                updateNavigationMenuTitles()
            }
            R.id.nav_silence_threshold -> showInputDialog("Set Silence Threshold", SettingsManager.silenceThresholdSeconds) {
                SettingsManager.silenceThresholdSeconds = it
                updateNavigationMenuTitles()
            }
            R.id.nav_smart_detection -> {
                val switchView = item.actionView?.findViewById<SwitchCompat>(R.id.nav_switch)
                switchView?.toggle()
            }
            R.id.nav_model_selection -> showModelSelectionDialog()
        }
        return true
    }

    private fun showModelSelectionDialog() {
        val models = arrayOf("Small (Faster)", "Medium (More Accurate)")
        val modelFiles = arrayOf(SettingsManager.MODEL_SMALL, SettingsManager.MODEL_MEDIUM)
        val checkedItem = modelFiles.indexOf(SettingsManager.selectedDiarizationModel)

        AlertDialog.Builder(this)
            .setTitle("Select Diarization Model")
            .setSingleChoiceItems(models, checkedItem) { dialog, which ->
                SettingsManager.selectedDiarizationModel = modelFiles[which]
                updateNavigationMenuTitles()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                binding.drawerLayout.closeDrawers()
            }
            .show()
    }

    private fun showInputDialog(title: String, currentValue: Int, onSave: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentValue.toString())
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                input.text.toString().toIntOrNull()?.let {
                    // --- START OF FIX ---
                    if (it > 0) onSave(it) else Toast.makeText(this, "Invalid number.", Toast.LENGTH_SHORT).show()
                    // --- END OF FIX ---
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                binding.drawerLayout.closeDrawers()
            }
            .show()
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = ViewPagerAdapter(this)
        binding.viewPager.offscreenPageLimit = 2
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_conversations)
                1 -> getString(R.string.tab_recordings)
                else -> null
            }
        }.attach()
    }

    private fun setupFab() {
        binding.fab.setOnClickListener { checkAndRequestPermissions() }
    }

    private fun toggleRecordingService() {
        val intent = Intent(this, RecordingService::class.java)
        if (RecordingService.isRecording.value) {
            stopService(intent)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private val permissionsToRequest = mutableListOf<String>()
    private fun checkAndRequestPermissions() {
        permissionsToRequest.clear()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            toggleRecordingService()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_group_conversations -> {
                runDiarizationWorker()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun runDiarizationWorker() {
        val workRequest = OneTimeWorkRequestBuilder<DiarizationWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork("ManualDiarization", ExistingWorkPolicy.REPLACE, workRequest)
        Toast.makeText(this, "Grouping process started.", Toast.LENGTH_SHORT).show()

        WorkManager.getInstance(this).getWorkInfoByIdLiveData(workRequest.id)
            .observe(this, Observer { workInfo ->
                if (workInfo == null) return@Observer
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        binding.progressLayout.visibility = View.VISIBLE
                        workInfo.progress.getString(DiarizationWorker.PROGRESS)?.let {
                            val progress = Gson().fromJson(it, DiarizationProgress::class.java)
                            binding.progressBar.progress = progress.progressPercentage
                            binding.progressStatusText.text = progress.statusMessage
                            binding.progressDetailsText.text = "${progress.processedRecordings}/${progress.totalRecordings} recordings"
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        binding.progressLayout.visibility = View.GONE
                        Toast.makeText(this, "Grouping finished.", Toast.LENGTH_SHORT).show()
                    }
                    WorkInfo.State.FAILED -> {
                        binding.progressLayout.visibility = View.GONE
                        Toast.makeText(this, "Grouping failed.", Toast.LENGTH_LONG).show()
                    }
                    else -> binding.progressLayout.visibility = View.GONE
                }
            })
    }

    private fun observeRecordingState() {
        lifecycleScope.launch {
            RecordingService.isRecording.collect { isRecording ->
                val icon = if (isRecording) android.R.drawable.ic_media_pause else android.R.drawable.ic_btn_speak_now
                binding.fab.setImageResource(icon)
            }
        }
    }


}