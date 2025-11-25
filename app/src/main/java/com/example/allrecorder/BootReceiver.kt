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
            Log.i(TAG, "Boot completed.")

            // Ensure Settings are loaded
            SettingsManager.init(context)

            // [NEW] Check if the user actually wants to record on boot
            if (!SettingsManager.autoRecordOnBoot) {
                Log.i(TAG, "Auto-record on boot is DISABLED. Skipping.")
                return
            }

            Log.i(TAG, "Auto-record on boot is ENABLED. Starting RecordingService.")
            try {
                val serviceIntent = Intent(context, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_START
                }

                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service on boot", e)
            }
        }
    }
}