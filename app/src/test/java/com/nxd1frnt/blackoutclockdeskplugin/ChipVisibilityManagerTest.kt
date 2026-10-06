package com.nxd1frnt.blackoutclockdeskplugin

import org.junit.Assert.*
import org.junit.Test

class ChipVisibilityManagerTest {

    private val cacheWithOutages = OutageCache(
        intervalsToday = listOf(
            OutageInterval("10:00", "14:00", "4")
        )
    )

    private val cacheNoOutages = OutageCache(
        intervalsToday = emptyList(),
        intervalsTomorrow = emptyList()
    )

    @Test
    fun testModeAlways() {
        val infoPowerOn = CountdownInfo("СВІТЛО Є", "45:00 хв.", "14:00")
        val infoPowerOff = CountdownInfo("ВИМКНЕННЯ", "01:00 год.", "18:00")
        val infoNoOutages = CountdownInfo("СВІТЛО Є", "—", "Відключень немає")

        assertTrue(ChipVisibilityManager.computeVisibility(ChipVisibilityManager.MODE_ALWAYS, 30, cacheWithOutages, infoPowerOn))
        assertTrue(ChipVisibilityManager.computeVisibility(ChipVisibilityManager.MODE_ALWAYS, 30, cacheWithOutages, infoPowerOff))
        assertTrue(ChipVisibilityManager.computeVisibility(ChipVisibilityManager.MODE_ALWAYS, 30, cacheNoOutages, infoNoOutages))
    }

    @Test
    fun testModeHideIfNoOutagesToday() {
        val infoNoOutages = CountdownInfo("СВІТЛО Є", "—", "Відключень немає")
        assertFalse(ChipVisibilityManager.computeVisibility(ChipVisibilityManager.MODE_HIDE_IF_NO_OUTAGES_TODAY, 30, cacheNoOutages, infoNoOutages))

        val infoWithOutage = CountdownInfo("СВІТЛО Є", "01:30 год.", "10:00")
        assertTrue(ChipVisibilityManager.computeVisibility(ChipVisibilityManager.MODE_HIDE_IF_NO_OUTAGES_TODAY, 30, cacheWithOutages, infoWithOutage))
    }

    @Test
    fun testModeShowOnlyDuringOutage() {
        val infoPowerOff = CountdownInfo("ВИМКНЕННЯ", "02:00 год.", "14:00")
        // Під час активного вимкнення завжди видимий
        assertTrue(ChipVisibilityManager.computeVisibility(ChipVisibilityManager.MODE_SHOW_ONLY_DURING_OUTAGE, 0, cacheWithOutages, infoPowerOff))
        assertTrue(ChipVisibilityManager.computeVisibility(ChipVisibilityManager.MODE_SHOW_ONLY_DURING_OUTAGE, 30, cacheWithOutages, infoPowerOff))

        // Світло є, до вимкнення 2 години (120 хв), поріг 30 хв -> приховано
        val infoFarOutage = CountdownInfo("СВІТЛО Є", "02:00 год.", "14:00")
        assertFalse(ChipVisibilityManager.computeVisibility(ChipVisibilityManager.MODE_SHOW_ONLY_DURING_OUTAGE, 30, cacheWithOutages, infoFarOutage))

        // Світло є, до вимкнення 20 хв, поріг 30 хв -> показано (попередження)
        val infoNearOutage = CountdownInfo("СВІТЛО Є", "20:00 хв.", "14:00")
        assertTrue(ChipVisibilityManager.computeVisibility(ChipVisibilityManager.MODE_SHOW_ONLY_DURING_OUTAGE, 30, cacheWithOutages, infoNearOutage))

        // Світло є, до вимкнення 20 хв, але поріг 0 хв (без попередження) -> приховано
        assertFalse(ChipVisibilityManager.computeVisibility(ChipVisibilityManager.MODE_SHOW_ONLY_DURING_OUTAGE, 0, cacheWithOutages, infoNearOutage))

        // Світло є, вимкнень немає взагалі
        val infoNoOutages = CountdownInfo("СВІТЛО Є", "—", "Відключень немає")
        assertFalse(ChipVisibilityManager.computeVisibility(ChipVisibilityManager.MODE_SHOW_ONLY_DURING_OUTAGE, 30, cacheNoOutages, infoNoOutages))
    }

    @Test
    fun testErrorStatesAlwaysVisible() {
        val infoError = CountdownInfo("ДАНИХ НЕМАЄ", "—", "—")
        assertTrue(ChipVisibilityManager.computeVisibility(ChipVisibilityManager.MODE_SHOW_ONLY_DURING_OUTAGE, 30, null, infoError))
        assertTrue(ChipVisibilityManager.computeVisibility(ChipVisibilityManager.MODE_HIDE_IF_NO_OUTAGES_TODAY, 30, null, infoError))
    }

    @Test
    fun testParseRemainingMinutes() {
        assertEquals(45, ChipVisibilityManager.parseRemainingMinutes("45:00 хв."))
        assertEquals(90, ChipVisibilityManager.parseRemainingMinutes("01:30 год."))
        assertEquals(15, ChipVisibilityManager.parseRemainingMinutes("15:30 хв."))
        assertNull(ChipVisibilityManager.parseRemainingMinutes("—"))
        assertNull(ChipVisibilityManager.parseRemainingMinutes(""))
    }
}
