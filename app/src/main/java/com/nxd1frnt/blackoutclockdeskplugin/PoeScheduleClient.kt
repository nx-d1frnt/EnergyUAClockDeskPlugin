package com.nxd1frnt.blackoutclockdeskplugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.select.Elements
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Клієнт та парсер графіків ПолтаваОблЕнерго (poe.pl.ua).
 */
object PoeScheduleClient {

    const val POE_PUBLIC_URL = "https://www.poe.pl.ua/disconnection/power-outages/"
    private const val BASE_ENDPOINT = "https://www.poe.pl.ua/customs/"
    private val KYIV_ZONE: ZoneId = ZoneId.of("Europe/Kyiv")

    private val MONTHS = listOf(
        "січня", "лютого", "березня", "квітня", "травня", "червня",
        "липня", "серпня", "вересня", "жовтня", "листопада", "грудня"
    )

    val ALL_QUEUES = listOf(
        "1.1", "1.2", "2.1", "2.2", "3.1", "3.2",
        "4.1", "4.2", "5.1", "5.2", "6.1", "6.2"
    )

    suspend fun fetchSchedule(queue: String): OutageCache? = withContext(Dispatchers.IO) {
        val now = ZonedDateTime.now(KYIV_ZONE)
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)

        val todayHtml = postDate("newgpv-info.php", today)
        val todayMap = parseSchedule(todayHtml, today) ?: return@withContext null
        val todayIntervals = todayMap[queue] ?: return@withContext null

        val tomorrowIntervals = try {
            val tomorrowHtml = postDate("newgpv-info.php", tomorrow)
            parseSchedule(tomorrowHtml, tomorrow)?.get(queue) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val emergencyMessage = try {
            val unloadingJson = postDate("unloading-info.php", today)
            parseEmergency(unloadingJson, now)
        } catch (_: Exception) {
            null
        }

        OutageCache(
            timestamp = System.currentTimeMillis(),
            cacheDate = today.toString(),
            intervalsToday = todayIntervals,
            intervalsTomorrow = tomorrowIntervals,
            emergencyMessage = emergencyMessage
        )
    }

    private fun postDate(endpoint: String, date: LocalDate): String {
        val dateText = date.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
        val payload = "{\"date_in\":\"$dateText\"}"
        val body = "seldate=" + URLEncoder.encode(payload, "UTF-8")

        val conn = (URL(BASE_ENDPOINT + endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
        }

        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("ПОЕ: HTTP $code")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Парсить HTML-відповідь ПОЕ у мапу "черга -> список інтервалів відключення".
     * Повертає null, якщо розклад на вказану дату ще не опубліковано або не знайдено.
     */
    internal fun parseSchedule(html: String, date: LocalDate): Map<String, List<OutageInterval>>? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)
        val details = doc.select(".gpvinfodetail")
        if (details.isEmpty()) return null

        val expectedDate = "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]} ${date.year} року"
        val detail = details.firstOrNull { it.text().contains(expectedDate) } ?: return null

        val gpvRows = detail.select("table.gpv-table tbody tr")
        val slotsByQueue = if (gpvRows.isNotEmpty()) {
            parseGpvRows(gpvRows)
        } else {
            val legacyRows = detail.select("table.turnoff-scheduleui-table tbody tr")
            if (legacyRows.isEmpty()) return null
            parseLegacyRows(legacyRows)
        }

        if (slotsByQueue.size != 12) return null
        return slotsByQueue.mapValues { (_, slots) -> slotsToOutageIntervals(slots) }
    }

