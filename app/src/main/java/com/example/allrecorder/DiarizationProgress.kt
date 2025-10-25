package com.example.allrecorder

/**
 * A data class to hold detailed information about the processing progress.
 * This is used by the ProcessingWorker to report its status.
 */
data class DiarizationProgress(
    val progressPercentage: Int,
    val statusMessage: String,
    val totalRecordings: Int,
    val processedRecordings: Int
)