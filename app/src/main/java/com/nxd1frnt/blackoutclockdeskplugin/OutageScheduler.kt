package com.nxd1frnt.blackoutclockdeskplugin

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.*

data class OutageInterval(val start: String, val end: String, val duration: String)

private data class ScheduleSegment(
    val from: Int = 0,
    val to: Int = 0,
    val status: String? = null
)

private data class SchedulePayload(
    val today: List<ScheduleSegment>? = null,
    val tomorrow: List<ScheduleSegment>? = null
)

// ОНОВЛЕНО: Кеш містить дату, графік на сьогодні і на завтра
data class OutageCache(
    val timestamp: Long = System.currentTimeMillis(),
    val cacheDate: String = LocalDate.now().toString(), // ISO-8601: YYYY-MM-DD
    val intervalsToday: List<OutageInterval>,
    val intervalsTomorrow: List<OutageInterval>? = null, // Може бути null, якщо графіку ще немає
    val sourceId: String? = null,
    val changes: List<String>? = null,
    val changesTimestamp: Long? = null,
    val emergencyMessage: String? = null
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
        const val KEY_DATA_SOURCE = "data_source"
        const val SOURCE_ENERGY_UA = "energy_ua"
        const val SOURCE_POE = "poe"
        const val KEY_POE_QUEUE = "poe_queue"
        const val DEFAULT_POE_QUEUE = "5.2"

        internal fun formatMinutes(minutes: Int): String {
            if (minutes >= 1440) return "24:00"
            val h = minutes / 60
            val m = minutes % 60
            return String.format(Locale.US, "%02d:%02d", h, m)
        }

        internal fun formatDurationMinutes(totalMinutes: Int): String {
            return when {
                totalMinutes % 60 == 0 -> "${totalMinutes / 60}"
                totalMinutes % 30 == 0 -> "${totalMinutes / 60}.5"
                totalMinutes < 60 -> "$totalMinutes хв"
                else -> "${totalMinutes / 60} год ${totalMinutes % 60} хв"
            }
        }
    }

    private val currentDataSource: String
        get() {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_DATA_SOURCE, SOURCE_ENERGY_UA) ?: SOURCE_ENERGY_UA
        }

    private val currentUrl: String
        get() {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        }

    private val currentPoeQueue: String
        get() {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_POE_QUEUE, DEFAULT_POE_QUEUE) ?: DEFAULT_POE_QUEUE
        }

    private fun currentSourceId(): String {
        return if (currentDataSource == SOURCE_POE) {
            "$SOURCE_POE:$currentPoeQueue"
        } else {
            "$SOURCE_ENERGY_UA:$currentUrl"
        }
    }

    private val gson = Gson()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun clearCache() {
        try {
            val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            if (cacheFile.exists()) {
                cacheFile.delete()
                Log.d(TAG, "Кеш очищено")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Помилка очищення кешу", e)
        }
    }

    suspend fun getCachedSchedule(): OutageCache? = withContext(Dispatchers.IO) {
        loadCache()
    }

    suspend fun fetchAndCacheIfNeeded(): OutageCache? {
        val cachedData = loadCache()
        val todayStr = LocalDate.now().toString()
        val isDifferentDay = cachedData?.cacheDate != null && cachedData.cacheDate != todayStr
        val isDifferentSource = cachedData?.sourceId != null && cachedData.sourceId != currentSourceId()
        val isTimeExpired = cachedData == null || (System.currentTimeMillis() - cachedData.timestamp > CACHE_EXPIRATION_MS)
        val isCacheExpired = isTimeExpired || isDifferentDay || isDifferentSource

        if (isCacheExpired) {
            val newData = fetchAndMergeWithOldCache(cachedData)
            if (newData != null) {
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

    suspend fun forceRefresh(): OutageCache? {
        val cachedData = loadCache()
        val newData = fetchAndMergeWithOldCache(cachedData)
        if (newData != null) {
            saveCache(newData)
        }
        return newData
    }

    private suspend fun fetchAndMergeWithOldCache(oldCache: OutageCache?): OutageCache? {
        val fetched = fetchScheduleFromWeb() ?: return null
        val sourceId = currentSourceId()
        val sameDayAndSource = oldCache != null &&
            oldCache.cacheDate == fetched.cacheDate &&
            (oldCache.sourceId == null || oldCache.sourceId == sourceId)

        if (!sameDayAndSource) {
            return fetched.copy(sourceId = sourceId)
        }

        val newDiffs = ScheduleDiffCalculator.compareCaches(oldCache!!, fetched)
        return if (newDiffs.isNotEmpty()) {
            Log.i(TAG, "Виявлено зміни в графіку: ${newDiffs.size}")
            fetched.copy(
                sourceId = sourceId,
                changes = newDiffs,
                changesTimestamp = fetched.timestamp
            )
        } else {
            fetched.copy(
                sourceId = sourceId,
                changes = oldCache.changes,
                changesTimestamp = oldCache.changesTimestamp
            )
        }
    }

    suspend fun fetchScheduleFromWeb(): OutageCache? {
        return try {
            val result = if (currentDataSource == SOURCE_POE) {
                Log.d(TAG, "Починаємо завантаження з ПОЕ для черги $currentPoeQueue")
                PoeScheduleClient.fetchSchedule(currentPoeQueue)
            } else {
                Log.d(TAG, "Починаємо завантаження через WebView: $currentUrl")
                val html = withTimeout(12000L) {
                    WebViewFetcher(context).fetchHtml(currentUrl)
                }
                parseHtmlSchedule(html)
            }

            if (result == null) {
                Log.e(TAG, "ПОМИЛКА: Не вдалося отримати або розпарсити графік ($currentDataSource)")
            } else {
                Log.d(TAG, "Знайдено інтервалів: Сьогодні=${result.intervalsToday.size}, Завтра=${result.intervalsTomorrow?.size ?: 0}")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Помилка завантаження: ${e.message}")
            null
        }
    }

    internal fun parseHtmlSchedule(html: String): OutageCache? {
        val doc = Jsoup.parse(html)

        // Новий дизайн сайту: JSON у <script id="schedulePayload" type="application/json">
        val payloadElement = doc.selectFirst("script#schedulePayload")
        if (payloadElement != null) {
            val jsonText = payloadElement.data().ifBlank { payloadElement.html() }.trim()
            if (jsonText.isNotEmpty()) {
                return try {
                    val payload = gson.fromJson(jsonText, SchedulePayload::class.java)
                    if (payload == null || (payload.today == null && payload.tomorrow == null)) {
                        null
                    } else {
                        val todayList = parsePayloadSegments(payload.today.orEmpty())
                        val tomorrowList = parsePayloadSegments(payload.tomorrow.orEmpty())
                        OutageCache(
                            timestamp = System.currentTimeMillis(),
                            cacheDate = LocalDate.now().toString(),
                            intervalsToday = todayList,
                            intervalsTomorrow = tomorrowList
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }

        // Fallback для старого дизайну (div.periods_items / div.no_data)
        val containers = doc.select("div.periods_items")
        val noDataElements = doc.select("div.no_data")

        if (containers.isEmpty() && noDataElements.isEmpty()) {
            return null
        }

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

        val todayList = if (containers.isNotEmpty()) parseContainer(containers[0]) else emptyList()
        val tomorrowList = if (containers.size > 1) parseContainer(containers[1]) else emptyList()

        return OutageCache(
            timestamp = System.currentTimeMillis(),
            cacheDate = LocalDate.now().toString(),
            intervalsToday = todayList,
            intervalsTomorrow = tomorrowList
        )
    }

    private fun parsePayloadSegments(segments: List<ScheduleSegment>): List<OutageInterval> {
        val redSegments = segments
            .asSequence()
            .filter { it.status == "red" }
            .map { seg ->
                val start = seg.from.coerceIn(0, 1440)
                val end = seg.to.coerceIn(0, 1440)
                start to end
            }
            .filter { (start, end) -> end > start }
            .sortedBy { it.first }
            .toList()

        if (redSegments.isEmpty()) return emptyList()

        val merged = mutableListOf<Pair<Int, Int>>()
        for ((start, end) in redSegments) {
            val last = merged.lastOrNull()
            if (last != null && start <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, end)
            } else {
                merged.add(start to end)
            }
        }

        return merged.map { (start, end) ->
            OutageInterval(
                start = formatMinutes(start),
                end = formatMinutes(end),
                duration = formatDurationMinutes(end - start)
            )
        }
    }

    private fun loadCache(): OutageCache? {
        val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
        if (!cacheFile.exists()) return null

        return try {
            val json = cacheFile.readText()
            val cache = gson.fromJson(json, OutageCache::class.java)

            // Перевіряємо цілісність кешу (Gson може залишити null у не-nullable полі)
            @Suppress("SENSELESS_COMPARISON")
            if (cache == null || cache.intervalsToday == null) {
                Log.w(TAG, "Кеш пошкоджений або застарів. Видаляємо.")
                cacheFile.delete()
                return null
            }

            val todayStr = LocalDate.now().toString()
            val cacheDateStr = cache.cacheDate ?: todayStr
            val yesterdayStr = LocalDate.now().minusDays(1).toString()

            // Перевірка переходу через опівніч:
            // Якщо дата вчорашня і був розклад на завтра, робимо роловер на сьогодні
            if (cacheDateStr == yesterdayStr && !cache.intervalsTomorrow.isNullOrEmpty()) {
                Log.i(TAG, "Перехід через північ: оновлюємо розклад з учорашнього 'завтра' на сьогодні.")
                val rolledOverCache = OutageCache(
                    timestamp = System.currentTimeMillis(),
                    cacheDate = todayStr,
                    intervalsToday = cache.intervalsTomorrow,
                    intervalsTomorrow = null
                )
                saveCache(rolledOverCache)
                return rolledOverCache
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
        if (cache == null) {
            return CountdownInfo("ДАНИХ НЕМАЄ", "—", "—")
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
            return CountdownInfo("СВІТЛО Є", "—", "Відключень немає")
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
    private fun normalizeTime(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "24:00" || trimmed == "24:00:00") return "23:59"
        val parts = trimmed.split(":")
        if (parts.size >= 2) {
            val h = parts[0].padStart(2, '0')
            val m = parts[1].padStart(2, '0')
            return "$h:$m"
        }
        return trimmed
    }

    fun parseIntervals(rawList: List<OutageInterval>): List<Pair<LocalTime, LocalTime>> {
        return rawList.mapNotNull { interval ->
            try {
                val startStr = normalizeTime(interval.start)
                val endStr = normalizeTime(interval.end)
                val start = LocalTime.parse(startStr, timeFormatter)
                val end = LocalTime.parse(endStr, timeFormatter)
                if (end.isBefore(start)) listOf(start to LocalTime.MAX, LocalTime.MIN to end) else listOf(start to end)
            } catch (e: Exception) {
                Log.w(TAG, "Помилка парсингу інтервалу: ${interval.start} - ${interval.end}", e)
                null
            }
        }.flatten()
    }

    private fun isTimeInInterval(time: LocalTime, start: LocalTime, end: LocalTime): Boolean {
        if (end == LocalTime.MIN) return false // Початок наступної доби
        if (end == LocalTime.MAX) return !time.isBefore(start)
        return !time.isBefore(start) && time.isBefore(end)
    }
}