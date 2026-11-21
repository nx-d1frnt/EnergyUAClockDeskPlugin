package com.nxd1frnt.blackoutclockdeskplugin

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class OutageSchedulerTest {

    private val gson = Gson()

    @Test
    fun testOutageCacheSerializationWithDate() {
        val today = LocalDate.now().toString()
        val cache = OutageCache(
            timestamp = 1700000000L,
            cacheDate = today,
            intervalsToday = listOf(
                OutageInterval("10:00", "14:00", "4"),
                OutageInterval("18:00", "22:00", "4")
            ),
            intervalsTomorrow = listOf(
                OutageInterval("06:00", "10:00", "4")
            )
        )

        val json = gson.toJson(cache)
        val deserialized = gson.fromJson(json, OutageCache::class.java)

        assertNotNull(deserialized)
        assertEquals(today, deserialized.cacheDate)
        assertEquals(2, deserialized.intervalsToday.size)
        assertEquals(1, deserialized.intervalsTomorrow?.size)
        assertEquals("10:00", deserialized.intervalsToday[0].start)
    }

    @Test
    fun testLegacyCacheJsonWithoutDateLoadsGracefully() {
        // Симуляція старого JSON кешу без поля cacheDate
        val legacyJson = """
            {
                "timestamp": 1700000000,
                "intervalsToday": [
                    {"start": "08:00", "end": "12:00", "duration": "4"}
                ]
            }
        """.trimIndent()

        val deserialized = gson.fromJson(legacyJson, OutageCache::class.java)
        assertNotNull(deserialized)
        assertEquals(1, deserialized.intervalsToday.size)
        assertEquals("08:00", deserialized.intervalsToday[0].start)
    }

    @Test
    fun testIntervalNormalizationAndParsing() {
        val rawList = listOf(
            OutageInterval("8:00", "12:00", "4"),
            OutageInterval("20:00", "24:00", "4")
        )

        // Використовуємо ContextWrapper(null) або перевірку парсингу
        val context = object : android.content.ContextWrapper(null) {}
        val scheduler = OutageScheduler(context)

        val parsed = scheduler.parseIntervals(rawList)
        assertEquals(2, parsed.size)

        // 8:00 має бути спарсено як 08:00
        assertEquals(LocalTime.of(8, 0), parsed[0].first)
        assertEquals(LocalTime.of(12, 0), parsed[0].second)

        // 24:00 має бути спарсено як 23:59
        assertEquals(LocalTime.of(20, 0), parsed[1].first)
        assertEquals(LocalTime.of(23, 59), parsed[1].second)
    }

    @Test
    fun testCountdownInfoWhenNoOutages() {
        val context = object : android.content.ContextWrapper(null) {}
        val scheduler = OutageScheduler(context)

        val emptyCache = OutageCache(
            intervalsToday = emptyList(),
            intervalsTomorrow = emptyList()
        )

        val info = scheduler.getCountdownInfo(emptyCache)
        assertEquals("СВІТЛО Є", info.status)
        assertEquals("—", info.timeToEvent)
        assertEquals("Відключень немає", info.nextEventTime)
    }

    @Test
    fun testCountdownInfoWhenCacheIsNull() {
        val context = object : android.content.ContextWrapper(null) {}
        val scheduler = OutageScheduler(context)

        val info = scheduler.getCountdownInfo(null)
        assertEquals("ДАНИХ НЕМАЄ", info.status)
        assertEquals("—", info.timeToEvent)
    }

    @Test
    fun testParseHtmlScheduleWithNewJsonPayload() {
        val html = """
            <html><body>
            <script id="schedulePayload" type="application/json">{"today":[{"from":30,"to":90,"status":"red"},{"from":480,"to":600,"status":"red"},{"from":810,"to":990,"status":"red"},{"from":1170,"to":1320,"status":"red"}],"tomorrow":[],"tomorrowAvailable":false}</script>
            </body></html>
        """.trimIndent()

        val context = object : android.content.ContextWrapper(null) {}
        val scheduler = OutageScheduler(context)

        val result = scheduler.parseHtmlSchedule(html)
        assertNotNull(result)
        assertEquals(4, result!!.intervalsToday.size)
        assertEquals(OutageInterval("00:30", "01:30", "1"), result.intervalsToday[0])
        assertEquals(OutageInterval("08:00", "10:00", "2"), result.intervalsToday[1])
        assertEquals(OutageInterval("13:30", "16:30", "3"), result.intervalsToday[2])
        assertEquals(OutageInterval("19:30", "22:00", "2.5"), result.intervalsToday[3])
        assertTrue(result.intervalsTomorrow!!.isEmpty())
    }

    @Test
    fun testParseHtmlScheduleMergesAdjacentRedAndIgnoresNonRed() {
        val html = """
            <html><body>
            <script id="schedulePayload" type="application/json">{
                "today": [
                    {"from": 0, "to": 60, "status": "green"},
                    {"from": 60, "to": 90, "status": "red"},
                    {"from": 90, "to": 120, "status": "red"},
                    {"from": 120, "to": 180, "status": "yellow"},
                    {"from": 1395, "to": 1440, "status": "red"}
                ],
                "tomorrow": [
                    {"from": 600, "to": 690, "status": "red"}
                ]
            }</script>
            </body></html>
        """.trimIndent()

        val context = object : android.content.ContextWrapper(null) {}
        val scheduler = OutageScheduler(context)

        val result = scheduler.parseHtmlSchedule(html)
        assertNotNull(result)
        assertEquals(2, result!!.intervalsToday.size)
        assertEquals(OutageInterval("01:00", "02:00", "1"), result.intervalsToday[0])
        assertEquals(OutageInterval("23:15", "24:00", "45 хв"), result.intervalsToday[1])
        assertEquals(1, result.intervalsTomorrow!!.size)
        assertEquals(OutageInterval("10:00", "11:30", "1.5"), result.intervalsTomorrow!![0])
    }

    @Test
    fun testParseHtmlScheduleEmptyTodayReturnsEmptyNotNull() {
        val html = """
            <html><body>
            <script id="schedulePayload" type="application/json">{"today":[],"tomorrow":[]}</script>
            </body></html>
        """.trimIndent()

        val context = object : android.content.ContextWrapper(null) {}
        val scheduler = OutageScheduler(context)

        val result = scheduler.parseHtmlSchedule(html)
        assertNotNull(result)
        assertTrue(result!!.intervalsToday.isEmpty())
        assertTrue(result.intervalsTomorrow!!.isEmpty())
    }

    @Test
    fun testParseHtmlScheduleReturnsNullOnUnknownHtml() {
        val html = "<html><body><div>Error 503</div></body></html>"
        val context = object : android.content.ContextWrapper(null) {}
        val scheduler = OutageScheduler(context)

        assertNull(scheduler.parseHtmlSchedule(html))
    }

    @Test
    fun testParseHtmlScheduleLegacyFallback() {
        val html = """
            <html><body>
            <div class="periods_items"><span><b>08:00</b><b>12:00</b><b>4 год.</b></span></div>
            </body></html>
        """.trimIndent()

        val context = object : android.content.ContextWrapper(null) {}
        val scheduler = OutageScheduler(context)

        val result = scheduler.parseHtmlSchedule(html)
        assertNotNull(result)
        assertEquals(1, result!!.intervalsToday.size)
        assertEquals(OutageInterval("08:00", "12:00", "4"), result.intervalsToday[0])
    }
}
