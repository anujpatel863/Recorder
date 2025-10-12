package com.example.allrecorder

/**
 * A data class to hold detailed information about the diarization progress.
 */
data class DiarizationProgress(
    val progressPercentage: Int,
    val statusMessage: String,
    val totalRecordings: Int,
    val processedRecordings: Int
)