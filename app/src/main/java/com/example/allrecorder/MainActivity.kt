package com.example.allrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import com.example.allrecorder.databinding.ActivityMainBinding
import com.google.android.material.navigation.NavigationView
import android.view.Menu
import androidx.core.content.edit

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityMainBinding
    private val PERMISSION_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize SettingsManager
        SettingsManager.init(this)

        setSupportActionBar(binding.toolbar)
        setupDrawer()

        if (!hasPermissions()) {
            requestPermissions()
        }

        setupViewPager()
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_group_conversations -> {
                Toast.makeText(this, "Group Conversations clicked (no action)", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle preference items
        when (item.itemId) {
            R.id.nav_chunk_duration -> showListPreferenceDialog(
                "chunk_duration",
                "Recording Chunk Duration",
                R.array.chunk_duration_entries,
                R.array.chunk_duration_values
            )
            R.id.nav_diarization_model -> showListPreferenceDialog(
                "diarization_model",
                "Diarization Model",
                R.array.diarization_model_entries,
                R.array.diarization_model_values
            )
            R.id.nav_silence_sensitivity -> showSeekBarPreferenceDialog(
                "silence_sensitivity",
                "Silence Sensitivity",
                3,
                30,
                10 // Default
            )
            R.id.nav_speaker_strictness -> showSeekBarPreferenceDialog(
                "speaker_strictness",
                "Speaker Detection Strictness",
                70,
                95,
                85 // Default
            )
            R.id.nav_asr_language -> showListPreferenceDialog(
                "asr_language",
                "Transcription Language",
                R.array.asr_language_entries,
                R.array.asr_language_values
            )
            R.id.nav_asr_decoder -> showListPreferenceDialog(
                "asr_decoder",
                "Decoder Type",
                R.array.asr_decoder_entries,
                R.array.asr_decoder_values
            )
            // Header items are disabled and will not be clicked
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true // Return true to indicate item was handled
    }

    private fun showListPreferenceDialog(key: String, title: String, entriesResId: Int, entryValuesResId: Int) {
        val entries = resources.getStringArray(entriesResId)
        val entryValues = resources.getStringArray(entryValuesResId)
        val currentValue = SettingsManager.prefs.getString(key, null)
        val currentEntryIndex = entryValues.indexOf(currentValue).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(entries, currentEntryIndex) { dialog, which ->
                val selectedValue = entryValues[which]
                SettingsManager.prefs.edit {
                    putString(key, selectedValue)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSeekBarPreferenceDialog(key: String, title: String, min: Int, max: Int, default: Int) {
        val currentValue = SettingsManager.prefs.getInt(key, default)

        // Create layout for dialog
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val valueTextView = TextView(context).apply {
            text = currentValue.toString()
            textSize = 16f
            setPadding(0, 0, 0, 24)
            gravity = android.view.Gravity.CENTER
        }

        val seekBar = SeekBar(context).apply {
            this.max = max - min
            this.progress = currentValue - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueTextView.text = (progress + min).toString()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        layout.addView(valueTextView)
        layout.addView(seekBar)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("Set") { dialog, _ ->
                val selectedValue = seekBar.progress + min
                SettingsManager.prefs.edit {
                    putInt(key, selectedValue)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Removed showSettingsFragment()
    // Removed showMainContent()

    private fun setupOnBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                }
                // Removed check for fragmentManager backStack
                else {
                    finish()
                }
            }
        })
    }

    // --- Permission Handling (Unchanged) ---

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