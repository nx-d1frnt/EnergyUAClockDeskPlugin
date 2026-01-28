package com.nxd1frnt.blackoutclockdeskplugin

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import java.io.File
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.abs

data class OutageInterval(val start: String, val end: String, val duration: String)
data class OutageCache(val timestamp: Long = System.currentTimeMillis(), val intervals: List<OutageInterval>)
data class CountdownInfo(
    val status: String,
    val timeToEvent: String,
    val nextEventTime: String
)

class OutageScheduler(private val context: Context) {

    private val TAG = "OutageScheduler"
    private val CACHE_FILE_NAME = "blackout_schedule_cache.json"
    private val CACHE_EXPIRATION_MS = 20 * 60 * 1000L
    companion object {
        // Ключі для SharedPreferences
        const val PREFS_NAME = "blackout_plugin_prefs"
        const val KEY_URL = "target_url"
        const val DEFAULT_URL = "https://energy-ua.info/cherga/5-2"
    }

     private val currentUrl: String
        get() {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        }

    private val gson = Gson()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // --- Логіка Кешування та Оновлення ---

    suspend fun getCachedSchedule(): OutageCache? = withContext(Dispatchers.IO) {
        loadCache()
    }

    suspend fun fetchAndCacheIfNeeded(): OutageCache? {
        val cachedData = loadCache()

        val isCacheExpired = cachedData == null || (System.currentTimeMillis() - cachedData.timestamp > CACHE_EXPIRATION_MS)

        if (isCacheExpired) {
            val intervals = fetchScheduleFromWeb()
            if (intervals.isNotEmpty()) {
                val newCache = OutageCache(intervals = intervals)
                saveCache(newCache)
                Log.i(TAG, "Графік оновлено та кешовано. ${intervals.size} інтервалів.")
                return newCache
            } else {
                Log.w(TAG, "Помилка оновлення. Використовуємо старий кеш або null.")
                return cachedData
            }
        }

        return cachedData
    }

    suspend fun fetchScheduleFromWeb(): List<OutageInterval> {
        return try {
            Log.d(TAG, "Починаємо завантаження через WebView...")
            val html = withTimeout(25000L) {
                WebViewFetcher(context).fetchHtml(currentUrl)
            }

            val doc = Jsoup.parse(html)
            val container = doc.selectFirst("div.periods_items")

            val intervals = mutableListOf<OutageInterval>()

            if (container == null) {
                Log.e(TAG, "ПОМИЛКА: Не знайдено контейнер div.periods_items в HTML!")
            }

            container?.select("span")?.forEach { span ->
                val bTags = span.select("b")
                if (bTags.size >= 3) {
                    val start = bTags[0].text().trim()
                    val end = bTags[1].text().trim()
                    val duration = bTags[2].text().replace("год.", "").trim()

                    intervals.add(OutageInterval(start, end, duration))
                    // ЛОГУВАННЯ КОЖНОГО ІНТЕРВАЛУ
                    Log.i(TAG, "Знайдено інтервал: $start - $end (тривалість $duration)")
                }
            }

            Log.d(TAG, "Всього інтервалів: ${intervals.size}")
            intervals
        } catch (e: Exception) {
            Log.e(TAG, "Помилка (Timeout або WebView): ${e.message}")
            emptyList()
        }
    }

    private fun loadCache(): OutageCache? {
        val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
        if (!cacheFile.exists()) return null

        return try {
            val json = cacheFile.readText()
            gson.fromJson(json, OutageCache::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Помилка читання кешу: ${e.message}")
            cacheFile.delete()
            null
        }
    }

    private fun saveCache(cache: OutageCache) {
        val json = gson.toJson(cache)
        File(context.cacheDir, CACHE_FILE_NAME).writeText(json)
    }

    fun getCountdownInfo(cache: OutageCache?): CountdownInfo {
        if (cache == null || cache.intervals.isEmpty()) {
            return CountdownInfo("ДАНИХ НЕМАЄ", "00:00 хв", "—")
        }

        val now = LocalTime.now()

        // 1. Обробка інтервалів, включаючи перехід через північ, для визначення стану
        val todayIntervals = cache.intervals.mapNotNull { interval ->
            try {
                val start = LocalTime.parse(interval.start, timeFormatter)
                val end = LocalTime.parse(interval.end, timeFormatter)
                if (end.isBefore(start)) listOf(start to LocalTime.MAX, LocalTime.MIN to end) else listOf(start to end)
            } catch (e: Exception) { null }
        }.flatten()

        var isCurrentlyOutage = todayIntervals.any { (start, end) ->
            if (end == LocalTime.MIN) false else !now.isBefore(start) && (end == LocalTime.MAX || now.isBefore(end))
        }

        // 2. Пошук найближчого переходу
        var nextEventTime: LocalTime? = null
        var nextIsOutage = false

        // Якщо зараз ВИМКНЕННЯ, шукаємо найближче УВІМКНЕННЯ (кінець інтервалу)
        if (isCurrentlyOutage) {
            nextEventTime = todayIntervals
                .filter { it.first.isBefore(now) || it.first.equals(now) }
                .map { (_, end) -> if (end == LocalTime.MAX) LocalTime.of(23, 59, 59) else end }
                .filter { it.isAfter(now) }
                .minOrNull()
            nextIsOutage = false
        }

        // Якщо ще не знайшли, або зараз СВІТЛО Є, шукаємо найближче ВИМКНЕННЯ (початок інтервалу)
        if (nextEventTime == null) {
            nextEventTime = todayIntervals
                .map { (start, _) -> start }
                .filter { it.isAfter(now) }
                .minOrNull()
            nextIsOutage = true
        }

        // Якщо до кінця доби нічого, беремо перше ВИМКНЕННЯ завтра
        if (nextEventTime == null && todayIntervals.isNotEmpty()) {
            nextEventTime = cache.intervals
                .mapNotNull {
                    try { LocalTime.parse(it.start, timeFormatter) } catch (e: Exception) { null }
                }
                .minOrNull()

            isCurrentlyOutage = false
            nextIsOutage = true
        }


        // 3. Формування результату
        if (nextEventTime == null) {
            return CountdownInfo("СВІТЛО Є", "00:00", "Графік невідомий")
        }

        val totalSeconds = now.until(nextEventTime, ChronoUnit.SECONDS)
        val secondsTillEvent = if (totalSeconds < 0) totalSeconds + 24 * 60 * 60 else totalSeconds

        val hours = secondsTillEvent / 3600
        val minutes = (secondsTillEvent % 3600) / 60
        val seconds = secondsTillEvent % 60 // <-- ДОДАНО розрахунок секунд

        val timeToEventString = if (hours > 0) {
            // Тут все було ок, два аргументи для двох %d
            String.format("%02d:%02d год.", hours, minutes)
        } else {
            String.format("%02d:%02d хв.", minutes, seconds)
        }

        return CountdownInfo(
            status = if (isCurrentlyOutage) "ВИМКНЕННЯ" else "СВІТЛО Є",
            timeToEvent = timeToEventString,
            nextEventTime = nextEventTime.format(timeFormatter)
        )
    }
}