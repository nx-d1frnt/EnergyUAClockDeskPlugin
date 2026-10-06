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
}
