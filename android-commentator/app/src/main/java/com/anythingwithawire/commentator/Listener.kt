package com.anythingwithawire.commentator

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Continuous on-device speech-to-text on top of Android's SpeechRecognizer.
 *
 * Only the on-device recogniser is used, so audio never leaves the phone. If the
 * phone cannot recognise on-device, the listener refuses to start rather than
 * falling back to a cloud recognition service.
 *
 * SpeechRecognizer stops after each utterance, so every result or benign
 * error restarts it. Errors that will not clear by themselves go to onFatal
 * and stop the listener; nothing is retried silently.
 * Must be created and driven from the main thread.
 */
class Listener(
    private val context: Context,
    private val language: String,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFatal: (String) -> Unit,
) : RecognitionListener {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onFatal("On-device speech recognition needs Android 13 or later. This phone runs Android ${Build.VERSION.RELEASE}, so the app will not listen.")
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onFatal("This phone has no on-device speech recognition, so the app will not listen. " +
                "Installing or updating Speech Recognition and Synthesis by Google may add it.")
            return
        }
        val created = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        created.setRecognitionListener(this)
        recognizer = created
        active = true
        listen()
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        if (!active) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        recognizer!!.startListening(intent)
    }

    private fun restartAfter(delayMs: Long) {
        if (!active) return
        handler.postDelayed({ listen() }, delayMs)
    }

    override fun onReadyForSpeech(params: Bundle?) {
        onStatus("Listening (on-device)")
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) onPartial(text)
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) onFinal(text)
        onPartial("")
        restartAfter(0)
    }

    override fun onError(error: Int) {
        if (!active) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartAfter(0)

            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                onStatus("Recogniser busy, restarting")
                restartAfter(500)
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                stop()
                onFatal("Microphone permission was refused.")
            }

            else -> {
                stop()
                onFatal("On-device speech recognition failed with error code $error. " +
                    "Codes 12 and 13 mean the $language language pack is not installed for on-device recognition: " +
                    "install it in the phone's language settings, or pick an installed language in Settings.")
            }
        }
    }

    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
