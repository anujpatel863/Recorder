package com.example.allrecorder

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object SettingsManager {

    lateinit var prefs: SharedPreferences

    // --- Keys ---
    private const val CHUNK_DURATION_KEY = "chunk_duration"
    private const val ASR_LANGUAGE_KEY = "asr_language"
    private const val ASR_MODEL_KEY = "asr_model"
    private const val ASR_ENHANCEMENT_KEY = "asr_enhancement"

    fun init(context: Context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    }

    // --- Getters ---

    /**
     * Recording chunk duration in milliseconds.
     * Default: -1L (Continuous recording)
     */
    val chunkDurationMillis: Long
        get() = prefs.getString(CHUNK_DURATION_KEY, "-1")?.toLong() ?: -1L

    /**
     * ASR language code (e.g., "en", "hi", "gu").
     * Default: "" (Empty string for auto-detection)
     */
    val asrLanguage: String
        get() = prefs.getString(ASR_LANGUAGE_KEY, "") ?: ""

    /**
     * ASR model name (e.g., "tiny", "base", "small").
     * Default: "tiny"
     */
    val asrModel: String
        get() = prefs.getString(ASR_MODEL_KEY, "tiny") ?: "tiny"
    val asrEnhancementEnabled: Boolean // NEW
        get() = prefs.getBoolean(ASR_ENHANCEMENT_KEY, true)
}