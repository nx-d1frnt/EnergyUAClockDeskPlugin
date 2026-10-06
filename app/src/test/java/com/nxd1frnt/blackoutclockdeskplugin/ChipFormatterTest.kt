package com.nxd1frnt.blackoutclockdeskplugin

import org.junit.Assert.assertEquals
import org.junit.Test

class ChipFormatterTest {

    @Test
    fun testDetailedFormat() {
        val infoPowerOn = CountdownInfo("СВІТЛО Є", "45:00 хв.", "14:00")
        val resultOn = ChipFormatter.formatWithStyle(infoPowerOn, ChipFormatter.FORMAT_DETAILED)
        assertEquals("Є | до 14:00 (45:00 хв.)", resultOn)

        val infoPowerOff = CountdownInfo("ВИМКНЕННЯ", "01:30 год.", "18:00")
        val resultOff = ChipFormatter.formatWithStyle(infoPowerOff, ChipFormatter.FORMAT_DETAILED)
        assertEquals("Нема | до 18:00 (01:30 год.)", resultOff)
    }

    @Test
    fun testTimeOnlyFormat() {
        val infoPowerOn = CountdownInfo("СВІТЛО Є", "45:00 хв.", "14:00")
        val resultOn = ChipFormatter.formatWithStyle(infoPowerOn, ChipFormatter.FORMAT_TIME_ONLY)
        assertEquals("Є до 14:00", resultOn)

        val infoPowerOff = CountdownInfo("ВИМКНЕННЯ", "01:30 год.", "18:00")
        val resultOff = ChipFormatter.formatWithStyle(infoPowerOff, ChipFormatter.FORMAT_TIME_ONLY)
        assertEquals("Нема до 18:00", resultOff)
    }

    @Test
    fun testCountdownOnlyFormat() {
        val infoPowerOn = CountdownInfo("СВІТЛО Є", "45:00 хв.", "14:00")
        val resultOn = ChipFormatter.formatWithStyle(infoPowerOn, ChipFormatter.FORMAT_COUNTDOWN_ONLY)
        assertEquals("Є (45:00 хв.)", resultOn)

        val infoPowerOff = CountdownInfo("ВИМКНЕННЯ", "01:30 год.", "18:00")
        val resultOff = ChipFormatter.formatWithStyle(infoPowerOff, ChipFormatter.FORMAT_COUNTDOWN_ONLY)
        assertEquals("Нема (01:30 год.)", resultOff)
    }

    @Test
    fun testCompactFormat() {
        val infoPowerOn = CountdownInfo("СВІТЛО Є", "45:00 хв.", "14:00")
        val resultOn = ChipFormatter.formatWithStyle(infoPowerOn, ChipFormatter.FORMAT_COMPACT)
        assertEquals("Є 14:00", resultOn)

        val infoPowerOff = CountdownInfo("ВИМКНЕННЯ", "01:30 год.", "18:00")
        val resultOff = ChipFormatter.formatWithStyle(infoPowerOff, ChipFormatter.FORMAT_COMPACT)
        assertEquals("Нема 18:00", resultOff)
    }

    @Test
    fun testCustomTemplateFormat() {
        val info = CountdownInfo("СВІТЛО Є", "45:00 хв.", "14:00")
        val template = "[{status}] -> {time} [залишилось: {remaining}]"
        val result = ChipFormatter.formatWithStyle(info, ChipFormatter.FORMAT_CUSTOM, template)
        assertEquals("[Є] -> 14:00 [залишилось: 45:00 хв.]", result)
    }

    @Test
    fun testNoOutagesSpecialCase() {
        val info = CountdownInfo("СВІТЛО Є", "—", "Відключень немає")
        val result = ChipFormatter.formatWithStyle(info, ChipFormatter.FORMAT_DETAILED)
        assertEquals("Світло є (без відключень)", result)

        val resultCustom = ChipFormatter.formatWithStyle(info, ChipFormatter.FORMAT_CUSTOM, "{status} {time}")
        assertEquals("Світло є (без відключень)", resultCustom)
    }

    @Test
    fun testNoDataSpecialCase() {
        val info = CountdownInfo("ДАНИХ НЕМАЄ", "—", "—")
        val result = ChipFormatter.formatWithStyle(info, ChipFormatter.FORMAT_DETAILED)
        assertEquals("Немає графіку", result)
    }
}
