package com.nxd1frnt.blackoutclockdeskplugin

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {

    private lateinit var rgDataSource: RadioGroup
    private lateinit var rbSourceEnergyUa: RadioButton
    private lateinit var rbSourcePoe: RadioButton
    private lateinit var layoutEnergyUaUrl: LinearLayout
    private lateinit var layoutPoeQueue: LinearLayout
    private lateinit var spinnerPoeQueue: Spinner
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
        rgDataSource = findViewById(R.id.rgDataSource)
        rbSourceEnergyUa = findViewById(R.id.rbSourceEnergyUa)
        rbSourcePoe = findViewById(R.id.rbSourcePoe)
        layoutEnergyUaUrl = findViewById(R.id.layoutEnergyUaUrl)
        layoutPoeQueue = findViewById(R.id.layoutPoeQueue)
        spinnerPoeQueue = findViewById(R.id.spinnerPoeQueue)
        etUrl = findViewById(R.id.etUrl)

        val queueLabels = PoeScheduleClient.ALL_QUEUES.map { "Черга $it" }
        spinnerPoeQueue.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            queueLabels
        )

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
        rgDataSource.setOnCheckedChangeListener { _, checkedId ->
            val isPoe = checkedId == R.id.rbSourcePoe
            layoutEnergyUaUrl.visibility = if (isPoe) View.GONE else View.VISIBLE
            layoutPoeQueue.visibility = if (isPoe) View.VISIBLE else View.GONE
        }

        rgFormat.setOnCheckedChangeListener { _, checkedId ->
            tilCustomTemplate.visibility = if (checkedId == R.id.rbCustom) View.VISIBLE else View.GONE
        }

        rgVisibility.setOnCheckedChangeListener { _, checkedId ->
            layoutWarningMinutes.visibility = if (checkedId == R.id.rbVisOnlyOutage) View.VISIBLE else View.GONE
        }

        btnSave.setOnClickListener {
            val isPoe = rgDataSource.checkedRadioButtonId == R.id.rbSourcePoe
            val newUrl = etUrl.text.toString().trim()
            if (isPoe || (newUrl.isNotEmpty() && (newUrl.contains("energy-ua.info") || newUrl.contains("http")))) {
                val selectedQueue = PoeScheduleClient.ALL_QUEUES.getOrElse(spinnerPoeQueue.selectedItemPosition) {
                    OutageScheduler.DEFAULT_POE_QUEUE
                }
                val source = if (isPoe) OutageScheduler.SOURCE_POE else OutageScheduler.SOURCE_ENERGY_UA
                saveSettings(source, newUrl.ifEmpty { OutageScheduler.DEFAULT_URL }, selectedQueue)
                finish()
            } else {
                Toast.makeText(this, getString(R.string.error_invalid_url), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(OutageScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        val dataSource = prefs.getString(OutageScheduler.KEY_DATA_SOURCE, OutageScheduler.SOURCE_ENERGY_UA)
        val savedUrl = prefs.getString(OutageScheduler.KEY_URL, OutageScheduler.DEFAULT_URL)
        val savedQueue = prefs.getString(OutageScheduler.KEY_POE_QUEUE, OutageScheduler.DEFAULT_POE_QUEUE)

        etUrl.setText(savedUrl)
        val queueIndex = PoeScheduleClient.ALL_QUEUES.indexOf(savedQueue).coerceAtLeast(0)
        spinnerPoeQueue.setSelection(queueIndex)

        if (dataSource == OutageScheduler.SOURCE_POE) {
            rbSourcePoe.isChecked = true
            layoutEnergyUaUrl.visibility = View.GONE
            layoutPoeQueue.visibility = View.VISIBLE
        } else {
            rbSourceEnergyUa.isChecked = true
            layoutEnergyUaUrl.visibility = View.VISIBLE
            layoutPoeQueue.visibility = View.GONE
        }

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

    private fun saveSettings(dataSource: String, url: String, poeQueue: String) {
        val prefs = getSharedPreferences(OutageScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        val oldSource = prefs.getString(OutageScheduler.KEY_DATA_SOURCE, OutageScheduler.SOURCE_ENERGY_UA)
        val oldUrl = prefs.getString(OutageScheduler.KEY_URL, "")
        val oldQueue = prefs.getString(OutageScheduler.KEY_POE_QUEUE, OutageScheduler.DEFAULT_POE_QUEUE)

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
            putString(OutageScheduler.KEY_DATA_SOURCE, dataSource)
            putString(OutageScheduler.KEY_URL, url)
            putString(OutageScheduler.KEY_POE_QUEUE, poeQueue)
            putString(ChipFormatter.KEY_FORMAT_TYPE, selectedFormat)
            putString(ChipFormatter.KEY_CUSTOM_TEMPLATE, customTemplate)
            putString(ChipVisibilityManager.KEY_VISIBILITY_MODE, selectedVisibility)
            putInt(ChipVisibilityManager.KEY_WARNING_MINUTES, selectedWarningMinutes)
        }

        if (oldSource != dataSource || oldUrl != url || oldQueue != poeQueue) {
            OutageScheduler(this).clearCache()
        }

        // Негайне оновлення віджета в ClockDesk
        ExampleChipReceiver.triggerImmediateUpdate(this)

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }
}
