package com.example.allrecorder

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "recordings"
)
data class Recording(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    var filePath: String,
    val startTime: Long, // Unix timestamp in milliseconds
    val duration: Long, // Duration in milliseconds

    // This is the new column
    var processingStatus: Int = 0, // 0 = Not Started, 1 = Processing, 2 = Completed, -1 = Failed

    var transcript: String? = null, // Raw transcript
    var speakerLabels: String? = null ,
    var embedding: List<Float>? = null
) {
    // This makes the code easier to read
    companion object {
        const val STATUS_NOT_STARTED = 0
        const val STATUS_PROCESSING = 1
        const val STATUS_COMPLETED = 2
        const val STATUS_FAILED = -1
    }
}