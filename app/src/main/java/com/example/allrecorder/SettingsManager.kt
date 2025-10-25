package com.example.allrecorder

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object SettingsManager {

    private lateinit var prefs: SharedPreferences

    private const val DIARIZATION_MODEL_KEY = "diarization_model"
    private const val CHUNK_DURATION_KEY = "chunk_duration"
    private const val SILENCE_SENSITIVITY_KEY = "silence_sensitivity"
    private const val SPEAKER_STRICTNESS_KEY = "speaker_strictness"
    private const val ASR_LANGUAGE_KEY = "asr_language"
    private const val ASR_DECODER_KEY = "asr_decoder"

    fun init(context: Context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    }

    val selectedDiarizationModel: String
        get() = prefs.getString(DIARIZATION_MODEL_KEY, "conformer_tisid_medium.tflite") ?: "conformer_tisid_medium.tflite"

    val chunkDurationMillis: Long
        get() = prefs.getString(CHUNK_DURATION_KEY, "-1")?.toLong() ?: -1L
    val silenceSensitivitySeconds: Int
        get() = prefs.getInt(SILENCE_SENSITIVITY_KEY, 10)

    // SeekBarPreference saves as int, so we retrieve int and convert to float for the worker
    val speakerStrictnessThreshold: Float
        get() = prefs.getInt(SPEAKER_STRICTNESS_KEY, 85) / 100.0f
    val asrLanguage: String
        get() = prefs.getString(ASR_LANGUAGE_KEY, "gu") ?: "gu"

    val asrDecoder: String
        get() = prefs.getString(ASR_DECODER_KEY, "ctc") ?: "ctc"
}