package com.anythingwithawire.bsdetector

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.animation.doOnEnd
import androidx.lifecycle.lifecycleScope
import com.anythingwithawire.core.ApiResult
import com.anythingwithawire.core.Basis
import com.anythingwithawire.core.BasisPicker
import com.anythingwithawire.core.Listener
import com.anythingwithawire.core.Settings
import com.anythingwithawire.core.callClaude
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var settings: DetectorSettings
    private lateinit var basisPicker: BasisPicker
    private lateinit var sensitivityLabel: TextView
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var flashOverlay: TextView

    private var listener: Listener? = null
    private var detector: Detector? = null
    private var requestInFlight = false
    private var pendingBasis: Basis? = null

    /** Speech heard while a request was running; sent as soon as that request finishes, then discarded. */
    private val pending = StringBuilder()

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val basis = pendingBasis
        pendingBasis = null
        if (!granted) statusText.text = "Microphone permission is needed to listen." else if (basis != null) startSession()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = DetectorSettings(this)

        basisPicker = BasisPicker(findViewById<RadioGroup>(R.id.basisGroup))
        sensitivityLabel = findViewById(R.id.sensitivityLabel)
        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)
        flashOverlay = findViewById(R.id.flashOverlay)

        val slider = findViewById<SeekBar>(R.id.sensitivitySlider)
        slider.progress = settings.sensitivity
        showSensitivity(settings.sensitivity)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                settings.sensitivity = progress
                showSensitivity(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        statusText.text = "Idle"
        toggleButton.setOnClickListener { if (listener == null) requestStart() else stopSession() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SETTINGS, 0, R.string.settings)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SETTINGS -> { showSettings(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    private fun showSensitivity(sensitivity: Int) {
        sensitivityLabel.text = "Sensitivity $sensitivity: flashes at a score of ${DetectorSettings.threshold(sensitivity)} or more out of 100"
    }

    private fun requestStart() {
        val basis = basisPicker.selected
        if (basis == null) {
            statusText.text = BasisPicker.NONE_CHOSEN
            return
        }
        if (settings.apiKey.isBlank()) {
            statusText.text = "Set your Claude API key in Settings first."
            showSettings()
            return
        }
        basisPicker.confirmThen(this, basis) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startSession()
            } else {
                pendingBasis = basis
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startSession() {
        detector = Detector(settings.apiKey, settings.model)
        val newListener = Listener(
            context = this,
            language = settings.language,
            onPartial = {},
            onFinal = { onUtterance(it) },
            onStatus = { statusText.text = it },
            onFatal = { message ->
                stopSession()
                statusText.text = message
            },
        )
        listener = newListener
        newListener.start()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        basisPicker.setEnabled(false)
        toggleButton.setText(R.string.stop)
    }

    private fun stopSession() {
        if (listener == null) return
        listener?.stop()
        listener = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        basisPicker.setEnabled(true)
        basisPicker.clear()
        toggleButton.setText(R.string.start)
        statusText.text = "Stopped"
    }

    private fun onUtterance(text: String) {
        pending.append(text).append('\n')
        send()
    }

    /** Sends whatever has been heard, unless a request is already running (it is sent when that one finishes). */
    private fun send() {
        val activeDetector = detector ?: return
        if (pending.isEmpty() || requestInFlight) return
        val utterance = pending.toString()
        pending.setLength(0)
        requestInFlight = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { callClaude { activeDetector.score(utterance) } }
            requestInFlight = false
            when (result) {
                is ApiResult.Ok -> when (val verdict = result.value) {
                    is Verdict.Score -> {
                        val threshold = DetectorSettings.threshold(settings.sensitivity)
                        statusText.text = "Last score ${verdict.score} (flashes at $threshold)"
                        if (verdict.score >= threshold) flash()
                    }
                    is Verdict.Declined -> statusText.text = verdict.reason
                }
                is ApiResult.Failed -> {
                    statusText.text = "${result.message} That speech was discarded unscored."
                    if (result.fatal) stopSession()
                }
            }
            send()
        }
    }

    /** Red screen with BULLSHIT, blinking three times over about 1.5 seconds. */
    private fun flash() {
        flashOverlay.visibility = View.VISIBLE
        ObjectAnimator.ofFloat(flashOverlay, View.ALPHA, 1f, 0.15f).apply {
            duration = 250
            repeatCount = 5
            repeatMode = ValueAnimator.REVERSE
            doOnEnd {
                flashOverlay.alpha = 1f
                flashOverlay.visibility = View.GONE
            }
            start()
        }
    }

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val apiKeyInput = view.findViewById<EditText>(R.id.apiKeyInput)
        val modelInput = view.findViewById<EditText>(R.id.modelInput)
        val languageInput = view.findViewById<EditText>(R.id.languageInput)
        apiKeyInput.setText(settings.apiKey)
        modelInput.setText(settings.model)
        languageInput.setText(settings.language)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.apiKey = apiKeyInput.text.toString()
                settings.model = modelInput.text.toString().ifBlank { Settings.DEFAULT_MODEL }
                settings.language = languageInput.text.toString().ifBlank { Settings.DEFAULT_LANGUAGE }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val MENU_SETTINGS = 1
    }
}
