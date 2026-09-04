package com.studytrack.app.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Minimal, dependency-free CSV reader that handles quoted fields with embedded commas. */
fun parseCsv(content: String): List<Map<String, String>> {
    val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.isEmpty()) return emptyList()
    val header = parseCsvLine(lines.first()).map { it.trim().lowercase(Locale.getDefault()) }
    return lines.drop(1).map { line ->
        val fields = parseCsvLine(line)
        header.indices.associate { i -> header[i] to (fields.getOrNull(i) ?: "") }
    }
}

private fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                current.append('"'); i++
            }
            ch == '"' -> inQuotes = !inQuotes
            ch == ',' && !inQuotes -> {
                fields.add(current.toString()); current.clear()
            }
            else -> current.append(ch)
        }
        i++
    }
    fields.add(current.toString())
    return fields
}

fun formatMinutes(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

fun formatClock(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

fun startOfDayMillis(date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Long =
    date.atStartOfDay(zone).toInstant().toEpochMilli()

fun endOfDayMillis(date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Long =
    date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

fun startOfWeekMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()

fun endOfWeekMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(7)
        .atStartOfDay(zone).toInstant().toEpochMilli()

fun daysBetween(fromMillis: Long, toMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val from = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
    val to = Instant.ofEpochMilli(toMillis).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(from, to)
}

fun formatDateShort(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))

fun formatTimeShort(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
