package com.nxd1frnt.blackoutclockdeskplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleDiffCalculatorTest {

    @Test
    fun testIdenticalSchedulesProduceNoDiffs() {
        val intervals = listOf(OutageInterval("08:00", "12:00", "4"))
        val diffs = ScheduleDiffCalculator.compareDay(intervals, intervals)
        assertTrue(diffs.isEmpty())
    }

    @Test
    fun testNewAndCancelledOutage() {
        val oldList = listOf(OutageInterval("08:00", "10:00", "2"))
        val newList = listOf(OutageInterval("14:00", "16:30", "2.5"))

        val diffs = ScheduleDiffCalculator.compareDay(oldList, newList)
        assertEquals(2, diffs.size)
        assertEquals("Скасовано відключення 08:00–10:00 (+2 год)", diffs[0])
        assertEquals("Нове відключення 14:00–16:30 (-2 год 30 хв)", diffs[1])
    }

    @Test
    fun testEarlierSwitchOffAndLaterSwitchOn() {
        val oldList = listOf(OutageInterval("10:00", "14:00", "4"))
        val newList = listOf(OutageInterval("09:00", "15:30", "6.5"))

        val diffs = ScheduleDiffCalculator.compareDay(oldList, newList)
        assertEquals(2, diffs.size)
        assertEquals("Раніше вимкнуть: о 09:00 замість 10:00 (-1 год)", diffs[0])
        assertEquals("Пізніше увімкнуть: о 15:30 замість 14:00 (-1 год 30 хв)", diffs[1])
    }

    @Test
    fun testEarlierSwitchOnAndLaterSwitchOff() {
        val oldList = listOf(OutageInterval("09:00", "15:00", "6"))
        val newList = listOf(OutageInterval("10:00", "14:00", "4"))

        val diffs = ScheduleDiffCalculator.compareDay(oldList, newList)
        assertEquals(2, diffs.size)
        assertEquals("Пізніше вимкнуть: о 10:00 замість 09:00 (+1 год)", diffs[0])
        assertEquals("Раніше увімкнуть: о 14:00 замість 15:00 (+1 год)", diffs[1])
    }

    @Test
    fun testCompareCachesIncludesTomorrowPrefixOnlyWhenChanged() {
        val oldCache = OutageCache(
            intervalsToday = listOf(OutageInterval("08:00", "10:00", "2")),
            intervalsTomorrow = listOf(OutageInterval("12:00", "14:00", "2"))
        )
        val newCache = OutageCache(
            intervalsToday = listOf(OutageInterval("08:00", "10:00", "2")),
            intervalsTomorrow = listOf(OutageInterval("12:00", "16:00", "4"))
        )

        val diffs = ScheduleDiffCalculator.compareCaches(oldCache, newCache)
        assertEquals(1, diffs.size)
        assertEquals("Завтра: Пізніше увімкнуть: о 16:00 замість 14:00 (-2 год)", diffs[0])
    }
}
