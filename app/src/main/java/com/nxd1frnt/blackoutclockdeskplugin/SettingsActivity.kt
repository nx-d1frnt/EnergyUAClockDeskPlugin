package com.nxd1frnt.blackoutclockdeskplugin

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var etUrl: TextInputEditText
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blackout_settings)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        etUrl = findViewById(R.id.etUrl)
        btnSave = findViewById(R.id.btnSave)

        loadUrl()

        btnSave.setOnClickListener {
            val newUrl = etUrl.text.toString().trim()
            if (newUrl.isNotEmpty() && (newUrl.contains("energy-ua.info") || newUrl.contains("http"))) {
                saveUrl(newUrl)
                finish()
            } else {
                Toast.makeText(this, getString(R.string.error_invalid_url), Toast.LENGTH_SHORT).show()
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
        prefs.edit {
            putString(OutageScheduler.KEY_URL, url)
        }
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }
}
