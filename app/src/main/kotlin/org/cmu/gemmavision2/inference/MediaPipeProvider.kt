package org.cmu.gemmavision2.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis

/**
 * Gemma 4 inference via Google AI Edge **LiteRT-LM** (`.litertlm` format).
 *
 * This is the same runtime the Google AI Edge Gallery reference app uses.
 * It loads:
 *   • `gemma-4-E4B-it.litertlm` (~3.4 GB) — production target
 *   • smaller fallbacks (.task removed — LiteRT-LM loads .litertlm only)
 *
 * The class is still named MediaPipeProvider for backward compat with the
 * rest of the codebase, but it no longer uses MediaPipe Tasks GenAI.
 */
class MediaPipeProvider(private val context: Context) : InferenceProvider {

    private val initialized = AtomicBoolean(false)
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    override val isReady: Boolean get() = initialized.get() && engine != null

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (!initialized.compareAndSet(false, true)) return@withContext
        try {
            val elapsed = measureTimeMillis {
                val modelFile = ensureModelFile()
                // Try CPU first — GPU (OpenGL) always fails on emulator and
                // wastes ~2 min. On real devices with Adreno/Mali, flip order.
                // Use all available cores for XNNPACK parallelism.
                val cpuCores = Runtime.getRuntime().availableProcessors()
                Log.i(TAG, "Available CPU cores: $cpuCores")
                engine = tryEngine(modelFile, Backend.CPU(cpuCores), visionGpu = false)
                    ?: tryEngine(modelFile, Backend.GPU(), visionGpu = true)
                    ?: throw IllegalStateException(
                        "Gemma 4 LiteRT-LM engine failed on both CPU and GPU backends"
                    )
                conversation = engine!!.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(
                            topK = 40,
                            topP = 0.95,
                            temperature = 0.7,
                        ),
                        systemInstruction = null,  // we render system prompt per-turn
                        tools = emptyList(),
                    )
                )
            }
            Log.i(TAG, "LiteRT-LM cold load: ${elapsed}ms")
        } catch (t: Throwable) {
            initialized.set(false)
            try { conversation?.close() } catch (_: Throwable) {}
            try { engine?.close() } catch (_: Throwable) {}
            conversation = null
            engine = null
            Log.e(TAG, "LiteRT-LM init failed", t)
            throw t
        }
    }

    private fun tryEngine(
        modelFile: File,
        textBackend: Backend,
        visionGpu: Boolean,
    ): Engine? {
        return try {
            val cacheDir = context.getExternalFilesDir(null)?.absolutePath
                ?: context.cacheDir.absolutePath
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = textBackend,
                visionBackend = if (visionGpu) Backend.GPU() else Backend.CPU(),
                audioBackend = Backend.CPU(),
                maxNumTokens = MAX_CONTEXT_TOKENS,
                cacheDir = cacheDir,
            )
            val eng = Engine(config)
            eng.initialize()
            Log.i(TAG, "LiteRT-LM Engine ready: text=$textBackend visionGpu=$visionGpu")
            eng
        } catch (t: Throwable) {
            Log.w(TAG, "Backend text=$textBackend failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    override fun generateStream(
        prompt: String,
        image: Bitmap?,
        maxTokens: Int,
    ): Flow<String> = channelFlow {
        val conv = conversation
            ?: throw IllegalStateException("MediaPipeProvider.initialize() not called or failed")

        // Build the contents list — image(s) first, then the rendered prompt
        // (system prompt + tool schemas + user intent are all in `prompt`).
        val contents = mutableListOf<Content>()
        image?.let { contents.add(Content.ImageBytes(it.toPngByteArray())) }
        contents.add(Content.Text(prompt))

        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                // The Message contains accumulated channels (text, thought, etc.).
                // For our streaming TTS pipeline we want the most recent chunk:
                val chunk = message.toString()
                Log.d(TAG, "onMessage: class=${message.javaClass.simpleName} len=${chunk.length} chunk=${chunk.take(120)}")
                if (chunk.isNotEmpty()) trySend(chunk)
            }
            override fun onDone() {
                Log.i(TAG, "onDone — generation finished")
                close()
            }
            override fun onError(throwable: Throwable) {
                Log.e(TAG, "onError — generation failed", throwable)
                close(throwable)
            }
        }

        Log.i(TAG, "sendMessageAsync: prompt.len=${prompt.length} hasImage=${image != null}")
        try {
            conv.sendMessageAsync(
                Contents.of(contents),
                callback,
                emptyMap(),
            )
            awaitClose {
                try { conv.cancelProcess() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            close(t)
        }
    }.flowOn(Dispatchers.IO)

    override fun shutdown() {
        try { conversation?.close() } catch (_: Throwable) {}
        try { engine?.close() } catch (_: Throwable) {}
        conversation = null
        engine = null
        initialized.set(false)
    }

    /**
     * Locate a usable Gemma 4 `.litertlm` file on the device.
     *
     * The model is too large to ship inside the APK (Android AAPT2 can't
     * package single assets > 2 GB). We expect:
     *
     *     adb push models/gemma-4-E4B-it.litertlm \
     *         /sdcard/Android/data/org.cmu.gemmavision2/files/
     */
    private fun ensureModelFile(): File {
        for (candidate in MODEL_CANDIDATES) {
            val external = context.getExternalFilesDir(null)?.let { File(it, candidate.name) }
            if (external != null && external.exists() && external.length() > candidate.minBytes) {
                Log.i(TAG, "Using model: ${candidate.name} (external, ${external.length()} bytes)")
                return external
            }
            val internal = File(context.filesDir, candidate.name)
            if (internal.exists() && internal.length() > candidate.minBytes) {
                Log.i(TAG, "Using model: ${candidate.name} (internal, ${internal.length()} bytes)")
                return internal
            }
        }
        val firstExternal = context.getExternalFilesDir(null)?.absolutePath
            ?: "/sdcard/Android/data/${context.packageName}/files"
        val candidateNames = MODEL_CANDIDATES.joinToString(", ") { it.name }
        throw ModelNotBundledException(
            "No Gemma 4 .litertlm found at $firstExternal.\n" +
                "Looked for: $candidateNames.\n" +
                "Run: adb push models/<one_of_those> $firstExternal/\n" +
                "(see docs/04-build-and-run.md §Step 4)"
        )
    }

    private fun Bitmap.toPngByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    /** Thrown when the .litertlm file is missing — actionable for devs. */
    class ModelNotBundledException(message: String) : IllegalStateException(message)

    private data class ModelCandidate(val name: String, val minBytes: Long)

    companion object {
        private const val TAG = "MediaPipeProvider"
        private const val MAX_CONTEXT_TOKENS = 4096

        private val MODEL_CANDIDATES = listOf(
            // Production: Gemma 4 E4B (~3.4 GB INT4)
            ModelCandidate("gemma-4-E4B-it.litertlm", 1_000_000_000L),
            // Sprint fallback if you have storage trouble: Gemma 4 E2B (~1.3 GB)
            ModelCandidate("gemma-4-E2B-it.litertlm", 500_000_000L),
            // Tiny dev fallback: Gemma 3 270M (~270 MB) — only after license accepted
            ModelCandidate("gemma3-270m-it-q8.litertlm", 100_000_000L),
        )
    }
}
