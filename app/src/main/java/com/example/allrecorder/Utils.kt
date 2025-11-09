package com.example.allrecorder

import java.util.concurrent.TimeUnit

// Helper function to format milliseconds into MM:SS format
fun formatDuration(millis: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return String.format("%02d:%02d", minutes, seconds)
}
