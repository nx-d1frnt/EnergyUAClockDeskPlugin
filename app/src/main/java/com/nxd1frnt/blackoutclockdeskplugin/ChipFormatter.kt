package com.nxd1frnt.blackoutclockdeskplugin

import android.content.Context

object ChipFormatter {
    const val PREFS_NAME = OutageScheduler.PREFS_NAME
    const val KEY_FORMAT_TYPE = "chip_format_type"
    const val KEY_CUSTOM_TEMPLATE = "chip_custom_template"

    const val FORMAT_DETAILED = "DETAILED"
    const val FORMAT_TIME_ONLY = "TIME_ONLY"
    const val FORMAT_COUNTDOWN_ONLY = "COUNTDOWN_ONLY"
    const val FORMAT_COMPACT = "COMPACT"
    const val FORMAT_CUSTOM = "CUSTOM"

    const val DEFAULT_CUSTOM_TEMPLATE = "{status} | до {time} ({remaining})"

    fun format(context: Context, info: CountdownInfo): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val formatType = prefs.getString(KEY_FORMAT_TYPE, FORMAT_DETAILED) ?: FORMAT_DETAILED
        val customTemplate = prefs.getString(KEY_CUSTOM_TEMPLATE, DEFAULT_CUSTOM_TEMPLATE) ?: DEFAULT_CUSTOM_TEMPLATE

        return formatWithStyle(info, formatType, customTemplate)
    }

    fun formatWithStyle(info: CountdownInfo, formatType: String, customTemplate: String = DEFAULT_CUSTOM_TEMPLATE): String {
        if (info.status == "ДАНИХ НЕМАЄ") {
            return "Немає графіку"
        }

        if (info.timeToEvent == "—") {
            return if (info.status == "СВІТЛО Є") "Світло є (без відключень)" else "Світло є"
        }

        val statusShort = when (info.status) {
            "СВІТЛО Є" -> "Є"
            "ВИМКНЕННЯ" -> "Нема"
            else -> info.status
        }

        val targetTime = info.nextEventTime
        val remaining = info.timeToEvent

        return when (formatType) {
            FORMAT_TIME_ONLY -> {
                val timePrefix = if (targetTime.startsWith("Завтра")) targetTime else "до $targetTime"
                "$statusShort $timePrefix"
            }
            FORMAT_COUNTDOWN_ONLY -> {
                "$statusShort ($remaining)"
            }
            FORMAT_COMPACT -> {
                "$statusShort $targetTime"
            }
            FORMAT_CUSTOM -> {
                val template = if (customTemplate.isBlank()) DEFAULT_CUSTOM_TEMPLATE else customTemplate
                template.replace("{status}", statusShort)
                    .replace("{time}", targetTime)
                    .replace("{remaining}", remaining)
            }
            FORMAT_DETAILED -> {
                val timePrefix = if (targetTime.startsWith("Завтра")) targetTime else "до $targetTime"
                "$statusShort | $timePrefix ($remaining)"
            }
            else -> {
                val timePrefix = if (targetTime.startsWith("Завтра")) targetTime else "до $targetTime"
                "$statusShort | $timePrefix ($remaining)"
            }
        }
    }
}
