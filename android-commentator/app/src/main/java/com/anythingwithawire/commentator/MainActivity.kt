package com.anythingwithawire.commentator

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private lateinit var basisGroup: RadioGroup
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var partialText: TextView
    private lateinit var commentList: LinearLayout
    private lateinit var commentScroll: ScrollView

    private var listener: Listener? = null
    private var commentator: Commentator? = null
    private var ticker: Job? = null
    private var requestInFlight = false
    private var sessionEphemeral = true

    /**
     * Session log, kept in memory only and written nowhere unless the user shares it.
     * Holds comments always, and the transcript only when ephemeral mode is off.
     */
    private val sessionLog = StringBuilder()

    /** Transcript lines not yet sent to Claude. */
    private val pending = StringBuilder()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.UK)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val basis = pendingBasis
        pendingBasis = null
        if (!granted) showError("Microphone permission is needed to listen.") else if (basis != null) startSession(basis)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        contextInput = findViewById(R.id.contextInput)
        basisGroup = findViewById(R.id.basisGroup)
        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)
        partialText = findViewById(R.id.partialText)
        commentList = findViewById(R.id.commentList)
        commentScroll = findViewById(R.id.commentScroll)

        for (basis in Basis.entries) {
            basisGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = basis
                text = basis.label
                textSize = 13f
            })
        }

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
        val checked = basisGroup.checkedRadioButtonId
        if (checked == View.NO_ID) {
            showError("Choose the basis for listening first. Recording a private conversation, or communicating one, " +
                "without an exception can be an offence under the Surveillance Devices Act 1998 (WA) ss 5 and 9.")
            return
        }
        val basis = basisGroup.findViewById<RadioButton>(checked).tag as Basis
        if (settings.apiKey.isBlank()) {
            showError("Set your Claude API key in Settings first.")
            showSettings()
            return
        }
        if (basis == Basis.LAWFUL_INTERESTS) {
            AlertDialog.Builder(this)
                .setTitle("Lawful interests: a narrow exception")
                .setMessage("Surveillance Devices Act 1998 (WA) s 5(3)(d) needs you to be a principal party and the use to be " +
                    "\"reasonably necessary for the protection of the lawful interests\" of that party. s 9(2)(a)(vi) is the " +
                    "matching exception for communicating the conversation. You carry the burden of justifying it. Continue?")
                .setPositiveButton("Continue") { _, _ -> checkMicAndStart(basis) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            checkMicAndStart(basis)
        }
    }

    private var pendingBasis: Basis? = null

    private fun checkMicAndStart(basis: Basis) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startSession(basis)
        } else {
            pendingBasis = basis
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startSession(basis: Basis) {
        val context = contextInput.text.toString().trim()
        settings.lastContext = context
        sessionEphemeral = settings.ephemeral
        commentator = Commentator(settings.apiKey, settings.model, context, settings.jurisdiction, basis, sessionEphemeral)
        sessionLog.append(
            "=== Session started ${Date()} ===\n" +
                "Basis: ${basis.label}\n" +
                "Mode: ${if (sessionEphemeral) "ephemeral (transcript discarded once sent; comments only)" else "full transcript"}\n" +
                "Context: ${context.ifBlank { "(none)" }}\n" +
                "Model: ${settings.model}\n\n"
        )

        val newListener = Listener(
            context = this,
            language = settings.language,
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
        setBasisEnabled(false)
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
        setBasisEnabled(true)
        basisGroup.clearCheck()
        toggleButton.setText(R.string.start)
        partialText.text = ""
        statusText.text = "Stopped"
    }

    private fun onUtterance(text: String) {
        val line = "[${timeFormat.format(Date())}] $text\n"
        if (!sessionEphemeral) sessionLog.append(line)
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
                    showError(if (sessionEphemeral) "${result.message} That speech was discarded without commentary." else result.message)
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
     * Turns API and parsing failures into a visible error card. In full mode the chunk that
     * failed stays in the conversation, so Claude still sees it with the next chunk; in
     * ephemeral mode it is gone, and the error card says so.
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
        val languageInput = view.findViewById<EditText>(R.id.languageInput)
        val ephemeralCheck = view.findViewById<CheckBox>(R.id.ephemeralCheck)

        apiKeyInput.setText(settings.apiKey)
        modelInput.setText(settings.model)
        intervalInput.setText(settings.intervalSeconds.toString())
        jurisdictionInput.setText(settings.jurisdiction)
        languageInput.setText(settings.language)
        ephemeralCheck.isChecked = settings.ephemeral

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
                settings.language = languageInput.text.toString().ifBlank { Settings.DEFAULT_LANGUAGE }
                settings.ephemeral = ephemeralCheck.isChecked
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

    private fun setBasisEnabled(enabled: Boolean) {
        for (i in 0 until basisGroup.childCount) basisGroup.getChildAt(i).isEnabled = enabled
    }

    companion object {
        private const val MENU_SETTINGS = 1
        private const val MENU_SHARE = 2
        private const val MENU_CLEAR = 3
    }
}
