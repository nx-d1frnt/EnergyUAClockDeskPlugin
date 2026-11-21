package com.nxd1frnt.blackoutclockdeskplugin

/**
 * Порівнює старий та новий графіки відключень і формує людиночитаний список змін.
 */
object ScheduleDiffCalculator {

    private data class MinuteRange(val start: Int, val end: Int)
    private data class DiffBlock(val start: Int, val end: Int, val wasOff: Boolean, val isOff: Boolean)

    fun compareCaches(oldCache: OutageCache, newCache: OutageCache): List<String> {
        val result = mutableListOf<String>()
        result.addAll(compareDay(oldCache.intervalsToday, newCache.intervalsToday))

        val oldTomorrow = oldCache.intervalsTomorrow.orEmpty()
        val newTomorrow = newCache.intervalsTomorrow.orEmpty()
        if (oldTomorrow.isNotEmpty() || newTomorrow.isNotEmpty()) {
            // Не показуємо як "зміну" першу публікацію завтрашнього графіка, якщо до того його не було
            if (oldTomorrow.isNotEmpty()) {
                compareDay(oldTomorrow, newTomorrow).forEach { diff ->
                    result.add("Завтра: $diff")
                }
            }
        }
        return result
    }

    fun compareDay(oldIntervals: List<OutageInterval>, newIntervals: List<OutageInterval>): List<String> {
        val oldRanges = toNormalizedRanges(oldIntervals)
        val newRanges = toNormalizedRanges(newIntervals)
        if (oldRanges == newRanges) return emptyList()

        val points = sortedSetOf(0, 1440)
        oldRanges.forEach { points.add(it.start); points.add(it.end) }
        newRanges.forEach { points.add(it.start); points.add(it.end) }

        val sortedPoints = points.toList()
        val blocks = mutableListOf<DiffBlock>()

        for (i in 0 until sortedPoints.size - 1) {
            val a = sortedPoints[i]
            val b = sortedPoints[i + 1]
            if (b <= a) continue

            val wasOff = isCovered(oldRanges, a, b)
            val isOff = isCovered(newRanges, a, b)
            if (wasOff == isOff) continue

            val prev = blocks.lastOrNull()
            if (prev != null && prev.end == a && prev.wasOff == wasOff && prev.isOff == isOff) {
                blocks[blocks.lastIndex] = prev.copy(end = b)
            } else {
                blocks.add(DiffBlock(a, b, wasOff, isOff))
            }
        }

        return blocks.map { block -> describeBlock(block, oldRanges, newRanges) }
    }

    private fun describeBlock(
        block: DiffBlock,
        oldRanges: List<MinuteRange>,
        newRanges: List<MinuteRange>
    ): String {
        val start = OutageScheduler.formatMinutes(block.start)
        val end = OutageScheduler.formatMinutes(block.end)
        val dur = formatDiffDuration(block.end - block.start)

        return if (!block.wasOff && block.isOff) {
            val hadOutageAfter = oldRanges.any { it.start == block.end }
            val hadOutageBefore = oldRanges.any { it.end == block.start }
            when {
                hadOutageAfter && !hadOutageBefore -> "Раніше вимкнуть: о $start замість $end (-$dur)"
                hadOutageBefore && !hadOutageAfter -> "Пізніше увімкнуть: о $end замість $start (-$dur)"
                else -> "Нове відключення $start–$end (-$dur)"
            }
        } else {
            val hasOutageBefore = newRanges.any { it.end == block.start }
            val hasOutageAfter = newRanges.any { it.start == block.end }
            when {
                hasOutageBefore && !hasOutageAfter -> "Раніше увімкнуть: о $start замість $end (+$dur)"
                hasOutageAfter && !hasOutageBefore -> "Пізніше вимкнуть: о $end замість $start (+$dur)"
                else -> "Скасовано відключення $start–$end (+$dur)"
            }
        }
    }

    private fun formatDiffDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "$h год $m хв"
            h > 0 -> "$h год"
            else -> "$m хв"
        }
    }

    private fun isCovered(ranges: List<MinuteRange>, a: Int, b: Int): Boolean {
        return ranges.any { it.start <= a && it.end >= b }
    }

    private fun toNormalizedRanges(intervals: List<OutageInterval>): List<MinuteRange> {
        val raw = intervals.mapNotNull { interval ->
            val start = parseMinutes(interval.start) ?: return@mapNotNull null
            val end = parseMinutes(interval.end) ?: return@mapNotNull null
            if (end > start) MinuteRange(start, end) else null
        }.sortedBy { it.start }

        if (raw.isEmpty()) return emptyList()
        val merged = mutableListOf<MinuteRange>()
        for (range in raw) {
            val last = merged.lastOrNull()
            if (last != null && range.start <= last.end) {
                merged[merged.lastIndex] = last.copy(end = maxOf(last.end, range.end))
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    private fun parseMinutes(raw: String): Int? {
        val trimmed = raw.trim()
        if (trimmed == "24:00" || trimmed == "24:00:00") return 1440
        val parts = trimmed.split(":")
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..24 || m !in 0..59) return null
        return (h * 60 + m).coerceIn(0, 1440)
    }
}
