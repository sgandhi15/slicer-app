package com.example.slicer.shared

import kotlin.math.roundToInt
import java.util.Locale

internal fun formatSavedPercentage(inputBytes: Long, outputBytes: Long): String? {
    if (inputBytes <= 0L || outputBytes < 0L || outputBytes > inputBytes) {
        return null
    }

    val saved = inputBytes - outputBytes
    if (saved <= 0L) {
        return "No size reduction"
    }

    val percent = (saved.toDouble() / inputBytes.toDouble()) * 100.0
    val precision = if (percent < 10.0) 1 else 0
    return "saved ${if (precision == 0) percent.roundToInt() else "%.1f".format(Locale.US, percent)}%"
}

internal fun formatByteCount(bytes: Long): String {
    if (bytes < 0L) return "unknown"
    if (bytes == 0L) return "0 B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0

    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }

    val precision = if (unitIndex == 0) 0 else if (value < 10) 1 else 0
    return if (precision == 0) {
        "${value.roundToInt()} ${units[unitIndex]}"
    } else {
        "%.1f ${units[unitIndex]}".format(Locale.US, value)
    }
}
