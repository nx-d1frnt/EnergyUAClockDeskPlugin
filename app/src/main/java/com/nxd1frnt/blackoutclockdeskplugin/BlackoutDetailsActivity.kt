package com.nxd1frnt.blackoutclockdeskplugin

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class BlackoutDetailsActivity : AppCompatActivity() {

    private lateinit var tvQueueBadge: TextView
    private lateinit var btnOpenBrowser: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var cardStatus: MaterialCardView
    private lateinit var ivStatusIcon: ImageView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSubtitle: TextView
    private lateinit var timelineView: TimelineView
    private lateinit var toggleDayGroup: MaterialButtonToggleGroup
    private lateinit var layoutIntervals: LinearLayout
    private lateinit var tvEmptyState: TextView
    private lateinit var tvLastUpdated: TextView
    private lateinit var btnRefresh: Button
    private lateinit var progressBar: ProgressBar

    private lateinit var scheduler: OutageScheduler
    private var cachedSchedule: OutageCache? = null
    private var selectedIsToday: Boolean = true

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    override fun onCreate(savedInstanceState: Bundle?) {
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())

        val surfaceColor = getColor(R.color.md_theme_surface)

        window.sharedElementEnterTransition = MaterialContainerTransform().apply {
            addTarget(R.id.dialog_card)
            duration = 400L
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(surfaceColor)
            containerColor = surfaceColor
            startContainerColor = surfaceColor
            endContainerColor = surfaceColor
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }

        window.sharedElementReturnTransition = MaterialContainerTransform().apply {
            addTarget(R.id.dialog_card)
            duration = 300L
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(surfaceColor)
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }

        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Сучасний immersive fullscreen режим
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContentView(R.layout.activity_blackout_details)

        scheduler = OutageScheduler(this)

        initViews()
        setupListeners()
        showCachedSchedule()
    }

    override fun onResume() {
        super.onResume()
        // Оновлюємо стан при поверненні (наприклад, якщо змінили URL в налаштуваннях)
        showCachedSchedule()
    }

    private fun initViews() {
        tvQueueBadge = findViewById(R.id.tvQueueBadge)
        btnOpenBrowser = findViewById(R.id.btnOpenBrowser)
        btnSettings = findViewById(R.id.btnSettings)
        cardStatus = findViewById(R.id.cardStatus)
        ivStatusIcon = findViewById(R.id.ivStatusIcon)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusSubtitle = findViewById(R.id.tvStatusSubtitle)
        timelineView = findViewById(R.id.timelineView)
        toggleDayGroup = findViewById(R.id.toggleDayGroup)
        layoutIntervals = findViewById(R.id.layoutIntervals)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        tvLastUpdated = findViewById(R.id.tvLastUpdated)
        btnRefresh = findViewById(R.id.btnRefresh)
        progressBar = findViewById(R.id.progressBar)

        toggleDayGroup.check(R.id.btnTabToday)
    }

    private fun setupListeners() {
        findViewById<View>(R.id.root_scrim).setOnClickListener {
            finishAfterTransition()
        }

        btnRefresh.setOnClickListener {
            refreshSchedule()
        }

        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        btnOpenBrowser.setOnClickListener {
            val prefs = getSharedPreferences(OutageScheduler.PREFS_NAME, Context.MODE_PRIVATE)
            val url = prefs.getString(OutageScheduler.KEY_URL, OutageScheduler.DEFAULT_URL) ?: OutageScheduler.DEFAULT_URL
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(browserIntent)
            } catch (e: Exception) {
                // Ignore if no browser
            }
        }

        toggleDayGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                selectedIsToday = (checkedId == R.id.btnTabToday)
                renderDaySchedule()
            }
        }
    }

    private fun showCachedSchedule() {
        lifecycleScope.launch {
            val cache = scheduler.getCachedSchedule()
            if (cache != null) {
                cachedSchedule = cache
                displayCache(cache)
            } else {
                refreshSchedule()
            }
        }
    }

    private fun displayCache(cache: OutageCache) {
        cachedSchedule = cache

        // 1. Черга та час оновлення
        val prefs = getSharedPreferences(OutageScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString(OutageScheduler.KEY_URL, OutageScheduler.DEFAULT_URL) ?: OutageScheduler.DEFAULT_URL
        val queuePart = url.substringAfterLast("/cherga/", "").replace("-", ".")
        tvQueueBadge.text = if (queuePart.isNotEmpty()) {
            getString(R.string.queue_label, queuePart)
        } else {
            getString(R.string.app_name)
        }

        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        tvLastUpdated.text = getString(R.string.last_updated, dateFormat.format(Date(cache.timestamp)))

        // 2. Статус-банер
        val info = scheduler.getCountdownInfo(cache)
        updateStatusBanner(info)

        // 3. Відображення вибраного дня (сьогодні/завтра)
        renderDaySchedule()
    }

    private fun updateStatusBanner(info: CountdownInfo) {
        when (info.status) {
            "СВІТЛО Є" -> {
                ivStatusIcon.setImageResource(R.drawable.lightbulb_outline)
                ivStatusIcon.setColorFilter(Color.parseColor("#388E3C"))
                tvStatusTitle.text = getString(R.string.status_power_on)
                tvStatusTitle.setTextColor(Color.parseColor("#2E7D32"))
                if (info.timeToEvent == "—") {
                    tvStatusSubtitle.text = getString(R.string.empty_today_outages)
                } else {
                    tvStatusSubtitle.text = getString(R.string.status_until, info.nextEventTime) +
                            " (" + getString(R.string.status_countdown, info.timeToEvent) + ")"
                }
            }
            "ВИМКНЕННЯ" -> {
                ivStatusIcon.setImageResource(R.drawable.lightbulb_off_outline)
                ivStatusIcon.setColorFilter(Color.parseColor("#D32F2F"))
                tvStatusTitle.text = getString(R.string.status_power_off)
                tvStatusTitle.setTextColor(Color.parseColor("#C62828"))
                tvStatusSubtitle.text = getString(R.string.status_until, info.nextEventTime) +
                        " (" + getString(R.string.status_countdown, info.timeToEvent) + ")"
            }
            else -> {
                ivStatusIcon.setImageResource(R.drawable.alert_circle_outline)
                ivStatusIcon.setColorFilter(Color.parseColor("#757575"))
                tvStatusTitle.text = getString(R.string.no_data_check_settings)
                tvStatusSubtitle.text = ""
            }
        }
    }

    private fun renderDaySchedule() {
        val cache = cachedSchedule ?: return
        val rawIntervals = if (selectedIsToday) {
            cache.intervalsToday
        } else {
            cache.intervalsTomorrow ?: emptyList()
        }

        val parsedPairs = scheduler.parseIntervals(rawIntervals)
        timelineView.setData(parsedPairs, isToday = selectedIsToday)

        layoutIntervals.removeAllViews()

        if (rawIntervals.isEmpty()) {
            tvEmptyState.visibility = View.VISIBLE
            tvEmptyState.text = if (selectedIsToday) {
                getString(R.string.empty_today_outages)
            } else {
                getString(R.string.empty_tomorrow_outages)
            }
            return
        }

        tvEmptyState.visibility = View.GONE
        val now = LocalTime.now()
        val inflater = LayoutInflater.from(this)

        for (interval in rawIntervals) {
            val itemView = inflater.inflate(R.layout.item_outage_interval, layoutIntervals, false)
            val cardView = itemView.findViewById<MaterialCardView>(R.id.cardInterval)
            val tvIntervalTime = itemView.findViewById<TextView>(R.id.tvIntervalTime)
            val tvIntervalDuration = itemView.findViewById<TextView>(R.id.tvIntervalDuration)
            val tvIntervalStatus = itemView.findViewById<TextView>(R.id.tvIntervalStatus)

            tvIntervalTime.text = "${interval.start} — ${interval.end}"
            val durationText = if (interval.duration.contains("год")) interval.duration else "${interval.duration} год."
            tvIntervalDuration.text = durationText

            // Визначення статусу інтервалу
            if (selectedIsToday) {
                try {
                    val startStr = if (interval.start.trim() == "24:00") "23:59" else interval.start.trim()
                    val endStr = if (interval.end.trim() == "24:00") "23:59" else interval.end.trim()
                    val start = LocalTime.parse(startStr.padStart(5, '0'), timeFormatter)
                    val end = LocalTime.parse(endStr.padStart(5, '0'), timeFormatter)

                    val isActive = if (end.isBefore(start)) {
                        !now.isBefore(start) || now.isBefore(end)
                    } else {
                        !now.isBefore(start) && now.isBefore(end)
                    }

                    val isPast = if (end.isBefore(start)) false else now.isAfter(end)

                    when {
                        isActive -> {
                            tvIntervalStatus.text = getString(R.string.interval_status_active)
                            tvIntervalStatus.setTextColor(Color.parseColor("#C62828"))
                            cardView.strokeWidth = 2
                            cardView.strokeColor = Color.parseColor("#EF5350")
                        }
                        isPast -> {
                            tvIntervalStatus.text = getString(R.string.interval_status_passed)
                            tvIntervalStatus.setTextColor(Color.parseColor("#9E9E9E"))
                            cardView.alpha = 0.5f
                        }
                        else -> {
                            tvIntervalStatus.text = getString(R.string.interval_status_upcoming)
                            tvIntervalStatus.setTextColor(Color.parseColor("#1565C0"))
                        }
                    }
                } catch (e: Exception) {
                    tvIntervalStatus.text = getString(R.string.interval_status_upcoming)
                    tvIntervalStatus.setTextColor(Color.parseColor("#1565C0"))
                }
            } else {
                tvIntervalStatus.text = getString(R.string.interval_status_upcoming)
                tvIntervalStatus.setTextColor(Color.parseColor("#1565C0"))
            }

            layoutIntervals.addView(itemView)
        }
    }

    private fun refreshSchedule() {
        progressBar.visibility = View.VISIBLE
        btnRefresh.isEnabled = false

        lifecycleScope.launch {
            try {
                val cachedData = scheduler.fetchScheduleFromWeb()
                if (cachedData != null) {
                    displayCache(cachedData)
                } else {
                    tvEmptyState.visibility = View.VISIBLE
                    tvEmptyState.text = getString(R.string.no_data_check_settings)
                }
            } catch (e: Exception) {
                tvEmptyState.visibility = View.VISIBLE
                tvEmptyState.text = getString(R.string.error_prefix, e.message ?: "Unknown error")
            } finally {
                progressBar.visibility = View.GONE
                btnRefresh.isEnabled = true
            }
        }
    }
}
