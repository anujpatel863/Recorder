package com.example.allrecorder

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CallManager: A Unified Singleton to manage Call State across the Service and Receiver.
 * This ensures we don't lose the phone number between the "Ringing" and "Offhook" states.
 */
object CallManager {
    var cachedIncomingNumber: String? = null
    var cachedOutgoingNumber: String? = null
    var isCallActive: Boolean = false
}

/**
 * AppCallScreeningService: The modern Android way (API 24+) to detect incoming calls
 * and get the phone number reliably without needing dangerous permissions like READ_CALL_LOG
 * (if the app is set as the Default Caller ID app).
 */
class AppCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // 1. Extract the number immediately when the phone starts ringing
        val phoneNumber = getPhoneNumber(callDetails)

        Log.d("CallManager", "Screening call from: $phoneNumber")

        // 2. Cache it in our Singleton so the Receiver can access it later
        CallManager.cachedIncomingNumber = phoneNumber

        // 3. IMPORTANT: Allow the call to proceed. We are not blocking it.
        val response = CallResponse.Builder().build()
        respondToCall(callDetails, response)
    }

    private fun getPhoneNumber(details: Call.Details): String? {
        return try {
            val handle: Uri? = details.handle
            // The handle scheme is usually "tel:+123456789"
            handle?.schemeSpecificPart
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * CallReceiver: The Legacy BroadcastReceiver.
 * It is still REQUIRED to detect:
 * 1. When the user actually picks up the phone (OFFHOOK).
 * 2. When the call ends (IDLE).
 * 3. When an outgoing call is made.
 */
class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // --- Case 1: Outgoing Call ---
        if (action == Intent.ACTION_NEW_OUTGOING_CALL) {
            val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
            CallManager.cachedOutgoingNumber = number
            Log.d("CallManager", "Outgoing call detected: $number")
            return
        }

        // --- Case 2: Phone State Changed (Ringing, Offhook, Idle) ---
        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

            when (state) {
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // User picked up (or outgoing call started connecting)
                    Log.i("CallManager", "Call Offhook. Starting Recorder...")
                    CallManager.isCallActive = true

                    // Determine number: Check Outgoing first, then Incoming
                    val number = CallManager.cachedOutgoingNumber ?: CallManager.cachedIncomingNumber

                    if (!number.isNullOrEmpty()) {
                        startRecordingService(context, number)
                    } else {
                        // FALLBACK: If we are NOT the default app, we might not have the number yet.
                        // Try to query the Call Log after a short delay.
                        Log.w("CallManager", "Number not cached. Attempting fallback lookup...")
                        CoroutineScope(Dispatchers.IO).launch {
                            delay(2000) // Wait for system to write to Call Log
                            val fallbackNumber = getLatestCallNumber(context)
                            startRecordingService(context, fallbackNumber)
                        }
                    }
                }

                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // Call ended
                    Log.i("CallManager", "Call Idle. Stopping Recorder...")
                    CallManager.isCallActive = false
                    CallManager.cachedIncomingNumber = null
                    CallManager.cachedOutgoingNumber = null
                    stopRecordingService(context)
                }
            }
        }
    }

    private fun startRecordingService(context: Context, number: String?) {
        // Prevent double starts
        if (RecordingService.isRecording) return

        val safeNumber = number ?: "Unknown"
        val serviceIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra("IS_PHONE_CALL", true)
            putExtra("CALLER_NUMBER", safeNumber)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun stopRecordingService(context: Context) {
        val serviceIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(serviceIntent)
    }

    // Fallback method for when the app is NOT the default Call Screening App
    private fun getLatestCallNumber(context: Context): String? {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return null

        try {
            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE)
            val sortOrder = "${CallLog.Calls.DATE} DESC"

            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                }
            }
        } catch (e: Exception) {
            Log.e("CallManager", "Call Log Query Failed", e)
        }
        return null
    }
}