package com.tvremote

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Configuration screen for the TV remote app.
 * User sets device ID, broker URL, and starts the service.
 */
class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_NOTIFICATION = 100
    }

    private lateinit var etDeviceId: EditText
    private lateinit var etBrokerUrl: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etDeviceId = findViewById(R.id.etDeviceId)
        etBrokerUrl = findViewById(R.id.etBrokerUrl)
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        // Load saved config
        val prefs = getSharedPreferences("tvremote_prefs", MODE_PRIVATE)
        etDeviceId.setText(prefs.getString("device_id", ""))
        etBrokerUrl.setText(prefs.getString("broker_url", "tcp://broker.emqx.io:1883"))

        // Update status
        updateStatus()

        btnStart.setOnClickListener {
            val deviceId = etDeviceId.text.toString().trim()
            val brokerUrl = etBrokerUrl.text.toString().trim()

            if (deviceId.isBlank()) {
                Toast.makeText(this, "请输入设备ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save config
            TvRemoteService.saveConfig(this, deviceId, brokerUrl)

            // Request notification permission on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_NOTIFICATION
                    )
                    return@setOnClickListener
                }
            }

            startService()
        }

        btnStop.setOnClickListener {
            TvRemoteService.stopServiceInstance(this)
            Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show()
            updateStatus()
        }

        // Request ignore battery optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        // Auto-start if already configured
        val savedId = prefs.getString("device_id", null)
        if (!savedId.isNullOrBlank()) {
            startService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startService()
            } else {
                Toast.makeText(this, "需要通知权限以保持后台运行", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startService() {
        TvRemoteService.startService(this)
        Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun updateStatus() {
        val deviceId = TvRemoteService.getDeviceId(this)
        val running = isServiceRunning()
        tvStatus.text = if (running) {
            "状态：运行中（设备：$deviceId）"
        } else {
            "状态：未运行"
        }
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val services = am.getRunningServices(Integer.MAX_VALUE)
        return services.any { it.service.className == TvRemoteService::class.java.name }
    }
}
