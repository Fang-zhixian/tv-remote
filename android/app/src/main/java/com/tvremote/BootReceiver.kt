package com.tvremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives BOOT_COMPLETED broadcast to auto-start the TV remote service.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed, starting service...")

        // Check if device ID is configured
        val deviceId = TvRemoteService.getDeviceId(context)
        if (deviceId.isNullOrBlank()) {
            Log.w(TAG, "Device ID not set, skipping autostart")
            return
        }

        // Start the foreground service
        TvRemoteService.startService(context)
    }
}
