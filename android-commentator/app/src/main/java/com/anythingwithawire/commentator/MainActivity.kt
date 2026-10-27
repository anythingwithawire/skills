package com.anythingwithawire.commentator

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings
    private lateinit var contextInput: EditText
    private lateinit var consentCheck: CheckBox
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var partialText: TextView
    private lateinit var commentList: LinearLayout
    private lateinit var commentScroll: ScrollView

    private var listener: Listener? = null
    private var commentator: Commentator? = null
    private var ticker: Job? = null
    private var requestInFlight = false

    /** Whole session (transcript and comments), kept in memory only; written nowhere unless the user shares it. */
    private val sessionLog = StringBuilder()

    /** Transcript lines not yet sent to Claude. */
    private val pending = StringBuilder()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.UK)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSession() else showError("Microphone permission is needed to listen.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        contextInput = findViewById(R.id.contextInput)
        consentCheck = findViewById(R.id.consentCheck)
        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)
        partialText = findViewById(R.id.partialText)
        commentList = findViewById(R.id.commentList)
        commentScroll = findViewById(R.id.commentScroll)

        contextInput.setText(settings.lastContext)
        statusText.text = "Idle"
        toggleButton.setOnClickListener { if (listener == null) requestStart() else stopSession() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SETTINGS, 0, R.string.settings)
        menu.add(0, MENU_SHARE, 1, R.string.share)
        menu.add(0, MENU_CLEAR, 2, R.string.clear)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SETTINGS -> { showSettings(); true }
        MENU_SHARE -> { shareSession(); true }
        MENU_CLEAR -> { clearSession(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    private fun requestStart() {
        if (!consentCheck.isChecked) {
            showError("Confirm consent before listening. Recording or monitoring a private conversation without it " +
                "can be an offence under the Surveillance Devices Act 1998 (WA) s 5.")
            return
        }
        if (settings.apiKey.isBlank()) {
            showError("Set your Claude API key in Settings first.")
            showSettings()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startSession()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startSession() {
        val context = contextInput.text.toString().trim()
        settings.lastContext = context
        commentator = Commentator(settings.apiKey, settings.model, context, settings.jurisdiction)
        sessionLog.append("=== Session started ${Date()} ===\nContext: ${context.ifBlank { "(none)" }}\nModel: ${settings.model}\n\n")

        val newListener = Listener(
            context = this,
            preferOnDevice = settings.preferOnDevice,
            onPartial = { partialText.text = it },
            onFinal = { onUtterance(it) },
            onStatus = { statusText.text = it },
            onFatal = { message ->
                showError(message)
                stopSession()
            },
        )
        listener = newListener
        newListener.start()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        contextInput.isEnabled = false
        consentCheck.isEnabled = false
        toggleButton.setText(R.string.stop)

        val intervalMs = settings.intervalSeconds.coerceAtLeast(Settings.MIN_INTERVAL_SECONDS) * 1000L
        ticker = lifecycleScope.launch {
            while (isActive) {
                delay(intervalMs)
                flush()
            }
        }
    }

    private fun stopSession() {
        if (listener == null) return
        listener?.stop()
        listener = null
        ticker?.cancel()
        ticker = null
        flush()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        contextInput.isEnabled = true
        consentCheck.isEnabled = true
        consentCheck.isChecked = false
        toggleButton.setText(R.string.start)
        partialText.text = ""
        statusText.text = "Stopped"
    }

    private fun onUtterance(text: String) {
        val line = "[${timeFormat.format(Date())}] $text\n"
        sessionLog.append(line)
        pending.append(line)
    }

    /** Sends pending transcript to Claude, unless a request is already running (then it waits for the next tick). */
    private fun flush() {
        val activeCommentator = commentator ?: return
        if (pending.isEmpty() || requestInFlight) return
        val chunk = pending.toString()
        pending.setLength(0)
        requestInFlight = true
        statusText.text = "Asking Claude..."

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatchingApi { activeCommentator.comment(chunk) } }
            requestInFlight = false
            if (listener != null) statusText.text = "Listening"
            when (result) {
                is ApiResult.Ok -> when (val outcome = result.outcome) {
                    is Outcome.Comments -> outcome.comments.forEach { addComment(it) }
                    is Outcome.Declined -> showError(outcome.reason)
                }
                is ApiResult.Failed -> {
                    showError(result.message)
                    if (result.fatal) stopSession()
                }
            }
            // Speech that arrived after Stop while this request was running still gets its commentary.
            if (listener == null) flush()
        }
    }

    private sealed class ApiResult {
        data class Ok(val outcome: Outcome) : ApiResult()
        data class Failed(val message: String, val fatal: Boolean) : ApiResult()
    }

    /**
     * Turns API and parsing failures into a visible error card. The chunk that failed
     * stays in the conversation history, so Claude still sees it with the next chunk.
     */
    private inline fun runCatchingApi(block: () -> Outcome): ApiResult = try {
        ApiResult.Ok(block())
    } catch (e: UnauthorizedException) {
        ApiResult.Failed("Claude API key was rejected (401). Check it in Settings.", fatal = true)
    } catch (e: AnthropicServiceException) {
        ApiResult.Failed("Claude API error ${e.statusCode()}: ${e.message}", fatal = false)
    } catch (e: AnthropicException) {
        ApiResult.Failed("Could not reach Claude: ${e.message}", fatal = false)
    } catch (e: JSONException) {
        ApiResult.Failed("Claude's reply was not in the expected format: ${e.message}", fatal = false)
    }

    private fun addComment(comment: Comment) {
        val (label, colour) = when (comment.kind) {
            Kind.QUESTION -> "❓ Question" to R.color.question
            Kind.DOUBT -> "⚠️ Doubt" to R.color.doubt
            Kind.INFO -> "➕ Info" to R.color.info
            Kind.CORRECTION -> "✏️ Correction" to R.color.correction
        }
        val time = timeFormat.format(Date())
        sessionLog.append("[$time] $label: \"${comment.quote}\": ${comment.text}\n")
        addCard("$label  ·  $time", "“${comment.quote}”", comment.text, colour)
    }

    private fun showError(message: String) {
        sessionLog.append("[${timeFormat.format(Date())}] Error: $message\n")
        addCard("Error  ·  ${timeFormat.format(Date())}", null, message, R.color.error)
    }

    /** Newest card goes on top so it is visible without scrolling. */
    private fun addCard(header: String, quote: String?, body: String, colourRes: Int) {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(getColor(R.color.card))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = pad / 2 }
        }
        card.addView(TextView(this).apply {
            text = header
            setTextColor(getColor(colourRes))
            setTypeface(typeface, Typeface.BOLD)
        })
        if (quote != null) {
            card.addView(TextView(this).apply {
                text = quote
                setTextColor(getColor(R.color.muted))
                setTypeface(typeface, Typeface.ITALIC)
            })
        }
        card.addView(TextView(this).apply {
            text = body
            textSize = 16f
        })
        commentList.addView(card, 0)
        commentScroll.scrollTo(0, 0)
    }

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val apiKeyInput = view.findViewById<EditText>(R.id.apiKeyInput)
        val modelInput = view.findViewById<EditText>(R.id.modelInput)
        val intervalInput = view.findViewById<EditText>(R.id.intervalInput)
        val jurisdictionInput = view.findViewById<EditText>(R.id.jurisdictionInput)
        val onDeviceCheck = view.findViewById<CheckBox>(R.id.onDeviceCheck)

        apiKeyInput.setText(settings.apiKey)
        modelInput.setText(settings.model)
        intervalInput.setText(settings.intervalSeconds.toString())
        jurisdictionInput.setText(settings.jurisdiction)
        onDeviceCheck.isChecked = settings.preferOnDevice

        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.apiKey = apiKeyInput.text.toString()
                settings.model = modelInput.text.toString().ifBlank { Settings.DEFAULT_MODEL }
                val interval = intervalInput.text.toString().toIntOrNull()
                if (interval == null || interval < Settings.MIN_INTERVAL_SECONDS) {
                    showError("Interval must be a whole number of at least ${Settings.MIN_INTERVAL_SECONDS} seconds; it was not changed.")
                } else {
                    settings.intervalSeconds = interval
                }
                settings.jurisdiction = jurisdictionInput.text.toString().ifBlank { Settings.DEFAULT_JURISDICTION }
                settings.preferOnDevice = onDeviceCheck.isChecked
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareSession() {
        if (sessionLog.isEmpty()) {
            showError("Nothing to share yet.")
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Commentator session")
            putExtra(Intent.EXTRA_TEXT, sessionLog.toString())
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun clearSession() {
        if (listener != null) {
            showError("Stop listening before clearing the session.")
            return
        }
        sessionLog.setLength(0)
        pending.setLength(0)
        commentator = null
        commentList.removeAllViews()
        statusText.text = "Idle"
    }

    companion object {
        private const val MENU_SETTINGS = 1
        private const val MENU_SHARE = 2
        private const val MENU_CLEAR = 3
    }
}
