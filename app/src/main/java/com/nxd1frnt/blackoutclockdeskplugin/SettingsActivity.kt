package com.nxd1frnt.blackoutclockdeskplugin

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {

    private lateinit var etUrl: TextInputEditText

    // Формат тексту
    private lateinit var rgFormat: RadioGroup
    private lateinit var rbDetailed: RadioButton
    private lateinit var rbTimeOnly: RadioButton
    private lateinit var rbCountdown: RadioButton
    private lateinit var rbCompact: RadioButton
    private lateinit var rbCustom: RadioButton
    private lateinit var tilCustomTemplate: TextInputLayout
    private lateinit var etCustomTemplate: TextInputEditText

    // Видимість віджета
    private lateinit var rgVisibility: RadioGroup
    private lateinit var rbVisAlways: RadioButton
    private lateinit var rbVisHideNoOutages: RadioButton
    private lateinit var rbVisOnlyOutage: RadioButton
    private lateinit var layoutWarningMinutes: LinearLayout
    private lateinit var rgWarningMinutes: RadioGroup
    private lateinit var rbWarn0: RadioButton
    private lateinit var rbWarn15: RadioButton
    private lateinit var rbWarn30: RadioButton
    private lateinit var rbWarn60: RadioButton

    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blackout_settings)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        loadSettings()
        setupListeners()
    }

    private fun initViews() {
        etUrl = findViewById(R.id.etUrl)

        rgFormat = findViewById(R.id.rgFormat)
        rbDetailed = findViewById(R.id.rbDetailed)
        rbTimeOnly = findViewById(R.id.rbTimeOnly)
        rbCountdown = findViewById(R.id.rbCountdown)
        rbCompact = findViewById(R.id.rbCompact)
        rbCustom = findViewById(R.id.rbCustom)
        tilCustomTemplate = findViewById(R.id.tilCustomTemplate)
        etCustomTemplate = findViewById(R.id.etCustomTemplate)

        rgVisibility = findViewById(R.id.rgVisibility)
        rbVisAlways = findViewById(R.id.rbVisAlways)
        rbVisHideNoOutages = findViewById(R.id.rbVisHideNoOutages)
        rbVisOnlyOutage = findViewById(R.id.rbVisOnlyOutage)
        layoutWarningMinutes = findViewById(R.id.layoutWarningMinutes)
        rgWarningMinutes = findViewById(R.id.rgWarningMinutes)
        rbWarn0 = findViewById(R.id.rbWarn0)
        rbWarn15 = findViewById(R.id.rbWarn15)
        rbWarn30 = findViewById(R.id.rbWarn30)
        rbWarn60 = findViewById(R.id.rbWarn60)

        btnSave = findViewById(R.id.btnSave)
    }

    private fun setupListeners() {
        rgFormat.setOnCheckedChangeListener { _, checkedId ->
            tilCustomTemplate.visibility = if (checkedId == R.id.rbCustom) View.VISIBLE else View.GONE
        }

        rgVisibility.setOnCheckedChangeListener { _, checkedId ->
            layoutWarningMinutes.visibility = if (checkedId == R.id.rbVisOnlyOutage) View.VISIBLE else View.GONE
        }

        btnSave.setOnClickListener {
            val newUrl = etUrl.text.toString().trim()
            if (newUrl.isNotEmpty() && (newUrl.contains("energy-ua.info") || newUrl.contains("http"))) {
                saveSettings(newUrl)
                finish()
            } else {
                Toast.makeText(this, getString(R.string.error_invalid_url), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(OutageScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(OutageScheduler.KEY_URL, OutageScheduler.DEFAULT_URL)
        etUrl.setText(savedUrl)

        // 1. Формат тексту
        val formatType = prefs.getString(ChipFormatter.KEY_FORMAT_TYPE, ChipFormatter.FORMAT_DETAILED)
        when (formatType) {
            ChipFormatter.FORMAT_TIME_ONLY -> rbTimeOnly.isChecked = true
            ChipFormatter.FORMAT_COUNTDOWN_ONLY -> rbCountdown.isChecked = true
            ChipFormatter.FORMAT_COMPACT -> rbCompact.isChecked = true
            ChipFormatter.FORMAT_CUSTOM -> {
                rbCustom.isChecked = true
                tilCustomTemplate.visibility = View.VISIBLE
            }
            else -> rbDetailed.isChecked = true
        }

        val customTemplate = prefs.getString(ChipFormatter.KEY_CUSTOM_TEMPLATE, ChipFormatter.DEFAULT_CUSTOM_TEMPLATE)
        etCustomTemplate.setText(customTemplate)

        // 2. Видимість віджета
        val visibilityMode = prefs.getString(ChipVisibilityManager.KEY_VISIBILITY_MODE, ChipVisibilityManager.MODE_ALWAYS)
        when (visibilityMode) {
            ChipVisibilityManager.MODE_HIDE_IF_NO_OUTAGES_TODAY -> rbVisHideNoOutages.isChecked = true
            ChipVisibilityManager.MODE_SHOW_ONLY_DURING_OUTAGE -> {
                rbVisOnlyOutage.isChecked = true
                layoutWarningMinutes.visibility = View.VISIBLE
            }
            else -> rbVisAlways.isChecked = true
        }

        val warningMinutes = prefs.getInt(ChipVisibilityManager.KEY_WARNING_MINUTES, ChipVisibilityManager.DEFAULT_WARNING_MINUTES)
        when (warningMinutes) {
            0 -> rbWarn0.isChecked = true
            15 -> rbWarn15.isChecked = true
            60 -> rbWarn60.isChecked = true
            else -> rbWarn30.isChecked = true
        }
    }

    private fun saveSettings(url: String) {
        val prefs = getSharedPreferences(OutageScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        val oldUrl = prefs.getString(OutageScheduler.KEY_URL, "")

        // 1. Формат тексту
        val selectedFormat = when (rgFormat.checkedRadioButtonId) {
            R.id.rbTimeOnly -> ChipFormatter.FORMAT_TIME_ONLY
            R.id.rbCountdown -> ChipFormatter.FORMAT_COUNTDOWN_ONLY
            R.id.rbCompact -> ChipFormatter.FORMAT_COMPACT
            R.id.rbCustom -> ChipFormatter.FORMAT_CUSTOM
            else -> ChipFormatter.FORMAT_DETAILED
        }

        val customTemplate = etCustomTemplate.text.toString().trim().ifBlank {
            ChipFormatter.DEFAULT_CUSTOM_TEMPLATE
        }

        // 2. Видимість
        val selectedVisibility = when (rgVisibility.checkedRadioButtonId) {
            R.id.rbVisHideNoOutages -> ChipVisibilityManager.MODE_HIDE_IF_NO_OUTAGES_TODAY
            R.id.rbVisOnlyOutage -> ChipVisibilityManager.MODE_SHOW_ONLY_DURING_OUTAGE
            else -> ChipVisibilityManager.MODE_ALWAYS
        }

        val selectedWarningMinutes = when (rgWarningMinutes.checkedRadioButtonId) {
            R.id.rbWarn0 -> 0
            R.id.rbWarn15 -> 15
            R.id.rbWarn60 -> 60
            else -> 30
        }

        prefs.edit {
            putString(OutageScheduler.KEY_URL, url)
            putString(ChipFormatter.KEY_FORMAT_TYPE, selectedFormat)
            putString(ChipFormatter.KEY_CUSTOM_TEMPLATE, customTemplate)
            putString(ChipVisibilityManager.KEY_VISIBILITY_MODE, selectedVisibility)
            putInt(ChipVisibilityManager.KEY_WARNING_MINUTES, selectedWarningMinutes)
        }

        if (oldUrl != url) {
            OutageScheduler(this).clearCache()
        }

        // Негайне оновлення віджета в ClockDesk
        ExampleChipReceiver.triggerImmediateUpdate(this)

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }
}
