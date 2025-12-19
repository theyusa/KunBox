package com.kunk.singbox.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import android.os.Build

class LogRepository private constructor() {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val maxLogSize = 500
    private val maxLogLineLength = 2000
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val buffer = ArrayDeque<String>(maxLogSize)
    private val logVersion = AtomicLong(0)
    private val flushRunning = AtomicBoolean(false)

    fun addLog(message: String) {
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        val formattedLog = "[$timestamp] $message"
        val finalLog = if (formattedLog.length > maxLogLineLength) {
            formattedLog.substring(0, maxLogLineLength)
        } else {
            formattedLog
        }

        synchronized(buffer) {
            if (buffer.size >= maxLogSize) {
                buffer.removeFirst()
            }
            buffer.addLast(finalLog)
            logVersion.incrementAndGet()
        }

        requestFlush()
    }

    private fun requestFlush() {
        if (!flushRunning.compareAndSet(false, true)) return

        scope.launch {
            var lastSeenVersion = logVersion.get()
            delay(200)

            while (true) {
                val snapshot = synchronized(buffer) {
                    buffer.toList()
                }
                _logs.value = snapshot

                val nowVersion = logVersion.get()
                if (nowVersion == lastSeenVersion) {
                    flushRunning.set(false)
                    if (logVersion.get() != nowVersion) {
                        if (flushRunning.compareAndSet(false, true)) {
                            lastSeenVersion = logVersion.get()
                            delay(200)
                            continue
                        }
                    }
                    break
                }

                lastSeenVersion = nowVersion
                delay(200)
            }
        }
    }

    fun clearLogs() {
        synchronized(buffer) {
            buffer.clear()
            logVersion.incrementAndGet()
        }
        _logs.value = emptyList()
    }

    fun getLogsAsText(): String {
        val exportDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val header = buildString {
            appendLine("=== SingBox 运行日志 ===")
            appendLine("导出时间: ${exportDateFormat.format(Date())}")
            appendLine("设备型号: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android 版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("========================")
            appendLine()
        }
        
        val logContent = synchronized(buffer) {
            buffer.joinToString("\n")
        }
        
        return header + logContent
    }

    companion object {
        @Volatile
        private var instance: LogRepository? = null

        fun getInstance(): LogRepository {
            return instance ?: synchronized(this) {
                instance ?: LogRepository().also { instance = it }
            }
        }
    }
}
