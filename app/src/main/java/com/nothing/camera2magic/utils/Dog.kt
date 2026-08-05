package com.nothing.camera2magic.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogEntry(
    val id: Long,
    val level: String,
    val tag: String,
    val message: String,
)

object Dog {
    private const val TAG = "VCX"
    private const val MAX_ENTRIES = 1000
    private var nextId = 0L
    private var monitorThread: Thread? = null

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startLogcatMonitor() else stopLogcatMonitor()
        }

    private fun append(level: String, tag: String?, msg: String) {
        val entry = LogEntry(id = nextId++, level = level, tag = tag ?: "", message = msg)
        _logs.value = (_logs.value + entry).takeLast(MAX_ENTRIES)
    }

    fun i(tag: String? = null, message: String, enabled: Boolean = this.enabled) {
        if (!enabled) return
        Log.i(TAG, "[${tag ?: ""}] $message")
        append("I", tag, message)
    }

    fun w(tag: String? = null, message: String, enabled: Boolean = this.enabled) {
        if (!enabled) return
        Log.w(TAG, "[${tag ?: ""}] $message")
        append("W", tag, message)
    }

    fun e(tag: String? = null, message: String, throwable: Throwable? = null, enabled: Boolean = this.enabled) {
        if (!enabled) return
        val msg = if (throwable != null) "$message: ${throwable.message}" else message
        Log.e(TAG, "[${tag ?: ""}] $msg", throwable)
        append("E", tag, msg)
    }

    fun clear() { _logs.value = emptyList() }

    private fun startLogcatMonitor() {
        stopLogcatMonitor()
        monitorThread = Thread {
            try {
                // Clear buffer first
                Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
                Thread.sleep(200)
                val cmd = if (hasRoot()) arrayOf("su", "-c", "logcat -s VCX:*")
                else arrayOf("logcat", "-s", "VCX:*")
                val p = Runtime.getRuntime().exec(cmd)
                p.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null && !Thread.currentThread().isInterrupted) {
                        val level = when { line.contains(" E ") -> "E"; line.contains(" W ") -> "W"; else -> "I" }
                        append(level, "", line)
                        line = reader.readLine()
                    }
                }
                p.destroy()
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    private fun stopLogcatMonitor() {
        monitorThread?.interrupt()
        monitorThread = null
    }

    private fun hasRoot() = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo ok"))
        p.waitFor() == 0
    }.getOrDefault(false)
}
