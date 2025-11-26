package com.example.allrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class CallReceiver : BroadcastReceiver() {

    companion object {
        var cachedIncomingNumber: String? = null
        var cachedOutgoingNumber: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        // --- 1. Handle Outgoing Call Detection ---
        if (action == Intent.ACTION_NEW_OUTGOING_CALL) {
            // This intent is still the standard for non-dialer apps to catch outgoing numbers
            val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
            cachedOutgoingNumber = number
            return
        }

        // --- 2. Handle Phone State Changes ---
        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

            // Try to grab deprecated extra as a quick "best effort"
            val deprecatedNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (!deprecatedNumber.isNullOrEmpty()) {
                cachedIncomingNumber = deprecatedNumber
            }

            when (state) {
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    Log.i("CallReceiver", "Call Offhook. Attempting to start recorder...")

                    // Determine if this is incoming or outgoing based on cache
                    val number = cachedOutgoingNumber ?: cachedIncomingNumber

                    if (!number.isNullOrEmpty()) {
                        startRecordingService(context, number)
                    } else {
                        // Fallback: If cache is empty, query Call Log asynchronously
                        CoroutineScope(Dispatchers.IO).launch {
                            // Slight delay to allow CallLog to update
                            delay(1500)
                            val fallbackNumber = getLatestCallNumber(context)
                            startRecordingService(context, fallbackNumber)
                        }
                    }
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // Clear caches and stop
                    cachedIncomingNumber = null
                    cachedOutgoingNumber = null
                    stopRecordingService(context)
                }
            }
        }
    }

    private fun getLatestCallNumber(context: Context): String? {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return null

        try {
            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE)
            val sortOrder = "${CallLog.Calls.DATE} DESC"

            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                    return number
                }
            }
        } catch (e: Exception) {
            Log.e("CallReceiver", "Call Log Query Failed", e)
        }
        return null
    }

    private fun startRecordingService(context: Context, number: String?) {
        if (RecordingService.isRecording) return

        val safeNumber = number ?: "Unknown"
        val serviceIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra("IS_PHONE_CALL", true)
            putExtra("CALLER_NUMBER", safeNumber)
        }

        context.startForegroundService(serviceIntent)
    }

    private fun stopRecordingService(context: Context) {
        val serviceIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(serviceIntent)
    }
}