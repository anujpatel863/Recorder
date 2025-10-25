package com.example.allrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.allrecorder.databinding.ActivityMainBinding
import com.google.android.material.navigation.NavigationView
import com.google.gson.Gson
import android.view.Menu
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityMainBinding
    private val PERMISSION_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        setupDrawer()

        if (!hasPermissions()) {
            requestPermissions()
        }

        setupViewPager()
        observeWorkerProgress()
        setupOnBackPressed()
    }

    private fun setupDrawer() {
        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
    }

    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = adapter
        com.google.android.material.tabs.TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                1 -> "Recordings"
                0 -> "Conversations"
                else -> null
            }
        }.attach()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    // --- ADD THIS FUNCTION TO HANDLE CLICKS ---
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_process_now -> {
                startManualProcessing()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startManualProcessing() {
        Toast.makeText(this, "Starting manual processing...", Toast.LENGTH_SHORT).show()
        val processWorkRequest = OneTimeWorkRequestBuilder<ProcessingWorker>().build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "ManualProcessing", // Use a unique name for the manual task
            ExistingWorkPolicy.KEEP, // Don't run if one is already running
            processWorkRequest
        )
    }

    private fun observeWorkerProgress() {
        val workManager = WorkManager.getInstance(applicationContext)
        workManager.getWorkInfosForUniqueWorkLiveData(ProcessingWorker.WORK_NAME)
            .observe(this) { workInfos ->
                if (workInfos.isNullOrEmpty()) return@observe

                val workInfo = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                if (workInfo != null) {
                    binding.progressLayout.visibility = View.VISIBLE
                    val progressData = workInfo.progress
                    val progressJson = progressData.getString(ProcessingWorker.PROGRESS)
                    if (progressJson != null) {
                        val progress = Gson().fromJson(progressJson, DiarizationProgress::class.java)
                        // This line will now work correctly
                        binding.progressText.text = progress.statusMessage
                        binding.progressBar.progress = progress.progressPercentage
                    }
                } else {
                    binding.progressLayout.visibility = View.GONE
                }
            }
    }

    // CORRECTED: The return type is now Boolean
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.nav_settings) {
            showSettingsFragment()
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun showSettingsFragment() {
        binding.mainContentContainer.visibility = View.GONE
        binding.settingsFragmentContainer.visibility = View.VISIBLE

        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_fragment_container, SettingsFragment())
            .addToBackStack("settings")
            .commit()
    }

    private fun showMainContent() {
        binding.mainContentContainer.visibility = View.VISIBLE
        binding.settingsFragmentContainer.visibility = View.GONE
    }

    private fun setupOnBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                }
                else if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    showMainContent()
                }
                else {
                    finish()
                }
            }
        })
    }

    // --- Permission Handling ---

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Audio recording permission is required to use this app.", Toast.LENGTH_LONG).show()
            }
        }
    }
}