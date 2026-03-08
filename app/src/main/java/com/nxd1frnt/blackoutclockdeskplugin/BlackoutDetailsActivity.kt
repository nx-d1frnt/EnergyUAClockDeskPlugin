package com.nxd1frnt.blackoutclockdeskplugin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BlackoutDetailsActivity : AppCompatActivity() {

    private lateinit var tvResult: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var scheduler: OutageScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())

        val surfaceColor = getColor(R.color.md_theme_surface)

        window.sharedElementEnterTransition = MaterialContainerTransform().apply {
            addTarget(R.id.dialog_card)
            duration = 400L
            scrimColor = android.graphics.Color.TRANSPARENT
            setAllContainerColors(surfaceColor)
            containerColor = surfaceColor
            startContainerColor = surfaceColor
            endContainerColor = surfaceColor
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }

        window.sharedElementReturnTransition = MaterialContainerTransform().apply {
            addTarget(R.id.dialog_card)
            duration = 300L
            scrimColor = android.graphics.Color.TRANSPARENT
            setAllContainerColors(surfaceColor)
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }

        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        setContentView(R.layout.activity_blackout_details)

        scheduler = OutageScheduler(this)

        tvResult = findViewById(R.id.tvResult)
        btnRefresh = findViewById(R.id.btnRefresh)
        progressBar = findViewById(R.id.progressBar)

        showCachedSchedule()

        btnRefresh.setOnClickListener {
            refreshSchedule()
        }

        val rootScrim = findViewById<View>(R.id.root_scrim)
        rootScrim.setOnClickListener {
            finishAfterTransition()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh display in case URL was changed in SettingsActivity
        showCachedSchedule()
    }

    private fun showCachedSchedule() {
        lifecycleScope.launch {
            val cache = scheduler.getCachedSchedule()
            if (cache != null) {
                displayCache(cache)
            } else {
                refreshSchedule()
            }
        }
    }

    private fun displayCache(cache: OutageCache) {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val lastUpdated = dateFormat.format(Date(cache.timestamp))

        sb.append(getString(R.string.last_updated, lastUpdated)).append("\n\n")

        if (cache.intervalsToday.isNotEmpty()) {
            sb.append(getString(R.string.schedule_today)).append("\n")
            sb.append("---------------------\n")
            cache.intervalsToday.forEach {
                sb.append("🕒 ${it.start} - ${it.end} (${it.duration})\n")
            }
            sb.append("\n")
        }

        if (!cache.intervalsTomorrow.isNullOrEmpty()) {
            sb.append(getString(R.string.schedule_tomorrow)).append("\n")
            sb.append("---------------------\n")
            cache.intervalsTomorrow.forEach {
                sb.append("🕒 ${it.start} - ${it.end} (${it.duration})\n")
            }
        } else {
            sb.append(getString(R.string.no_tomorrow_schedule))
        }

        tvResult.text = sb.toString()
    }

    private fun refreshSchedule() {
        progressBar.visibility = View.VISIBLE
        btnRefresh.isEnabled = false

        lifecycleScope.launch {
            try {
                val cachedData = scheduler.fetchScheduleFromWeb()
                if (cachedData != null && cachedData.intervalsToday.isNotEmpty()) {
                    displayCache(cachedData)
                } else {
                    tvResult.text = getString(R.string.no_data_check_settings)
                }
            } catch (e: Exception) {
                tvResult.text = getString(R.string.error_prefix, e.message ?: "Unknown error")
            } finally {
                progressBar.visibility = View.GONE
                btnRefresh.isEnabled = true
            }
        }
    }
}
