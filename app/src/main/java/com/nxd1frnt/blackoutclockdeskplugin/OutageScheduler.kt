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

data class OutageInterval(val start: String, val end: String, val duration: String)

// ОНОВЛЕНО: Кеш тепер містить графік на сьогодні і на завтра
data class OutageCache(
    val timestamp: Long = System.currentTimeMillis(),
    val intervalsToday: List<OutageInterval>,
    val intervalsTomorrow: List<OutageInterval>? = null // Може бути null, якщо графіку ще немає
)

data class CountdownInfo(
    val status: String,
    val timeToEvent: String,
    val nextEventTime: String
)

class OutageScheduler(private val context: Context) {

    private val TAG = "OutageScheduler"
    private val CACHE_FILE_NAME = "blackout_schedule_cache.json"
    private val CACHE_EXPIRATION_MS = 30 * 60 * 1000L // 30 хвилин

    companion object {
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

    suspend fun getCachedSchedule(): OutageCache? = withContext(Dispatchers.IO) {
        loadCache()
    }

    suspend fun fetchAndCacheIfNeeded(): OutageCache? {
        val cachedData = loadCache()
        val isCacheExpired = cachedData == null || (System.currentTimeMillis() - cachedData.timestamp > CACHE_EXPIRATION_MS)

        if (isCacheExpired) {
            val newData = fetchScheduleFromWeb()
            // Перевіряємо, чи ми отримали хоча б сьогоднішній графік
            if (newData != null && newData.intervalsToday.isNotEmpty()) {
                saveCache(newData)
                Log.i(TAG, "Графік оновлено. Сьогодні: ${newData.intervalsToday.size}, Завтра: ${newData.intervalsTomorrow?.size ?: 0}")
                return newData
            } else {
                Log.w(TAG, "Не вдалося оновити графік. Використовуємо старий кеш.")
                return cachedData
            }
        }
        return cachedData
    }

    suspend fun fetchScheduleFromWeb(): OutageCache? {
        return try {
            Log.d(TAG, "Починаємо завантаження через WebView: $currentUrl")
            val html = withTimeout(25000L) {
                WebViewFetcher(context).fetchHtml(currentUrl)
            }

            val doc = Jsoup.parse(html)

            // Знаходимо ВСІ блоки з графіками
            val containers = doc.select("div.periods_items")

            if (containers.isEmpty()) {
                Log.e(TAG, "ПОМИЛКА: Не знайдено жодного контейнера div.periods_items")
                return null
            }

            // Функція-хелпер для парсингу одного контейнера
            fun parseContainer(container: org.jsoup.nodes.Element): List<OutageInterval> {
                val list = mutableListOf<OutageInterval>()
                container.select("span").forEach { span ->
                    val bTags = span.select("b")
                    if (bTags.size >= 3) {
                        list.add(OutageInterval(
                            start = bTags[0].text().trim(),
                            end = bTags[1].text().trim(),
                            duration = bTags[2].text().replace("год.", "").trim()
                        ))
                    }
                }
                return list
            }

            // Перший блок - завжди "Сьогодні"
            val todayList = parseContainer(containers[0])

            // Другий блок (якщо є) - "Завтра"
            val tomorrowList = if (containers.size > 1) {
                parseContainer(containers[1])
            } else {
                null
            }

            Log.d(TAG, "Знайдено інтервалів: Сьогодні=${todayList.size}, Завтра=${tomorrowList?.size ?: 0}")

            OutageCache(
                intervalsToday = todayList,
                intervalsTomorrow = tomorrowList
            )

        } catch (e: Exception) {
            Log.e(TAG, "Помилка завантаження: ${e.message}")
            null
        }
    }

    private fun loadCache(): OutageCache? {
        val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
        if (!cacheFile.exists()) return null

        return try {
            val json = cacheFile.readText()
            val cache = gson.fromJson(json, OutageCache::class.java)

            // --- ВИПРАВЛЕННЯ ---
            // Gson може створити об'єкт з null полями, якщо JSON старий.
            // Перевіряємо це вручну:
            if (cache == null || cache.intervalsToday == null) {
                Log.w(TAG, "Кеш пошкоджений або застарів (intervalsToday is null). Видаляємо.")
                cacheFile.delete()
                return null
            }

            cache
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

    // --- Логіка таймера ---

    fun getCountdownInfo(cache: OutageCache?): CountdownInfo {
        if (cache == null || cache.intervalsToday.isEmpty()) {
            return CountdownInfo("ДАНИХ НЕМАЄ", "00:00 хв", "—")
        }

        val now = LocalTime.now()

        // 1. Аналізуємо СЬОГОДНІ
        val todayIntervals = parseIntervals(cache.intervalsToday)

        // Визначаємо поточний статус (чи ми зараз у відключенні)
        var isCurrentlyOutage = todayIntervals.any { (start, end) ->
            isTimeInInterval(now, start, end)
        }

        var nextEventTime: LocalTime? = null

        if (isCurrentlyOutage) {
            // Якщо світла немає, шукаємо коли воно з'явиться СЬОГОДНІ
            nextEventTime = todayIntervals
                .filter { isTimeInInterval(now, it.first, it.second) }
                .map { (_, end) -> if (end == LocalTime.MAX) LocalTime.MAX else end }
                .minOrNull()

            // Якщо інтервал йде до кінця доби (24:00), то ввімкнення буде вже завтра
            if (nextEventTime == LocalTime.MAX) {
                nextEventTime = null // Шукатимемо у блоці "завтра"
            }
        } else {
            // Якщо світло є, шукаємо найближче вимкнення СЬОГОДНІ
            nextEventTime = todayIntervals
                .map { (start, _) -> start }
                .filter { it.isAfter(now) }
                .minOrNull()
        }

        // 2. Якщо сьогодні подій більше немає, шукаємо у ЗАВТРА
        var daysOffset = 0L // Додамо 24 години до розрахунку, якщо подія завтра

        if (nextEventTime == null) {
            daysOffset = 1L

            // Вибираємо джерело для завтра: реальний графік або копію сьогоднішнього (якщо реального немає)
            val tomorrowSource = if (!cache.intervalsTomorrow.isNullOrEmpty()) {
                cache.intervalsTomorrow
            } else {
                cache.intervalsToday // Fallback: вважаємо, що графік повторюється
            }

            val tomorrowIntervals = parseIntervals(tomorrowSource)

            if (isCurrentlyOutage) {
                // Ми досі без світла (з сьогоднішнього вечора), шукаємо коли воно ввімкнеться завтра
                // Це має бути кінець першого інтервалу, якщо він починається з 00:00
                val firstInterval = tomorrowIntervals.firstOrNull()
                if (firstInterval != null && firstInterval.first == LocalTime.MIN) {
                    nextEventTime = firstInterval.second
                } else {
                    // Дивна ситуація: вчора вимкнули до 24:00, а завтра з 00:00 вимкнень немає.
                    // Значить ввімкнення о 00:00
                    nextEventTime = LocalTime.MIN
                }
            } else {
                // Світло є, шукаємо перше вимкнення завтра
                nextEventTime = tomorrowIntervals.map { it.first }.minOrNull()
            }
        }

        // 3. Формування результату
        if (nextEventTime == null) {
            // Якщо і завтра графік пустий
            return CountdownInfo("СВІТЛО Є", "—", "Графік невідомий")
        }

        // Розрахунок різниці в часі
        val nowSeconds = now.toSecondOfDay().toLong()
        val targetSeconds = nextEventTime.toSecondOfDay().toLong() + (daysOffset * 24 * 3600)

        var diffSeconds = targetSeconds - nowSeconds
        if (diffSeconds < 0) diffSeconds += 24 * 3600 // На випадок переходу через добу

        val hours = diffSeconds / 3600
        val minutes = (diffSeconds % 3600) / 60
        val seconds = diffSeconds % 60

        val timeToEventString = if (hours > 0) {
            String.format("%02d:%02d год.", hours, minutes)
        } else {
            String.format("%02d:%02d хв.", minutes, seconds)
        }

        val nextEventString = if (daysOffset > 0) "Завтра ${nextEventTime}" else nextEventTime.toString()

        return CountdownInfo(
            status = if (isCurrentlyOutage) "ВИМКНЕННЯ" else "СВІТЛО Є",
            timeToEvent = timeToEventString,
            nextEventTime = nextEventString
        )
    }

    // Хелпери
    private fun parseIntervals(rawList: List<OutageInterval>): List<Pair<LocalTime, LocalTime>> {
        return rawList.mapNotNull { interval ->
            try {
                val start = LocalTime.parse(interval.start, timeFormatter)
                val end = LocalTime.parse(interval.end, timeFormatter)
                if (end.isBefore(start)) listOf(start to LocalTime.MAX, LocalTime.MIN to end) else listOf(start to end)
            } catch (e: Exception) { null }
        }.flatten()
    }

    private fun isTimeInInterval(time: LocalTime, start: LocalTime, end: LocalTime): Boolean {
        if (end == LocalTime.MIN) return false // Початок наступної доби
        if (end == LocalTime.MAX) return !time.isBefore(start)
        return !time.isBefore(start) && time.isBefore(end)
    }
}