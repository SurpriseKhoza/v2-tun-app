package io.surprise.vpn.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${value.toLong()} ${units[index]}" else String.format(Locale.US, "%.1f %s", value, units[index])
}

fun formatDuration(startedAt: Long?, now: Long = System.currentTimeMillis()): String {
    if (startedAt == null) return "00:00:00"
    val total = abs(now - startedAt) / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

fun formatLatency(ms: Int?): String = if (ms == null || ms <= 0) "—" else "$ms ms"

fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(timestamp))

fun shortEndpoint(server: String, port: Int): String {
    val host = if (server.length > 28) server.take(25) + "…" else server
    return "$host:$port"
}
