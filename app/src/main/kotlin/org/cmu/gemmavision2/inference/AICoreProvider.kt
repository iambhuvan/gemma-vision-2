package org.cmu.gemmavision2.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Pixel 9+ primary path via Google AI Edge AICore + ML Kit Prompt API.
 *
 * As of the AICore Developer Preview (Android Developers Blog, Apr 2026)
 * the public surface is:
 *   • com.google.ai.edge.aicore:aicore:0.0.1-exp01
 *   • com.google.mlkit:genai-prompt:1.0.0-beta2
 *
 * Both ship as `0.0.x-exp` developer previews — the API surface is in flux.
 * Class names and method signatures have changed between preview drops. We
 * keep this provider as a typed stub that **always throws on initialize()**
 * so the [InferenceProvider.create] factory cleanly falls back to
 * [MediaPipeProvider]. Until ML Kit Prompt API reaches a stable 1.x and the
 * AICore Gemma 4 module ships broadly, MediaPipe is the production path.
 *
 * TODO(post-Prompt-API-GA): replace this stub with a real implementation
 * that uses `PromptApi.getCapabilities(context)`, `PromptApi.openSession(...)`,
 * tool registration, and the streaming message API. See:
 *   https://developers.google.com/ml-kit/genai/prompt/android/get-started
 *   https://developer.android.com/blog/posts/announcing-gemma-4-in-the-ai-core-developer-preview
 */
class AICoreProvider(@Suppress("unused") private val context: Context) : InferenceProvider {

    override val isReady: Boolean get() = false

    override suspend fun initialize() {
        // Intentionally throws so InferenceProvider.create() falls back to
        // MediaPipe. Do not remove until the Prompt API surface stabilises.
        Log.i(TAG, "AICore Dev Preview gating — yielding to MediaPipe fallback.")
        throw UnsupportedOperationException(
            "AICoreProvider is a stub. Implementation pending Prompt API GA."
        )
    }

    override fun generateStream(prompt: String, image: Bitmap?, maxTokens: Int): Flow<String> =
        flow<String> {
            error("AICoreProvider.generateStream called without a successful initialize()")
        }.flowOn(Dispatchers.IO)

    override fun shutdown() { /* nothing to release */ }

    companion object {
        private const val TAG = "AICoreProvider"
    }
}
