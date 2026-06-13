package com.tvremote

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class TvRemoteService : Service() {
    companion object {
        private const val TAG = "TvRemoteService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tvremote_service"
        private const val PREFS_NAME = "tvremote_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_BROKER_URL = "broker_url"
        private const val STREAM_QUALITY = 35
        private const val STREAM_FRAME_DELAY_MS = 150L

        fun getDeviceId(context: Context): String? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEVICE_ID, null)

        fun saveConfig(context: Context, deviceId: String, brokerUrl: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_DEVICE_ID, deviceId).putString(KEY_BROKER_URL, brokerUrl).apply()
        }

        fun startService(context: Context) {
            val intent = Intent(context, TvRemoteService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stopServiceInstance(context: Context) {
            context.stopService(Intent(context, TvRemoteService::class.java))
        }
    }

    private var mqttManager: MqttManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var streaming = AtomicBoolean(false)
    private var streamThread: Thread? = null
    private var screenW = 1920
    private var screenH = 1080

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")

        // Get actual screen resolution
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenW = bounds.width()
            screenH = bounds.height()
        } else {
            wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
        Log.i(TAG, "Screen resolution: ${screenW}x${screenH}")

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service onStartCommand")
        startForeground(NOTIFICATION_ID, buildNotification("连接中..."))

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)
        val brokerUrl = prefs.getString(KEY_BROKER_URL, "tcp://broker.emqx.io:1883")

        if (deviceId.isNullOrBlank()) {
            Log.w(TAG, "Device ID not configured")
            stopSelf()
            return START_NOT_STICKY
        }

        // WakeLock
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVRemote:WakeLock")
        wakeLock?.acquire(10 * 60 * 1000L)

        mqttManager = MqttManager(
            deviceId = deviceId,
            brokerUrl = brokerUrl ?: "tcp://broker.emqx.io:1883",
            screenWidth = screenW,
            screenHeight = screenH,
            onCommand = { json -> handleCommand(json) },
            onRequestScreenshot = { q -> handleScreenshot(q) },
            onStreamStart = { startStreaming() },
            onStreamStop = { stopStreaming() },
            onStatusChange = { connected ->
                val text = if (connected) {
                    if (streaming.get()) "已连接 · 推流中" else "已连接"
                } else {
                    stopStreaming()
                    "断开连接，重连中..."
                }
                updateNotification(text)
            }
        )
        mqttManager?.connect()

        return START_STICKY
    }

    private fun handleCommand(json: JSONObject) {
        try {
            CommandExecutor.execute(json)
        } catch (e: Exception) {
            Log.e(TAG, "Command error: ${e.message}")
        }
    }

    private fun handleScreenshot(quality: Int) {
        Thread {
            val b64 = ScreenshotHelper.captureBase64(quality)
            if (b64 != null) mqttManager?.publishScreenshot(b64)
        }.start()
    }

    // ── Streaming ──────────────────────────────────────────────

    private fun startStreaming() {
        if (streaming.getAndSet(true)) return
        Log.i(TAG, "Starting stream...")
        updateNotification("已连接 · 推流中")

        streamThread = Thread {
            var frames = 0
            val t0 = System.currentTimeMillis()
            while (streaming.get() && mqttManager?.isConnected == true) {
                val t1 = System.currentTimeMillis()
                val bytes = ScreenshotHelper.captureStreamBytes(STREAM_QUALITY)
                if (bytes != null) {
                    mqttManager?.publishStreamFrame(bytes)
                    frames++
                }
                val dt = System.currentTimeMillis() - t1
                if (STREAM_FRAME_DELAY_MS - dt > 10) {
                    try { Thread.sleep(STREAM_FRAME_DELAY_MS - dt) }
                    catch (_: InterruptedException) { break }
                }
            }
            val dur = (System.currentTimeMillis() - t0) / 1000f
            if (dur > 0) Log.i(TAG, "Stream ended. $frames frames, ${"%.1f".format(frames/dur)} fps")
            streaming.set(false)
        }.apply { name = "screen-stream"; isDaemon = true; start() }
    }

    private fun stopStreaming() {
        if (!streaming.getAndSet(false)) return
        streamThread?.interrupt()
        streamThread = null
    }

    // ── Lifecycle ──────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopStreaming()
        mqttManager?.disconnect()
        mqttManager = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "Task removed, restarting...")
        startService(Intent(this, TvRemoteService::class.java))
    }

    // ── Notification ───────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TV远程控制", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "保持远程控制连接"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TV Remote")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_remote)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
