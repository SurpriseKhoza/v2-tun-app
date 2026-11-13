package io.surprise.vpn.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogSource { APP, CORE }

data class LogEntry(
    val timestamp: Long,
    val level: String,
    val source: LogSource,
    val message: String
) {
    val time: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))
}

object TunnelLogs {
    private const val MAX_ENTRIES = 700
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun app(message: String, level: String = "INFO") {
        append(LogEntry(System.currentTimeMillis(), level, LogSource.APP, message))
    }

    fun core(message: String, level: String = "CORE") {
        append(LogEntry(System.currentTimeMillis(), level, LogSource.CORE, message))
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private fun append(entry: LogEntry) {
        _entries.update { current ->
            (current + entry).takeLast(MAX_ENTRIES)
        }
    }
}
