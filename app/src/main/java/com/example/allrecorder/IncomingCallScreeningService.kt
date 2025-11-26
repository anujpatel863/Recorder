package com.example.allrecorder

import android.telecom.Call
import android.telecom.CallScreeningService
import android.content.Intent
import android.net.Uri


class IncomingCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // 1. Get the Number (Modern Way)
        val phoneNumber = getPhoneNumber(callDetails)

        // 2. Cache it in the Receiver so it's ready when the call goes Off-Hook
        CallReceiver.cachedIncomingNumber = phoneNumber

        // 3. Allow the call to proceed (we are just monitoring, not blocking)
        val response = CallResponse.Builder().build()
        respondToCall(callDetails, response)
    }

    private fun getPhoneNumber(details: Call.Details): String? {
        return try {
            val handle: Uri? = details.handle
            handle?.schemeSpecificPart
        } catch (e: Exception) {
            null
        }
    }
}