    private fun parseGpvRows(rows: Elements): Map<String, List<Int>> {
        if (rows.size != 12) return emptyMap()
        val result = linkedMapOf<String, List<Int>>()
        for (row in rows) {
            val queueText = row.selectFirst(".gpv-q")?.text()?.trim() ?: return emptyMap()
            val parts = queueText.split(".")
            if (parts.size != 2) return emptyMap()
            val mainQueue = parts[0].toIntOrNull() ?: return emptyMap()
            val subQueue = parts[1].toIntOrNull() ?: return emptyMap()
            if (mainQueue !in 1..6 || subQueue !in 1..2) return emptyMap()

            val cells = row.children().filter { !it.hasClass("gpv-q") }
            if (cells.size != 48) return emptyMap()
            val slots = cells.map { cell ->
                when {
                    cell.hasClass("gpv-s1") -> 1
                    cell.hasClass("gpv-s2") -> 0
                    cell.hasClass("gpv-s3") -> 4
                    else -> return emptyMap()
                }
            }
            result["$mainQueue.$subQueue"] = slots
        }
        return result
    }

    private fun parseLegacyRows(rows: Elements): Map<String, List<Int>> {
        if (rows.size != 12) return emptyMap()
        val result = linkedMapOf<String, List<Int>>()
        var mainQueue = 0
        for (row in rows) {
            row.selectFirst(".turnoff-scheduleui-table-queue")?.let {
                mainQueue = it.text().substringBefore(" ").toIntOrNull() ?: 0
            }
            val subQueue = row.selectFirst(".turnoff-scheduleui-table-subqueue")?.text()?.toIntOrNull()
                ?: return emptyMap()
            if (mainQueue !in 1..6 || subQueue !in 1..2) return emptyMap()

            val cells = row.children().filter {
                !it.hasClass("turnoff-scheduleui-table-queue") &&
                    !it.hasClass("turnoff-scheduleui-table-subqueue")
            }
            if (cells.size != 48) return emptyMap()
            val slots = cells.map { cell ->
                when (cell.className()) {
                    "light_1" -> 1
                    "light_2" -> 0
                    "light_3" -> 4
                    else -> return emptyMap()
                }
            }
            result["$mainQueue.$subQueue"] = slots
        }
        return result
    }

    internal fun slotsToOutageIntervals(slots: List<Int>): List<OutageInterval> {
        val intervals = mutableListOf<OutageInterval>()
        var startSlot: Int? = null
        for (i in slots.indices) {
            if (slots[i] == 0) {
                if (startSlot == null) startSlot = i
            } else if (startSlot != null) {
                intervals.add(buildInterval(startSlot, i))
                startSlot = null
            }
        }
        if (startSlot != null) {
            intervals.add(buildInterval(startSlot, slots.size))
        }
        return intervals
    }

    private fun buildInterval(startSlot: Int, endSlot: Int): OutageInterval {
        val startMin = startSlot * 30
        val endMin = endSlot * 30
        return OutageInterval(
            start = OutageScheduler.formatMinutes(startMin),
            end = OutageScheduler.formatMinutes(endMin),
            duration = OutageScheduler.formatDurationMinutes(endMin - startMin)
        )
    }

    internal fun parseEmergency(json: String, now: ZonedDateTime): String? {
        if (json.isBlank()) return null
        val items = JSONArray(json)
        val dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
        val active = (0 until items.length()).map { items.getJSONObject(it) }.filter { item ->
            if (item.getInt("unloadingtypeid") !in 1..2) return@filter false
            val date = LocalDate.parse(item.getString("unloaddata"), dateFormatter)
            val start = date.atTime(item.getInt("beginhour"), item.getInt("beginminute")).atZone(now.zone)
            val endHour = item.getInt("endhour")
            val endMinute = item.getInt("endminute")
            val end = if (endHour == 23 && endMinute == 59) {
                date.plusDays(1).atStartOfDay(now.zone)
            } else {
                date.atTime(endHour, endMinute).atZone(now.zone)
            }
            !now.isBefore(start) && now.isBefore(end)
        }
        if (active.isEmpty()) return null
        val sgav = active.any { it.getInt("unloadingtypeid") == 2 }
        return if (sgav) {
            "Увага! Застосовано СГАВ! Графіки погодинних відключень не діють"
        } else {
            "Увага! Застосовано ГАВ! Графіки можуть не діяти"
        }
    }
}
