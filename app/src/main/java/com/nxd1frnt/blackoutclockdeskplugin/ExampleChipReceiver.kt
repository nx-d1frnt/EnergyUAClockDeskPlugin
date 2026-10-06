package com.nxd1frnt.blackoutclockdeskplugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

        fun getIconName(info: CountdownInfo): String {
            return when (info.status) {
                "СВІТЛО Є" -> "lightbulb_outline"
                "ВИМКНЕННЯ" -> "lightbulb_off_outline"
                else -> "alert_circle_outline"
            }
        }

        fun triggerImmediateUpdate(context: Context) {
            val scheduler = OutageScheduler(context.applicationContext)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val cache = scheduler.getCachedSchedule() ?: return@launch
                    val info = scheduler.getCountdownInfo(cache)
                    val isVisible = ChipVisibilityManager.isChipVisible(context, cache, info)
                    val chipText = ChipFormatter.format(context, info)
                    val iconName = getIconName(info)

                    val responseIntent = Intent(ACTION_UPDATE_DATA).apply {
                        setPackage(CLOCKDESK_PACKAGE)
                        putExtra("chip_visible", isVisible)
                        putExtra("plugin_package_name", context.packageName)
                        putExtra("chip_text", chipText)
                        putExtra("chip_icon_name", iconName)
                        putExtra("chip_click_activity", ".BlackoutDetailsActivity")
                        putExtra("update_interval_seconds", UPDATE_INTERVAL_SEC)
                    }

                    context.sendBroadcast(responseIntent)
                    Log.d("BlackoutChip", "Sent immediate update: $chipText (visible=$isVisible)")
                } catch (e: Exception) {
                    Log.e("BlackoutChip", "Error sending immediate update", e)
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!::scheduler.isInitialized) {
            scheduler = OutageScheduler(context.applicationContext)
        }

        if (intent.action == ACTION_REQUEST_DATA) {
            Log.d("BlackoutChip", "Received data request from ClockDesk")

            // Використовуємо goAsync() для фонової роботи
            val pendingResult = goAsync()

            receiverScope.launch {
                try {
                    // 1. Оновлення кешу, якщо він прострочений (30 хвилин)
                    // Або використовуємо старий кеш, якщо немає інтернету
                    val cache = scheduler.fetchAndCacheIfNeeded()

                    // 2. Отримання інформації про відлік часу
                    val info = scheduler.getCountdownInfo(cache)

                    // 3. Розрахунок видимості та форматування тексту
                    val isVisible = ChipVisibilityManager.isChipVisible(context, cache, info)
                    val chipText = ChipFormatter.format(context, info)
                    val iconName = getIconName(info)

                    // 4. Надсилання даних назад до ClockDesk
                    val responseIntent = Intent(ACTION_UPDATE_DATA).apply {
                        setPackage(CLOCKDESK_PACKAGE)
                        putExtra("chip_visible", isVisible)
                        putExtra("plugin_package_name", context.packageName)
                        putExtra("chip_text", chipText)
                        putExtra("chip_icon_name", iconName)
                        putExtra("chip_click_activity", ".BlackoutDetailsActivity")
                        putExtra("update_interval_seconds", UPDATE_INTERVAL_SEC)
                    }

                    context.sendBroadcast(responseIntent)
                    Log.d("BlackoutChip", "Sent data: $chipText (visible=$isVisible)")

                } catch (e: Exception) {
                    Log.e("BlackoutChip", "Error fetching plugin data", e)
                    val errorIntent = Intent(ACTION_UPDATE_DATA).apply {
                        setPackage(CLOCKDESK_PACKAGE)
                        putExtra("chip_visible", true)
                        putExtra("plugin_package_name", context.packageName)
                        putExtra("chip_text", "Помилка")
                        putExtra("chip_icon_name", "alert_circle_outline")
                        putExtra("chip_click_activity", ".BlackoutDetailsActivity")
                        putExtra("update_interval_seconds", UPDATE_INTERVAL_SEC)
                    }
                    context.sendBroadcast(errorIntent)
                } finally {
                    // Обов'язково завершуємо Broadcast, інакше система покарає ANR
                    pendingResult.finish()
                }
            }
        }
    }
}