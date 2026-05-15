package org.cmu.gemmavision2.output

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Streams Gemma 4 tokens into Android TextToSpeech with sentence-boundary
 * chunking and TalkBack-cooperative audio focus.
 *
 * Critical UX rule: time-to-first-spoken-word must stay sub-second. We
 * therefore flush early — at any of [.!?\n] OR when buffer exceeds
 * [EARLY_FLUSH_CHARS] — rather than waiting for a full response.
 *
 * All buffer mutations are guarded by `synchronized(buffer)` so callers may
 * legally feed from any thread.
 */
class StreamingTts(context: Context) {

    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Tracks outstanding TTS utterances. Increment in [speak], decrement in
     * the UtteranceProgressListener's onDone/onError. On `0` we release focus.
     * NB: the counter is clamped to >= 0 in callbacks so that a stop() that
     * resets it to 0 followed by a delayed onDone can't drive it negative.
     */
    private val outstanding = AtomicInteger(0)

    private val buffer = StringBuilder()

    @Volatile private var ready = false

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            // Pick the system locale if TTS supports it; otherwise fall back to US English.
            val systemLocale = app.resources.configuration.locales.get(0)
            val pickedLocale = if (tts.isLanguageAvailable(systemLocale) >= TextToSpeech.LANG_AVAILABLE) {
                systemLocale
            } else {
                Locale.US
            }
            tts.language = pickedLocale
            tts.setSpeechRate(1.05f)
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { /* no-op */ }
                override fun onDone(utteranceId: String?) = onUtteranceFinished()
                @Deprecated("Old API; kept for compatibility")
                override fun onError(utteranceId: String?) = onUtteranceFinished()
                override fun onError(utteranceId: String?, errorCode: Int) = onUtteranceFinished()
            })
        } else {
            Log.e(TAG, "TTS init failed (status=$status)")
        }
    }

    /** Feed a token chunk from the Gemma 4 stream. Flushes at sentence boundary. */
    fun feed(chunk: String) {
        if (!ready || chunk.isEmpty()) return
        val toSpeak: String? = synchronized(buffer) {
            buffer.append(chunk)
            val idx = lastFlushBoundary(buffer)
            when {
                idx >= 0 -> {
                    val s = buffer.substring(0, idx + 1).trim()
                    buffer.delete(0, idx + 1)
                    s
                }
                buffer.length > EARLY_FLUSH_CHARS -> {
                    val s = buffer.toString().trim()
                    buffer.clear()
                    s
                }
                else -> null
            }
        }
        if (!toSpeak.isNullOrBlank()) speak(toSpeak)
    }

    /** Flush any trailing partial sentence. Call when generation completes. */
    fun flush() {
        if (!ready) return
        val tail = synchronized(buffer) {
            val s = buffer.toString().trim()
            buffer.clear()
            s
        }
        if (tail.isNotBlank()) speak(tail)
    }

    /** Hard-stop current speech (e.g., user pressed another button). */
    fun stop() {
        if (ready) tts.stop()
        synchronized(buffer) { buffer.clear() }
        outstanding.set(0)
        releaseFocus()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun speak(text: String) {
        if (outstanding.getAndIncrement() == 0) requestFocus()
        val id = "g${System.nanoTime()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, id)
    }

    /** Listener-side count update. Clamps at zero to survive stop() races. */
    private fun onUtteranceFinished() {
        val now = outstanding.updateAndGet { current -> if (current <= 0) 0 else current - 1 }
        if (now == 0) releaseFocus()
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        audioManager.requestAudioFocus(
            null,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        )
    }

    @Suppress("DEPRECATION")
    private fun releaseFocus() {
        audioManager.abandonAudioFocus(null)
    }

    private fun lastFlushBoundary(sb: StringBuilder): Int {
        for (i in sb.indices.reversed()) {
            if (sb[i] in SENTENCE_TERMINATORS) return i
        }
        return -1
    }

    companion object {
        private const val TAG = "StreamingTts"
        private const val EARLY_FLUSH_CHARS = 60
        private val SENTENCE_TERMINATORS = setOf('.', '!', '?', '\n')
    }
}
