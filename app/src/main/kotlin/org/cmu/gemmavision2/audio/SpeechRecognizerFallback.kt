package org.cmu.gemmavision2.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Fallback ASR via Android SpeechRecognizer.
 *
 * Primary path is Gemma 4 E4B's native audio encoder (one-pass trimodal).
 * This exists for noisy / very-short-utterance cases where SR's specialized
 * acoustic model edges out the foundation model.
 *
 * On Pixel devices SpeechRecognizer runs offline by default; on other
 * Android devices it may require Google Speech Services + network.
 */
class SpeechRecognizerFallback(context: Context) {

    private val app = context.applicationContext

    suspend fun recognize(locale: Locale = Locale.US): String? =
        suspendCancellableCoroutine { cont ->
            if (!SpeechRecognizer.isRecognitionAvailable(app)) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val sr = SpeechRecognizer.createSpeechRecognizer(app)
            // Some OEMs (notably Samsung) fire BOTH onError and onResults for
            // a single recognition. Guard with an AtomicBoolean so we resume
            // the continuation exactly once.
            val resumed = AtomicBoolean(false)
            fun finish(text: String?) {
                if (resumed.compareAndSet(false, true)) {
                    sr.destroy()
                    cont.resume(text)
                }
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Log.w(TAG, "SR error $error")
                    finish(null)
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    finish(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            cont.invokeOnCancellation {
                if (resumed.compareAndSet(false, true)) {
                    try { sr.cancel(); sr.destroy() } catch (_: Throwable) {}
                }
            }
            sr.startListening(intent)
        }

    companion object {
        private const val TAG = "SpeechRecognizerFallback"
    }
}
