package com.example.allrecorder

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object SettingsManager {
    private const val PREFS_NAME = "allrecorder_prefs"
    lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Initialize Flows with current values
        _visualizerColorFlow.value = visualizerColor
        _visualizerColorSecondaryFlow.value = visualizerColorSecondary
        _visualizerGradientFlow.value = visualizerGradient
    }

    // ... (Keep existing properties like autoRecord, etc.) ...
    var autoRecordOnLaunch: Boolean
        get() = prefs.getBoolean("auto_record_launch", false)
        set(value) = prefs.edit().putBoolean("auto_record_launch", value).apply()

    var autoRecordOnBoot: Boolean
        get() = prefs.getBoolean("auto_record_boot", false)
        set(value) = prefs.edit().putBoolean("auto_record_boot", value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", false)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    var hapticFeedback: Boolean
        get() = prefs.getBoolean("haptic_feedback", true)
        set(value) = prefs.edit().putBoolean("haptic_feedback", value).apply()

    // AI & Format
    var asrEnhancementEnabled: Boolean
        get() = prefs.getBoolean("asr_enhancement", false)
        set(value) = prefs.edit().putBoolean("asr_enhancement", value).apply()

    var speakerDiarizationEnabled: Boolean
        get() = prefs.getBoolean("speaker_diarization", false)
        set(value) = prefs.edit().putBoolean("speaker_diarization", value).apply()

    var semanticSearchEnabled: Boolean
        get() = prefs.getBoolean("semantic_search", false)
        set(value) = prefs.edit().putBoolean("semantic_search", value).apply()

    var showVisualizer: Boolean
        get() = prefs.getBoolean("show_visualizer", true)
        set(value) = prefs.edit().putBoolean("show_visualizer", value).apply()

    var simplePlaybackEnabled: Boolean
        get() = prefs.getBoolean("simple_playback", false)
        set(value) = prefs.edit().putBoolean("simple_playback", value).apply()

    // --- REACTIVE VISUALIZER SETTINGS ---

    // 1. Primary Color
    private val _visualizerColorFlow = MutableStateFlow(Color(0xFF6200EE).toArgb())
    val visualizerColorFlow = _visualizerColorFlow.asStateFlow()

    var visualizerColor: Int
        get() = prefs.getInt("visualizer_color", Color(0xFF6200EE).toArgb())
        set(value) {
            prefs.edit().putInt("visualizer_color", value).apply()
            _visualizerColorFlow.value = value // Notify observers
        }

    // 2. Secondary Color
    private val _visualizerColorSecondaryFlow = MutableStateFlow(Color(0xFF03DAC6).toArgb())
    val visualizerColorSecondaryFlow = _visualizerColorSecondaryFlow.asStateFlow()

    var visualizerColorSecondary: Int
        get() = prefs.getInt("visualizer_color_secondary", Color(0xFF03DAC6).toArgb())
        set(value) {
            prefs.edit().putInt("visualizer_color_secondary", value).apply()
            _visualizerColorSecondaryFlow.value = value // Notify observers
        }

    // 3. Gradient Toggle
    private val _visualizerGradientFlow = MutableStateFlow(true)
    val visualizerGradientFlow = _visualizerGradientFlow.asStateFlow()

    var visualizerGradient: Boolean
        get() = prefs.getBoolean("visualizer_gradient", true)
        set(value) {
            prefs.edit().putBoolean("visualizer_gradient", value).apply()
            _visualizerGradientFlow.value = value // Notify observers
        }

    // --- (Existing Enums & Format) ---
    enum class RecordingFormat(val extension: String, val mimeType: String) {
        M4A(".m4a", "audio/mp4"),
        WAV(".wav", "audio/wav")
    }

    var recordingFormat: RecordingFormat
        get() {
            val name = prefs.getString("recording_format", RecordingFormat.M4A.name)
            return try { RecordingFormat.valueOf(name!!) } catch (e: Exception) { RecordingFormat.M4A }
        }
        set(value) = prefs.edit().putString("recording_format", value.name).apply()

    var chunkDurationMillis: Long
        get() = prefs.getString("chunk_duration", "0")?.toLongOrNull() ?: 0L
        set(value) = prefs.edit().putString("chunk_duration", value.toString()).apply()

    var asrLanguage: String
        get() = prefs.getString("asr_language", "en") ?: "en"
        set(value) = prefs.edit().putString("asr_language", value).apply()

    var asrModel: String
        get() = prefs.getString("asr_model", "tiny") ?: "tiny"
        set(value) = prefs.edit().putString("asr_model", value).apply()

    var callRecordingEnabled: Boolean
        get() = prefs.getBoolean("call_recording_enabled", true) // Default is TRUE
        set(value) = prefs.edit().putBoolean("call_recording_enabled", value).apply()
    var autoDeleteEnabled: Boolean
        get() = prefs.getBoolean("auto_delete_enabled", false)
        set(value) = prefs.edit().putBoolean("auto_delete_enabled", value).apply()

    // Number of days to keep recordings (e.g., 2 or 7)
    var retentionDays: Int
        get() = prefs.getInt("retention_days", 7)
        set(value) = prefs.edit().putInt("retention_days", value).apply()

    // Files with this tag will NOT be deleted (e.g., "keep")
    var protectedTag: String
        get() = prefs.getString("protected_tag", "keep") ?: "keep"
        set(value) = prefs.edit().putString("protected_tag", value).apply()
}