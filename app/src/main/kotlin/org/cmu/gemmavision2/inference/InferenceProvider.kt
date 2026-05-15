package org.cmu.gemmavision2.inference

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.Flow

/**
 * Sealed contract for all Gemma 4 inference paths.
 *
 *  • [AICoreProvider]   — Pixel 9+, NPU acceleration via Google AI Edge AICore.
 *  • [MediaPipeProvider] — universal fallback, GPU on most modern Android.
 *
 * Both stream [String] token chunks. The caller is responsible for splitting
 * tool-call payloads (handled by [org.cmu.gemmavision2.tools.ToolCallRouter])
 * and for piping non-tool tokens to TTS.
 */
interface InferenceProvider {

    /** Has the underlying model been loaded and is ready to generate? */
    val isReady: Boolean

    /** One-time model load. Idempotent. Cold load can be 20-50s for E4B. */
    suspend fun initialize()

    /**
     * Stream a single turn.
     * @param prompt user text (already rendered with system prompt by caller)
     * @param image optional captured frame; null for text-only follow-ups
     * @param maxTokens generation budget
     */
    fun generateStream(
        prompt: String,
        image: Bitmap?,
        maxTokens: Int = 1024,
    ): Flow<String>

    /** Release native resources. Must be called from Activity/Service onDestroy. */
    fun shutdown()

    companion object {
        private const val TAG = "InferenceProvider"

        /**
         * Probe + initialize the best provider for this device.
         *
         * On Pixel 9+ with the AICore APK installed we try AICore first.
         * If its initialize() fails (Dev Preview API churn, missing AICore
         * Gemma 4 module, etc.) we fall back to MediaPipe — which is the
         * production path everywhere else.
         *
         * Suspending so we can actually run the init probe instead of
         * returning a not-yet-broken handle and hoping. This is the fix
         * for the original "select() returns AICoreProvider but its init
         * throws and there's no fallback path" bug.
         */
        suspend fun create(context: Context): InferenceProvider {
            if (shouldTryAICore(context)) {
                val aicore = AICoreProvider(context)
                try {
                    aicore.initialize()
                    if (aicore.isReady) {
                        Log.i(TAG, "Using AICoreProvider")
                        return aicore
                    }
                    Log.w(TAG, "AICore initialized but not ready — falling back to MediaPipe")
                } catch (t: Throwable) {
                    Log.w(TAG, "AICore initialize() failed — falling back to MediaPipe", t)
                } finally {
                    if (!aicore.isReady) aicore.shutdown()
                }
            }
            val mediapipe = MediaPipeProvider(context)
            mediapipe.initialize()
            Log.i(TAG, "Using MediaPipeProvider")
            return mediapipe
        }

        private fun shouldTryAICore(context: Context): Boolean =
            isAICoreEligibleDevice() && hasAICorePackage(context)

        private fun isAICoreEligibleDevice(): Boolean {
            val model = Build.MODEL.orEmpty().lowercase()
            // AICore + Gemma 4 Dev Preview targets the Pixel 9 / 10 line per
            // the April 2026 Android Developers Blog announcement.
            return model.contains("pixel 9") || model.contains("pixel 10")
        }

        private fun hasAICorePackage(context: Context): Boolean = try {
            context.packageManager.getPackageInfo("com.google.android.aicore", 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
