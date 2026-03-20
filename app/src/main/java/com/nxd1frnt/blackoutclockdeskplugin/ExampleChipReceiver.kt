package com.nxd1frnt.blackoutclockdeskplugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ExampleChipReceiver : BroadcastReceiver() {

    private lateinit var scheduler: OutageScheduler

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    companion object {
        const val ACTION_REQUEST_DATA = "com.nxd1frnt.clockdesk2.ACTION_REQUEST_CHIP_DATA"
        const val ACTION_UPDATE_DATA = "com.nxd1frnt.clockdesk2.ACTION_UPDATE_CHIP_DATA"
        const val CLOCKDESK_PACKAGE = "com.nxd1frnt.clockdesk2"

        const val UPDATE_INTERVAL_SEC = 60
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!::scheduler.isInitialized) {
            scheduler = OutageScheduler(context.applicationContext)
        }

        if (intent.action == ACTION_REQUEST_DATA) {
            Log.d("BlackoutChip", "Received data request from ClockDesk")

            // Використовуємо goAsync() для фонової роботи
            val pendingResult = goAsync()

            receiverScope.launch{
                try {
                    // 1. Оновлення кешу, якщо він прострочений (10 хвилин)
                    // Або використовуємо старий кеш, якщо немає інтернету
                    val cache = scheduler.fetchAndCacheIfNeeded()

                    // 2. Отримання інформації про відлік часу
                    val info = scheduler.getCountdownInfo(cache)

                    // 3. Форматування даних для ClockDesk
                    val chipText = formatChipText(info)
                    val iconName = getIconName(info)

                    // 4. Надсилання даних назад до ClockDesk
                    val responseIntent = Intent(ACTION_UPDATE_DATA).apply {
                        setPackage(CLOCKDESK_PACKAGE)
                        putExtra("plugin_package_name", context.packageName)
                        putExtra("chip_text", chipText)
                        putExtra("chip_icon_name", iconName)
                        putExtra("chip_click_activity", ".BlackoutDetailsActivity")
                        // Просимо ClockDesk смикнути нас ще раз через 60 секунд
                        putExtra("update_interval_seconds", UPDATE_INTERVAL_SEC)
                    }

                    context.sendBroadcast(responseIntent)
                    Log.d("BlackoutChip", "Sent data: $chipText")

                } catch (e: Exception) {
                    Log.e("BlackoutChip", "Error fetching plugin data", e)
                    val errorIntent = Intent(ACTION_UPDATE_DATA).apply {
                        setPackage(CLOCKDESK_PACKAGE)
                        putExtra("plugin_package_name", context.packageName)
                        putExtra("chip_text", "Помилка")
                        putExtra("chip_icon_name", "alert_circle_outline")
                        putExtra("chip_click_activity", ".BlackoutDetailsActivity")
                    }
                    context.sendBroadcast(errorIntent)
                } finally {
                    // Обов'язково завершуємо Broadcast, інакше система покарає ANR
                    pendingResult.finish()
                }
            }
        }
    }

    private fun formatChipText(info: CountdownInfo): String {
        return when (info.status) {
            "СВІТЛО Є" -> "Є | Через ${info.timeToEvent}"
            "ВИМКНЕННЯ" -> "Нема | Через ${info.timeToEvent}"
            "ДАНИХ НЕМАЄ" -> "Немає графіку"
            else -> "..."
        }
    }

    private fun getIconName(info: CountdownInfo): String {
        return when (info.status) {
            "СВІТЛО Є" -> "lightbulb_outline"
            "ВИМКНЕННЯ" -> "lightbulb_off_outline"
            else -> "alert_circle_outline"
        }
    }
}