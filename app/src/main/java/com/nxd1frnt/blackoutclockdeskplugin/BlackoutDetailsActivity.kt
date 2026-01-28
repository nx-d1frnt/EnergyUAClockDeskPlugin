package com.nxd1frnt.blackoutclockdeskplugin

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BlackoutDetailsActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var tvResult: TextView
    private lateinit var btnSave: Button
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

        etUrl = findViewById(R.id.etUrl)
        tvResult = findViewById(R.id.tvResult)
        btnSave = findViewById(R.id.btnSaveAndCheck)
        progressBar = findViewById(R.id.progressBar)

        loadUrl()
        showCachedSchedule()

        btnSave.setOnClickListener {
            val newUrl = etUrl.text.toString().trim()
            if (newUrl.isNotEmpty() && newUrl.contains("energy-ua.info")) {
                saveUrl(newUrl)
                checkSchedule()
            } else {
                Toast.makeText(this, "Введіть коректне посилання energy-ua.info", Toast.LENGTH_SHORT).show()
            }
        }
        val rootScrim = findViewById<android.view.View>(R.id.root_scrim)
        rootScrim.setOnClickListener {
            finishAfterTransition()
        }
    }

    private fun showCachedSchedule() {
        lifecycleScope.launch {
            val cache = scheduler.getCachedSchedule()
            if (cache != null && cache.intervals.isNotEmpty()) {
                val sb = StringBuilder()
                val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                val lastUpdated = dateFormat.format(Date(cache.timestamp))

                sb.append("💾 ЗБЕРЕЖЕНИЙ ГРАФІК (оновлено о $lastUpdated):\n\n")
                cache.intervals.forEach {
                    sb.append("🕒 ${it.start} - ${it.end}\n")
                    sb.append("   Тривалість: ${it.duration}\n\n")
                }
                tvResult.text = sb.toString()
            }
        }
    }
    private fun loadUrl() {
        val prefs = getSharedPreferences(OutageScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(OutageScheduler.KEY_URL, OutageScheduler.DEFAULT_URL)
        etUrl.setText(savedUrl)
    }

    private fun saveUrl(url: String) {
        val prefs = getSharedPreferences(OutageScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(OutageScheduler.KEY_URL, url).apply()
        Toast.makeText(this, "URL збережено", Toast.LENGTH_SHORT).show()
    }

    private fun checkSchedule() {
        progressBar.visibility = View.VISIBLE
        tvResult.text = "Завантаження..."
        btnSave.isEnabled = false

        lifecycleScope.launch {
            try {
                val intervals = scheduler.fetchScheduleFromWeb()

                if (intervals.isNotEmpty()) {
                    val sb = StringBuilder()
                    sb.append("✅ УСПІШНО ОТРИМАНО:\n\n")
                    intervals.forEach {
                        sb.append("🕒 ${it.start} - ${it.end}\n")
                        sb.append("   Тривалість: ${it.duration}\n\n")
                    }
                    tvResult.text = sb.toString()
                } else {
                    tvResult.text = "❌ Даних не знайдено.\nПеревірте URL або спробуйте пізніше.\nМожливо, структура сторінки відрізняється."
                }
            } catch (e: Exception) {
                tvResult.text = "❌ Помилка: ${e.message}"
            } finally {
                progressBar.visibility = View.GONE
                btnSave.isEnabled = true
            }
        }
    }
}