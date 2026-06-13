package com.tvremote

import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

/**
 * Manages MQTT connection, subscription, and publishing.
 */
class MqttManager(
    private val deviceId: String,
    private val brokerUrl: String = "tcp://broker.emqx.io:1883",
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onCommand: (JSONObject) -> Unit,
    private val onRequestScreenshot: (Int) -> Unit,
    private val onStreamStart: () -> Unit,
    private val onStreamStop: () -> Unit,
    private val onStatusChange: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "MqttManager"
        private const val QOS_CMD = 1
        private const val QOS_STREAM = 0
    }

    private var client: MqttClient? = null
    private var connecting = false

    private val topicCmd = "tvremote/$deviceId/cmd"
    private val topicScreen = "tvremote/$deviceId/screen"
    private val topicStatus = "tvremote/$deviceId/status"
    private val topicReqScreen = "tvremote/$deviceId/req_screen"
    private val topicStreamStart = "tvremote/$deviceId/stream_start"
    private val topicStreamStop = "tvremote/$deviceId/stream_stop"

    fun connect() {
        if (connecting || (client?.isConnected == true)) return
        connecting = true

        Thread {
            try {
                val clientId = "tv_${deviceId}_${System.currentTimeMillis()}"
                client = MqttClient(brokerUrl, clientId, MemoryPersistence()).apply {
                    val opts = MqttConnectOptions().apply {
                        isCleanSession = true
                        keepAliveInterval = 30
                        connectionTimeout = 10
                        // Last Will with resolution info
                        val offlineMsg = JSONObject().apply {
                            put("status", "offline")
                        }
                        setWill(topicStatus, offlineMsg.toString().toByteArray(), QOS_CMD, true)
                    }
                    setCallback(object : MqttCallback {
                        override fun connectionLost(cause: Throwable?) {
                            Log.w(TAG, "Connection lost: ${cause?.message}")
                            onStatusChange(false)
                            onStreamStop()
                            scheduleReconnect()
                        }
                        override fun messageArrived(topic: String?, message: MqttMessage?) {
                            if (topic == null || message == null) return
                            handleMessage(topic, message)
                        }
                        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                    })
                    connect(opts)
                }

                client?.subscribe(topicCmd, QOS_CMD)
                client?.subscribe(topicReqScreen, QOS_CMD)
                client?.subscribe(topicStreamStart, QOS_CMD)
                client?.subscribe(topicStreamStop, QOS_CMD)

                // Publish online status with screen resolution (retained)
                val onlineMsg = JSONObject().apply {
                    put("status", "online")
                    put("width", screenWidth)
                    put("height", screenHeight)
                }
                client?.publish(topicStatus, onlineMsg.toString().toByteArray(), QOS_CMD, true)

                Log.i(TAG, "Connected ($clientId), screen=${screenWidth}x${screenHeight}")
                connecting = false
                onStatusChange(true)
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}")
                connecting = false
                onStatusChange(false)
                scheduleReconnect()
            }
        }.start()
    }

    private fun handleMessage(topic: String, message: MqttMessage) {
        val payload = String(message.payload)

        when (topic) {
            topicCmd -> {
                try {
                    val json = JSONObject(payload)
                    onCommand(json)
                } catch (e: Exception) {
                    Log.e(TAG, "Parse cmd error", e)
                }
            }
            topicReqScreen -> {
                try {
                    val json = JSONObject(payload)
                    onRequestScreenshot(json.optInt("quality", 50))
                } catch (e: Exception) {
                    onRequestScreenshot(50)
                }
            }
            topicStreamStart -> onStreamStart()
            topicStreamStop -> onStreamStop()
        }
    }

    fun publishScreenshot(base64: String) {
        try { client?.publish(topicScreen, base64.toByteArray(), QOS_CMD, false) }
        catch (e: Exception) { Log.e(TAG, "Publish screenshot error", e) }
    }

    fun publishStreamFrame(bytes: ByteArray) {
        try { client?.publish(topicScreen, bytes, QOS_STREAM, false) }
        catch (e: Exception) { Log.e(TAG, "Publish frame error", e) }
    }

    private fun scheduleReconnect() {
        Thread {
            try { Thread.sleep(5000); connect() }
            catch (_: InterruptedException) {}
        }.start()
    }

    fun disconnect() {
        try {
            val offlineMsg = JSONObject().apply { put("status", "offline") }
            client?.publish(topicStatus, offlineMsg.toString().toByteArray(), QOS_CMD, true)
            client?.disconnect()
            client?.close()
        } catch (_: Exception) {}
        client = null
        connecting = false
        onStatusChange(false)
    }

    val isConnected: Boolean get() = client?.isConnected == true
}
