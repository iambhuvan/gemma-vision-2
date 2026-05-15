package org.cmu.gemmavision2.app

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.cmu.gemmavision2.audio.SpeechRecognizerFallback
import org.cmu.gemmavision2.audio.WakeWordService
import org.cmu.gemmavision2.inference.InferenceProvider
import org.cmu.gemmavision2.tools.DescribeSceneTool
import org.cmu.gemmavision2.tools.IdentifyColorTool
import org.cmu.gemmavision2.tools.IdentifyCurrencyTool
import org.cmu.gemmavision2.tools.ReadDocumentTool
import org.cmu.gemmavision2.tools.ScanBarcodeTool
import org.cmu.gemmavision2.tools.SystemActionTool
import org.cmu.gemmavision2.tools.ToolCallRouter
import org.cmu.gemmavision2.tools.TranslateTextTool

/**
 * Process-level singleton holding the (heavy) inference provider, the tool
 * router, and an application-scoped coroutine. We deliberately do NOT use
 * Dagger/Hilt for the 5-day sprint — a single Application-owned graph is
 * simpler and faster to iterate on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GemmaVisionApp : Application() {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val speechRecognizer: SpeechRecognizerFallback by lazy {
        SpeechRecognizerFallback(this)
    }

    val toolRouter: ToolCallRouter by lazy {
        ToolCallRouter(
            currencyTool = IdentifyCurrencyTool(),
            documentTool = ReadDocumentTool(),
            barcodeTool = ScanBarcodeTool(),
            sceneTool = DescribeSceneTool(),
            colorTool = IdentifyColorTool(),
            translateTool = TranslateTextTool(),
            systemTool = SystemActionTool(this),
        )
    }

    /**
     * Resolves to the actual inference provider once initialize() succeeds.
     * MainActivity awaits this before dispatching a turn.
     */
    val inferenceReady: CompletableDeferred<InferenceProvider> = CompletableDeferred()

    /**
     * Best-effort access to a ready inference provider, or null if still
     * loading OR if initialization failed. We swallow the exception here
     * because `CompletableDeferred.getCompleted()` rethrows on a deferred
     * that completed exceptionally — which would crash the MainActivity
     * "still loading" check.
     */
    val inferenceOrNull: InferenceProvider?
        get() = try {
            if (inferenceReady.isCompleted) inferenceReady.getCompleted() else null
        } catch (_: Throwable) {
            null
        }

    /**
     * If init failed with a user-actionable message (e.g. model not bundled),
     * returns a short phrase suitable for TTS. Otherwise null — the caller
     * should fall back to a generic "still loading" message.
     */
    fun inferenceFailureMessageOrNull(): String? {
        if (!inferenceReady.isCompleted) return null
        val cause = inferenceReady.getCompletionExceptionOrNull() ?: return null
        // Walk the cause chain to find a ModelNotBundledException.
        var t: Throwable? = cause
        while (t != null) {
            if (t::class.simpleName == "ModelNotBundledException") {
                return "The Gemma 4 model isn't installed on this device yet. " +
                    "Please follow the setup instructions in the README."
            }
            t = t.cause
        }
        return "I couldn't load the assistant. ${cause.javaClass.simpleName}."
    }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        appScope.launch {
            try {
                val provider = InferenceProvider.create(this@GemmaVisionApp)
                inferenceReady.complete(provider)
                Log.i(TAG, "Inference ready: ${provider::class.simpleName}")
            } catch (t: Throwable) {
                Log.e(TAG, "Inference init failed (no further fallback)", t)
                inferenceReady.completeExceptionally(t)
            }
        }
        // NB: WakeWordService is started by MainActivity *after* RECORD_AUDIO
        // is granted. Starting it here would either crash on first install
        // (no permission) or leave a permanent broken foreground notification.
    }

    /**
     * Called by MainActivity once RECORD_AUDIO is granted.
     */
    fun startWakeWordService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, WakeWordService::class.java),
        )
    }

    companion object {
        private const val TAG = "GemmaVisionApp"

        @Volatile private var INSTANCE: GemmaVisionApp? = null
        fun get(): GemmaVisionApp = requireNotNull(INSTANCE) {
            "GemmaVisionApp not yet created"
        }
    }
}
