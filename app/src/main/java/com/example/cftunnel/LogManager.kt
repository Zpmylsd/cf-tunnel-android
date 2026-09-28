package com.example.cftunnel

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogManager(private val context: Context) {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val mutex = Mutex()
    private val maxLogLines = 500
    private val logFile: File by lazy {
        File(context.filesDir, "important_logs.txt")
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun appendLog(log: String) {
        runBlocking {
            mutex.withLock {
                val currentLogs = _logs.value.toMutableList()
                currentLogs.add(log)
                if (currentLogs.size > maxLogLines) {
                    currentLogs.removeAt(0)
                }
                _logs.value = currentLogs
            }
        }

        // Check if important log and write to file
        val lowerLog = log.lowercase(Locale.getDefault())
        if (lowerLog.contains("error") || lowerLog.contains("warn") || lowerLog.contains("err") || lowerLog.contains("fatal")) {
            saveImportantLog(log)
        }
    }

    private fun saveImportantLog(log: String) {
        try {
            val timestamp = dateFormat.format(Date())
            FileWriter(logFile, true).use { writer ->
                writer.append("[$timestamp] $log\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearLogs() {
        runBlocking {
            mutex.withLock {
                _logs.value = emptyList()
            }
        }
    }
}
