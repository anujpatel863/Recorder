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
    private const val RECORDING_FORMAT_KEY = "recording_format"

    // [NEW] Feature Toggles
    private const val SHOW_VISUALIZER_KEY = "show_visualizer"
    private const val AUTO_RECORD_KEY = "auto_record"
    private const val KEEP_SCREEN_ON_KEY = "keep_screen_on"
    private const val SPEAKER_DIARIZATION_KEY = "speaker_diarization"

    private const val SEMANTIC_SEARCH_KEY = "semantic_search"
    private const val HAPTIC_FEEDBACK_KEY = "haptic_feedback"

    enum class RecordingFormat(val extension: String, val mimeType: String) {
        WAV(".wav", "audio/wav"),
        M4A(".m4a", "audio/mp4")
    }

    fun init(context: Context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    }

    // --- Getters ---

    val chunkDurationMillis: Long
        get() = prefs.getString(CHUNK_DURATION_KEY, "-1")?.toLong() ?: -1L

    val asrLanguage: String
        get() = prefs.getString(ASR_LANGUAGE_KEY, "") ?: ""

    val asrModel: String
        get() = prefs.getString(ASR_MODEL_KEY, "tiny") ?: "tiny"

    var asrEnhancementEnabled: Boolean
        get() = prefs.getBoolean(ASR_ENHANCEMENT_KEY, true)
        set(value) = prefs.edit().putBoolean(ASR_ENHANCEMENT_KEY, value).apply()

    var recordingFormat: RecordingFormat
        get() {
            val value = prefs.getString(RECORDING_FORMAT_KEY, RecordingFormat.M4A.name)
            return try {
                RecordingFormat.valueOf(value!!)
            } catch (e: Exception) {
                RecordingFormat.M4A
            }
        }
        set(value) {
            prefs.edit().putString(RECORDING_FORMAT_KEY, value.name).apply()
        }

    // --- [NEW] Feature Properties ---

    var showVisualizer: Boolean
        get() = prefs.getBoolean(SHOW_VISUALIZER_KEY, true)
        set(value) = prefs.edit().putBoolean(SHOW_VISUALIZER_KEY, value).apply()

    var autoRecordOnLaunch: Boolean
        get() = prefs.getBoolean(AUTO_RECORD_KEY, false)
        set(value) = prefs.edit().putBoolean(AUTO_RECORD_KEY, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEEP_SCREEN_ON_KEY, false)
        set(value) = prefs.edit().putBoolean(KEEP_SCREEN_ON_KEY, value).apply()

    var speakerDiarizationEnabled: Boolean
        get() = prefs.getBoolean(SPEAKER_DIARIZATION_KEY, true)
        set(value) = prefs.edit().putBoolean(SPEAKER_DIARIZATION_KEY, value).apply()



    var semanticSearchEnabled: Boolean
        get() = prefs.getBoolean(SEMANTIC_SEARCH_KEY, true)
        set(value) = prefs.edit().putBoolean(SEMANTIC_SEARCH_KEY, value).apply()

    var hapticFeedback: Boolean
        get() = prefs.getBoolean(HAPTIC_FEEDBACK_KEY, true)
        set(value) = prefs.edit().putBoolean(HAPTIC_FEEDBACK_KEY, value).apply()
}