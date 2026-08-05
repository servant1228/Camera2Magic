package com.nothing.camera2magic.utils

import android.os.Process
import android.util.Log
import java.lang.Process as JavaProcess
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogEntry(
    val id: Long,
    val timeMillis: Long,
    val level: String,
    val tag: String,
    val message: String,
)

internal data class ParsedLogcatLine(
    val timeMillis: Long,
    val pid: Int?,
    val tid: Int?,
    val level: String,
    val tag: String,
    val message: String,
)

/** logcat threadtime 格式：MM-dd HH:mm:ss.SSS pid tid 级别 tag : message */
private val LOGCAT_LINE_REGEX = Regex(
    """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFAS])\s+(\S+)\s*:\s?(.*)$"""
)

private val logcatTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

/**
 * 解析 logcat threadtime 格式单行；仅接受 tag=VCX 的日志，其余返回 null。
 * 纯 JVM 函数，便于单元测试。
 */
internal fun parseLogcatLine(line: String, year: Int = Calendar.getInstance().get(Calendar.YEAR)): ParsedLogcatLine? {
    if (line.isBlank()) return null
    val match = LOGCAT_LINE_REGEX.matchEntire(line) ?: return null
    val pid = match.groupValues[2].toIntOrNull()
    val tid = match.groupValues[3].toIntOrNull()
    val level = match.groupValues[4]
    val tag = match.groupValues[5]
    if (tag != "VCX") return null
    val message = match.groupValues[6]
    val parsedTime = synchronized(logcatTimeFormat) {
        logcatTimeFormat.parse(match.groupValues[1])
    } ?: return null
    val calendar = Calendar.getInstance().apply {
        timeInMillis = parsedTime.time
        set(Calendar.YEAR, year)
    }
    return ParsedLogcatLine(
        timeMillis = calendar.timeInMillis,
        pid = pid,
        tid = tid,
        level = level,
        tag = tag,
        message = message,
    )
}

object Dog {
    private const val TAG = "VCX"
    private const val MAX_ENTRIES = 1000
    private const val FLUSH_INTERVAL_MS = 100L

    private val nextId = AtomicLong(0L)
    private val buffer = ArrayDeque<LogEntry>()
    private var dirty = false
    private val bufferLock = Any()

    private val monitorLock = Any()
    private var monitorThread: Thread? = null
    private var monitorProcess: JavaProcess? = null
    private var flusherThread: Thread? = null

    @Volatile
    private var rootChecked = false
    @Volatile
    private var rootAvailable = false

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                startFlusher()
                startLogcatMonitor()
            } else {
                stopLogcatMonitor()
                stopFlusher()
            }
        }

    /**
     * 内存缓冲仅在宿主进程（enabled=true）维护；Hook 目标进程只写 logcat，
     * 由宿主的 logcat 桥接读取，避免无人消费的内存分配。
     */
    private fun append(level: String, tag: String?, message: String, timeMillis: Long = System.currentTimeMillis()) {
        if (!enabled) return
        val entry = LogEntry(
            id = nextId.getAndIncrement(),
            timeMillis = timeMillis,
            level = level,
            tag = tag ?: "",
            message = message,
        )
        synchronized(bufferLock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            dirty = true
        }
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
        // 内存条目保留完整堆栈，方便详情页查看
        append("E", tag, if (throwable != null) "$message:\n${throwable.stackTraceToString()}" else message)
    }

    fun clear() {
        synchronized(bufferLock) {
            buffer.clear()
            dirty = false
        }
        _logs.value = emptyList()
    }

    private fun flushIfDirty() {
        val snapshot = synchronized(bufferLock) {
            if (!dirty) return
            dirty = false
            buffer.toList()
        }
        _logs.value = snapshot
    }

    private fun startFlusher() {
        stopFlusher()
        flusherThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                flushIfDirty()
            }
        }.apply {
            name = "DogFlusher"
            isDaemon = true
            start()
        }
    }

    private fun stopFlusher() {
        flusherThread?.interrupt()
        flusherThread = null
    }

    private fun startLogcatMonitor() {
        stopLogcatMonitor()
        val cmd = if (hasRoot()) listOf("su", "-c", "logcat -T 1 -s VCX:*")
        else listOf("logcat", "-T", "1", "-s", "VCX:*")
        val process: JavaProcess = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (_: Exception) {
            return
        }
        val thread = Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    var line = reader.readLine()
                    while (line != null && !Thread.currentThread().isInterrupted) {
                        val parsed = parseLogcatLine(line)
                        // 跳过宿主自身 PID 的日志，避免与直接追加的条目重复
                        if (parsed != null && parsed.pid != Process.myPid()) {
                            append(parsed.level, parsed.tag, parsed.message, parsed.timeMillis)
                        }
                        line = reader.readLine()
                    }
                }
            } catch (_: Exception) {
                // logcat 进程退出 / 流关闭属正常结束
            }
        }.apply {
            name = "DogLogcatMonitor"
            isDaemon = true
        }
        synchronized(monitorLock) {
            monitorProcess = process
            monitorThread = thread
        }
        thread.start()
    }

    private fun stopLogcatMonitor() {
        val oldThread: Thread?
        val oldProcess: JavaProcess?
        synchronized(monitorLock) {
            oldThread = monitorThread
            oldProcess = monitorProcess
            monitorThread = null
            monitorProcess = null
        }
        oldProcess?.destroy()
        oldThread?.interrupt()
        try {
            oldThread?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (oldProcess?.isAlive == true) oldProcess.destroyForcibly()
    }

    private fun hasRoot(): Boolean {
        if (rootChecked) return rootAvailable
        rootAvailable = runCatching {
            val p = ProcessBuilder("su", "-c", "echo ok").redirectErrorStream(true).start()
            p.waitFor() == 0
        }.getOrDefault(false)
        rootChecked = true
        return rootAvailable
    }
}
