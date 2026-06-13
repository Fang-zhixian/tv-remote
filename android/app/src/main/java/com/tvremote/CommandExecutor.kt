package com.tvremote

import android.util.Log
import org.json.JSONObject

/**
 * Executes remote control commands via input keyevent / input tap.
 * Requires ADB debugging enabled on the TV (one-time setup).
 */
object CommandExecutor {
    private const val TAG = "CommandExecutor"

    private val KEY_MAP: Map<String, Int> = mapOf(
        "up" to 19, "down" to 20, "left" to 21, "right" to 22,
        "ok" to 23, "back" to 4, "home" to 3, "menu" to 82,
        "vol_up" to 24, "vol_down" to 25, "power" to 26
    )

    /**
     * Execute a command from JSON: { action: "tap"|"up"|..., data: { x, y } }
     */
    @Throws(Exception::class)
    fun execute(cmdJson: JSONObject) {
        val action = cmdJson.getString("action")
        val data = cmdJson.optJSONObject("data")

        when (action) {
            "tap" -> {
                val x = data?.optInt("x", -1) ?: -1
                val y = data?.optInt("y", -1) ?: -1
                if (x < 0 || y < 0) throw IllegalArgumentException("tap requires x, y in data")
                exec("input tap $x $y")
            }
            else -> {
                val keyCode = KEY_MAP[action]
                    ?: throw IllegalArgumentException("Unknown action: $action")
                exec("input keyevent $keyCode")
            }
        }
    }

    private fun exec(command: String) {
        Log.d(TAG, "Exec: $command")
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val err = process.errorStream.bufferedReader().readText()
            Log.e(TAG, "Command failed exit=$exitCode: $err")
            throw Exception("Command failed (exit=$exitCode): $err")
        }
    }
}
