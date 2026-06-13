package com.tvremote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Captures screen via screencap command. All data flows through pipes — no temp files.
 * Two modes: on-demand (base64, high quality) and stream (raw JPEG bytes, low latency).
 */
object ScreenshotHelper {
    private const val TAG = "ScreenshotHelper"
    private const val MAX_WIDTH_ONDEMAND = 1280
    private const val MAX_WIDTH_STREAM = 640  // 480p-ish for low latency streaming

    /**
     * On-demand capture: returns base64-encoded JPEG string (higher quality).
     */
    fun captureBase64(quality: Int = 50): String? {
        val bytes = captureRaw(MAX_WIDTH_ONDEMAND, quality) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Streaming capture: returns raw JPEG bytes (small resolution, low quality).
     * No base64 encoding — send binary directly over MQTT for efficiency.
     */
    fun captureStreamBytes(quality: Int = 35): ByteArray? {
        return captureRaw(MAX_WIDTH_STREAM, quality)
    }

    private fun captureRaw(maxWidth: Int, quality: Int): ByteArray? {
        return try {
            // screencap to stdout pipe (no temp file)
            val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
            val opts = BitmapFactory.Options()
            val bitmap = BitmapFactory.decodeStream(process.inputStream, null, opts)
            process.waitFor()

            if (bitmap == null) {
                Log.e(TAG, "Failed to decode screenshot from pipe")
                return null
            }

            // Scale down if needed
            val scaled = if (bitmap.width > maxWidth) {
                val ratio = maxWidth.toFloat() / bitmap.width
                val newHeight = (bitmap.height * ratio).toInt()
                val s = Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
                bitmap.recycle()
                s
            } else {
                bitmap
            }

            // Compress to JPEG in memory
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)

            val bytes = baos.toByteArray()
            baos.close()
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()

            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed", e)
            null
        }
    }
}
