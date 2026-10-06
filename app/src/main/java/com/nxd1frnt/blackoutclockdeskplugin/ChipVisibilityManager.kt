package com.nxd1frnt.blackoutclockdeskplugin

import android.content.Context

object ChipVisibilityManager {
    const val PREFS_NAME = OutageScheduler.PREFS_NAME

    const val KEY_VISIBILITY_MODE = "chip_visibility_mode"
    const val KEY_WARNING_MINUTES = "chip_warning_minutes"

    const val MODE_ALWAYS = "ALWAYS"
    const val MODE_HIDE_IF_NO_OUTAGES_TODAY = "HIDE_NO_OUTAGES"
    const val MODE_SHOW_ONLY_DURING_OUTAGE = "SHOW_DURING_OUTAGE"

    const val DEFAULT_WARNING_MINUTES = 30

    fun isChipVisible(context: Context, cache: OutageCache?, info: CountdownInfo): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_VISIBILITY_MODE, MODE_ALWAYS) ?: MODE_ALWAYS
        val warningMinutes = prefs.getInt(KEY_WARNING_MINUTES, DEFAULT_WARNING_MINUTES)

        return computeVisibility(mode, warningMinutes, cache, info)
    }

    fun computeVisibility(
        mode: String,
        warningMinutes: Int,
        cache: OutageCache?,
        info: CountdownInfo
    ): Boolean {
        // Якщо сталася помилка або даних немає — показуємо віджет для інформування
        if (info.status == "ДАНИХ НЕМАЄ" || info.status == "Помилка" || cache == null) {
            return true
        }

        return when (mode) {
            MODE_HIDE_IF_NO_OUTAGES_TODAY -> {
                // Якщо на сьогодні немає жодного відключення
                if (cache.intervalsToday.isEmpty() || (info.status == "СВІТЛО Є" && info.timeToEvent == "—")) {
                    false
                } else {
                    true
                }
            }

            MODE_SHOW_ONLY_DURING_OUTAGE -> {
                if (info.status == "ВИМКНЕННЯ") {
                    // Під час вимкнення світла завжди показуємо
                    true
                } else if (info.status == "СВІТЛО Є") {
                    // Якщо світло є, перевіряємо, чи наближається вимкнення
                    val minutesRemaining = parseRemainingMinutes(info.timeToEvent)
                    if (minutesRemaining != null && warningMinutes > 0 && minutesRemaining <= warningMinutes) {
                        true
                    } else {
                        false
                    }
                } else {
                    true
                }
            }

            MODE_ALWAYS -> true
            else -> true
        }
    }

    fun parseRemainingMinutes(timeToEvent: String): Int? {
        val trimmed = timeToEvent.trim()
        if (trimmed == "—" || trimmed.isEmpty()) return null

        return try {
            if (trimmed.contains("год")) {
                val numPart = trimmed.substringBefore("год").trim()
                val parts = numPart.split(":")
                val h = parts[0].toIntOrNull() ?: 0
                val m = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
                h * 60 + m
            } else if (trimmed.contains("хв")) {
                val numPart = trimmed.substringBefore("хв").trim()
                val parts = numPart.split(":")
                parts[0].toIntOrNull() ?: 0
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
