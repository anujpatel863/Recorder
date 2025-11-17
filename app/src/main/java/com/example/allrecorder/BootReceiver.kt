package com.example.allrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed. Starting RecordingService.")
            try {
                val serviceIntent = Intent(context, RecordingService::class.java).apply {
                    // Add ACTION_START to be explicit
                    action = RecordingService.ACTION_START
                }

                // Android O (8.0) and higher require startForegroundService
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service on boot", e)
            }
        }
    }
}