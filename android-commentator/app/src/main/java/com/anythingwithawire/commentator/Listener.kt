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
 * Continuous speech-to-text on top of Android's SpeechRecognizer.
 *
 * SpeechRecognizer stops after each utterance, so every result or benign
 * error restarts it. Errors that will not clear by themselves go to onFatal
 * and stop the listener; nothing is retried silently.
 * Must be created and driven from the main thread.
 */
class Listener(
    private val context: Context,
    private val preferOnDevice: Boolean,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFatal: (String) -> Unit,
) : RecognitionListener {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var onDevice = false

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFatal("No speech recognition service is installed on this phone.")
            return
        }
        onDevice = preferOnDevice &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val created = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-AU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOnDevice)
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
        onStatus(if (onDevice) "Listening (on-device recognition)" else "Listening (system recognition service)")
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

            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> {
                onStatus("Speech service network error (code $error), restarting in 3 s")
                restartAfter(3000)
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                stop()
                onFatal("Microphone permission was refused.")
            }

            else -> {
                stop()
                onFatal("Speech recognition failed with error code $error. " +
                    "Codes 12 and 13 mean the en-AU language pack is not installed for on-device recognition: " +
                    "turn off on-device recognition in Settings or install the pack.")
            }
        }
    }

    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
