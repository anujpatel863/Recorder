package com.example.allrecorder

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {

    private const val PREFS_NAME = "com.example.allrecorder.settings"
    private lateinit var sharedPreferences: SharedPreferences

    // Existing keys from your initial code
    private const val KEY_CHUNK_DURATION = "chunk_duration_seconds"
    private const val KEY_SILENCE_THRESHOLD = "silence_threshold_seconds"
    private const val KEY_SMART_DETECTION = "smart_detection_enabled"

    // --- START OF NEW CODE ---
    private const val KEY_DIARIZATION_MODEL = "diarization_model"
    const val MODEL_SMALL = "conformer_tisid_small.tflite"
    const val MODEL_MEDIUM = "conformer_tisid_medium.tflite"
    // --- END OF NEW CODE ---


    fun init(context: Context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Your existing settings
    var chunkDurationSeconds: Int
        get() = sharedPreferences.getInt(KEY_CHUNK_DURATION, 10) // Default 10s
        set(value) = sharedPreferences.edit().putInt(KEY_CHUNK_DURATION, value).apply()

    var silenceThresholdSeconds: Int
        get() = sharedPreferences.getInt(KEY_SILENCE_THRESHOLD, 5) // Default 5s
        set(value) = sharedPreferences.edit().putInt(KEY_SILENCE_THRESHOLD, value).apply()

    var isSmartDetectionEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_SMART_DETECTION, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_SMART_DETECTION, value).apply()

    // --- START OF NEW CODE ---
    var selectedDiarizationModel: String
        get() = sharedPreferences.getString(KEY_DIARIZATION_MODEL, MODEL_SMALL) ?: MODEL_SMALL
        set(value) = sharedPreferences.edit().putString(KEY_DIARIZATION_MODEL, value).apply()
    // --- END OF NEW CODE ---
}