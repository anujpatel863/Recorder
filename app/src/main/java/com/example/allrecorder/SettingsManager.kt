package com.example.allrecorder

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {

    private const val PREFS_NAME = "AppRecorderSettings"
    private lateinit var sharedPreferences: SharedPreferences

    // --- Keys for our settings ---
    private const val KEY_CHUNK_DURATION = "chunk_duration_seconds"
    private const val KEY_SILENCE_THRESHOLD = "silence_threshold_seconds"
    private const val KEY_SMART_DETECTION = "smart_detection_enabled"

    fun init(context: Context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Getters and Setters for each setting ---

    var chunkDurationSeconds: Int
        get() = sharedPreferences.getInt(KEY_CHUNK_DURATION, 30) // Default: 30 seconds
        set(value) = sharedPreferences.edit().putInt(KEY_CHUNK_DURATION, value).apply()

    var silenceThresholdSeconds: Int
        get() = sharedPreferences.getInt(KEY_SILENCE_THRESHOLD, 15) // Default: 15 seconds
        set(value) = sharedPreferences.edit().putInt(KEY_SILENCE_THRESHOLD, value).apply()

    var isSmartDetectionEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_SMART_DETECTION, true) // Default: true
        set(value) = sharedPreferences.edit().putBoolean(KEY_SMART_DETECTION, value).apply()
}