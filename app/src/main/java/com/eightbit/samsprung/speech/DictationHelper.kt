package com.eightbit.samsprung.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.eightbit.samsprung.BuildConfig
import com.eightbit.samsprung.R
import java.util.Locale

/**
 * Voice dictation for the text fields of the app. Results are delivered while the user speaks
 * (partial results) and once more when the recognizer decides the sentence is finished.
 *
 * All methods must be called from the main thread.
 */
class DictationHelper(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        /** The microphone started (true) or stopped (false) listening. */
        fun onListening(active: Boolean)

        /** Recognized text. [isFinal] is false for partial results that may still change. */
        fun onText(text: String, isFinal: Boolean)

        /** Something prevented the dictation. See [messageFor] for a message to show. */
        fun onError(code: Int)
    }

    private val context: Context = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    var isListening = false
        private set

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun toggle() {
        if (isListening) stop() else start()
    }

    fun start() {
        if (!hasPermission()) {
            listener.onError(ERROR_PERMISSION)
            return
        }
        if (!isAvailable) {
            listener.onError(ERROR_UNAVAILABLE)
            return
        }
        destroy()
        val speech = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                setListening(true)
            }

            override fun onBeginningOfSpeech() { }

            override fun onRmsChanged(rmsdB: Float) { }

            override fun onBufferReceived(buffer: ByteArray?) { }

            override fun onEndOfSpeech() {
                setListening(false)
            }

            override fun onError(error: Int) {
                setListening(false)
                // Nothing said or nothing understood is not worth interrupting the user
                if (error != SpeechRecognizer.ERROR_NO_MATCH
                    && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    listener.onError(error)
                }
            }

            override fun onResults(results: Bundle?) {
                setListening(false)
                firstResult(results)?.let { listener.onText(it, true) }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { listener.onText(it, false) }
            }

            override fun onEvent(eventType: Int, params: Bundle?) { }
        })
        speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, BuildConfig.APPLICATION_ID)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    fun stop() {
        recognizer?.stopListening()
        setListening(false)
    }

    /** Releases the recognizer. Call it when the screen using the helper goes away. */
    fun destroy() {
        recognizer?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (_: Exception) { }
        }
        recognizer = null
        setListening(false)
    }

    private fun setListening(active: Boolean) {
        if (isListening == active) return
        isListening = active
        listener.onListening(active)
    }

    private fun firstResult(bundle: Bundle?): String? {
        return bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val ERROR_PERMISSION = -1
        const val ERROR_UNAVAILABLE = -2

        /** Message to show the user for an error code, or null if it is not worth showing. */
        @StringRes
        fun messageFor(code: Int): Int? = when (code) {
            ERROR_PERMISSION, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                R.string.voice_permission_needed
            ERROR_UNAVAILABLE -> R.string.voice_unavailable
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                R.string.voice_network_error
            else -> null
        }
    }
}
