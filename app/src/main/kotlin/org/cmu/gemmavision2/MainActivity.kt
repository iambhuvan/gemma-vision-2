package org.cmu.gemmavision2

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.cmu.gemmavision2.accessibility.GemmaVisionA11yService
import org.cmu.gemmavision2.app.GemmaVisionApp
import org.cmu.gemmavision2.audio.WakeWordService
import org.cmu.gemmavision2.camera.CameraCapture
import org.cmu.gemmavision2.camera.ImagePreprocessor
import org.cmu.gemmavision2.inference.SystemPrompt
import org.cmu.gemmavision2.inference.VisionIntent
import org.cmu.gemmavision2.input.GamepadInputHandler
import org.cmu.gemmavision2.output.StreamingTts
import org.cmu.gemmavision2.tools.ToolCall
import org.cmu.gemmavision2.tools.ToolCallRouter
import org.cmu.gemmavision2.tools.ToolRegistry

class MainActivity : ComponentActivity() {

    private lateinit var tts: StreamingTts
    private lateinit var camera: CameraCapture
    private val preprocessor = ImagePreprocessor()
    private lateinit var gamepad: GamepadInputHandler

    private val state = MutableStateFlow(UiState())
    @Volatile private var lastIntentAtMs: Long = 0L

    /** Receives ACTION_WAKE_WORD_FIRED from WakeWordService. */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WakeWordService.ACTION_WAKE_WORD_FIRED) {
                onIntent(VisionIntent.VoiceQuery)
            }
        }
    }

    /**
     * Debug-only receiver: lets us fire any VisionIntent from adb without
     * needing an 8BitDo gamepad. Usage from host:
     *
     *     adb shell am broadcast -a org.cmu.gemmavision2.TEST_INTENT \
     *         --es intent DescribeScene -p org.cmu.gemmavision2
     */
    private val debugIntentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_DEBUG_INTENT) return
            val name = intent.getStringExtra("intent") ?: return
            val vi = runCatching { VisionIntent.valueOf(name) }.getOrNull()
            if (vi != null) {
                Log.i(TAG, "Debug intent: $name")
                onIntent(vi)
            } else {
                Log.w(TAG, "Unknown debug intent: $name")
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val essentials = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val essentialsOk = essentials.all { grants[it] != false }
        state.update { it.copy(permissionsGranted = essentialsOk) }
        if (essentialsOk) onEssentialsGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = StreamingTts(this)
        camera = CameraCapture(this, this)
        gamepad = GamepadInputHandler(::onIntent)

        // Register the broadcast receiver here, not in onResume, so the
        // wake word still routes while the screen is off (BLV use case).
        val filter = IntentFilter(WakeWordService.ACTION_WAKE_WORD_FIRED)
        val debugFilter = IntentFilter(ACTION_DEBUG_INTENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(debugIntentReceiver, debugFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(wakeReceiver, filter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(debugIntentReceiver, debugFilter)
        }

        ensurePermissions()

        // Track model loading state for UI
        lifecycleScope.launch {
            try {
                GemmaVisionApp.get().inferenceReady.await()
                state.update { it.copy(modelReady = true) }
            } catch (t: Throwable) {
                state.update { it.copy(modelError = t.message?.take(100)) }
            }
        }

        setContent {
            val s by state.collectAsState()
            StatusScreen(state = s)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(wakeReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(debugIntentReceiver) } catch (_: Throwable) {}
        tts.shutdown()
        super.onDestroy()
    }

    // ── Input pipeline ───────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        gamepad.handle(event) || super.dispatchKeyEvent(event)

    private fun onIntent(intent: VisionIntent) {
        // 300ms debounce — both gamepad bursts and double wake-words trigger
        // the same code path; we don't want two captures racing.
        val now = SystemClock.uptimeMillis()
        if (now - lastIntentAtMs < DEBOUNCE_MS) return
        lastIntentAtMs = now

        state.update { it.copy(activeIntent = intent, lastResponse = "", responseText = "", processing = true, tokenCount = 0, lastToolCall = null) }
        tts.stop()  // cancel any prior speech mid-stream
        if (intent == VisionIntent.CallVolunteer) {
            launchBeMyEyes()
            return
        }
        lifecycleScope.launch { runOneTurn(intent) }
    }

    // ── Single-turn orchestration with tool-response re-injection ────────

    private suspend fun runOneTurn(intent: VisionIntent) {
        val app = GemmaVisionApp.get()
        val provider = app.inferenceOrNull
        val router = app.toolRouter
        if (provider == null || !provider.isReady) {
            val msg = app.inferenceFailureMessageOrNull()
                ?: "One moment, the assistant is still loading."
            tts.feed(msg)
            tts.flush()
            return
        }

        // 1. Capture frame if the intent needs one.
        Log.i(TAG, "runOneTurn: intent=${intent.name} requiresImage=${intent.requiresImage}")
        val image: Bitmap? = if (intent.requiresImage) {
            val raw = camera.takePicture() ?: run {
                Log.w(TAG, "Camera capture returned null")
                tts.feed("I couldn't capture an image.")
                tts.flush()
                return
            }
            Log.i(TAG, "Image captured: ${raw.width}x${raw.height}")
            preprocessor.process(raw, intent)
        } else null

        // 2. Build the initial prompt. Debug intents get a minimal prompt
        // so inference is fast on emulators (< 100 tokens vs ~1500).
        var transcript = if (intent == VisionIntent.DebugTextOnly) {
            "You are a helpful assistant. Say hello in one sentence."
        } else {
            val a11y = GemmaVisionA11yService.get()
            buildString {
                append(SystemPrompt.render(
                    foregroundApp = a11y?.lastForegroundPackage,
                    coarseLocale = resources.configuration.locales.get(0).toLanguageTag(),
                ))
                append("\n\n")
                append(ToolRegistry.toolSchemas)
                append("\n\nUSER INTENT: ${intent.name} — ${intent.systemHint}")
            }
        }
        // 2b. For VoiceQuery, capture the user's spoken question and append it.
        if (intent == VisionIntent.VoiceQuery) {
            val spokenText = app.speechRecognizer.recognize()
            if (!spokenText.isNullOrBlank()) {
                transcript += "\n\nUSER SAID: \"$spokenText\""
                Log.i(TAG, "Voice query transcribed: $spokenText")
            } else {
                Log.w(TAG, "Speech recognition returned no text")
                // Still proceed with just the image — model will describe what it sees
            }
        }

        Log.i(TAG, "Prompt length: ${transcript.length} chars")

        // 3. Multi-hop generation loop: stream until we hit a tool call OR
        // the model emits a complete answer. After a tool call, append the
        // call + response to the transcript and re-invoke generation.
        var hops = 0
        while (hops < MAX_HOPS) {
            val buffer = StringBuilder()
            var pendingCall: ToolCall? = null

            // We pass the image only on hop 0. Subsequent hops are
            // text-only follow-ups carrying tool responses.
            provider.generateStream(
                prompt = transcript,
                image = if (hops == 0) image else null,
            ).collect { chunk ->
                buffer.append(chunk)
                router.trimBuffer(buffer)
                // Stream response text to UI for display — but filter out
                // raw tool call XML so the user sees clean natural language.
                val displayChunk = chunk
                    .replace(Regex("</?gv:tool_call>"), "")
                    .replace(Regex("</?gv:tool_response>"), "")
                if (displayChunk.isNotEmpty()) {
                    state.update { it.copy(
                        responseText = it.responseText + displayChunk,
                        tokenCount = it.tokenCount + 1,
                    ) }
                }

                // Speak everything that's safe (text before any open tag,
                // OR text before the trailing tag-fragment if no tag arrived
                // yet). Done UNCONDITIONALLY so the preamble before a tool
                // call still reaches the user.
                val safe = router.safePrefixToSpeak(buffer.toString())
                if (safe.isNotEmpty()) {
                    tts.feed(safe)
                    buffer.delete(0, safe.length)
                }

                // Now check whether a complete tool-call span is present.
                val call = router.tryParse(buffer.toString())
                if (call != null) {
                    // Strip the span; remember the call for re-prompting.
                    val stripped = router.stripFirstSpan(buffer.toString())
                    buffer.clear()
                    buffer.append(stripped)
                    pendingCall = call
                    // We cannot break out of .collect{} cleanly. The system
                    // prompt tells the model to STOP after a tool call so the
                    // upstream Flow should terminate shortly after. If the
                    // model keeps generating past the close tag, those tokens
                    // will be dropped on the floor — acceptable because the
                    // re-prompt's response is the authoritative continuation.
                }
            }

            // Flow ended. Either we have a pending tool call or a plain answer.
            val call = pendingCall
            if (call == null) {
                // Plain answer — flush any remainder.
                if (buffer.isNotEmpty()) tts.feed(buffer.toString())
                tts.flush()
                state.update { it.copy(processing = false) }
                return
            }

            // Dispatch the tool, re-prompt the model with call + response.
            val response = router.dispatch(call, image)
            Log.i(TAG, "tool=${call.name} ok=${response.ok}")
            // Clean up display: replace raw JSON tool call with a readable summary
            state.update { it.copy(
                lastToolCall = call.name,
                lastToolOk = response.ok,
                responseText = "",  // clear for the follow-up response
            ) }

            transcript = buildString {
                append(transcript)
                append("\n")
                append(router.encodeCall(call))
                append(router.encodeResponse(response))
                append("\n")
            }
            hops++
        }
        tts.feed("I couldn't finish that request after several attempts.")
        tts.flush()
        state.update { it.copy(processing = false) }
    }

    // ── Permissions ──────────────────────────────────────────────────────

    private fun ensurePermissions() {
        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            state.update { it.copy(permissionsGranted = true) }
            onEssentialsGranted()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun onEssentialsGranted() {
        bindCamera()
        // Now safe to start the wake-word foreground service.
        GemmaVisionApp.get().startWakeWordService()
    }

    private fun bindCamera() {
        lifecycleScope.launch {
            val ok = camera.bind(preview = null)
            state.update { it.copy(cameraReady = ok) }
        }
    }

    private fun launchBeMyEyes() {
        tts.feed("Connecting you to a Be My Eyes volunteer.")
        tts.flush()
        val app = packageManager.getLaunchIntentForPackage("com.bemyeyes.bemyeyes")
        val intent = app ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bemyeyes.com/"))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            tts.feed("Be My Eyes is not installed.")
            tts.flush()
        }
    }

    // ── UI state ─────────────────────────────────────────────────────────

    data class UiState(
        val permissionsGranted: Boolean = false,
        val cameraReady: Boolean = false,
        val modelReady: Boolean = false,
        val modelError: String? = null,
        val activeIntent: VisionIntent? = null,
        val processing: Boolean = false,
        val lastToolCall: String? = null,
        val lastToolOk: Boolean = true,
        val lastResponse: String = "",
        val responseText: String = "",
        val tokenCount: Int = 0,
    )

    companion object {
        private const val TAG = "MainActivity"
        private const val MAX_HOPS = 4
        private const val DEBOUNCE_MS = 300L

        /** Debug action — see [debugIntentReceiver] for usage. */
        const val ACTION_DEBUG_INTENT = "org.cmu.gemmavision2.TEST_INTENT"
    }
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    secondary = Color(0xFF81C995),
    tertiary = Color(0xFFFDD663),
    surface = Color(0xFF1E1E2E),
    background = Color(0xFF11111B),
    onSurface = Color(0xFFCDD6F4),
    onBackground = Color(0xFFCDD6F4),
)

@Composable
private fun StatusScreen(state: MainActivity.UiState) {
    MaterialTheme(colorScheme = DarkColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = DarkColors.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
            ) {
                // ── Header ──
                Text(
                    text = "Gemma Vision 2.0",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                    ),
                    color = Color.White,
                )
                Text(
                    text = "On-Device Accessibility Assistant",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DarkColors.onSurface.copy(alpha = 0.6f),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ── Status Row ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val (statusColor, statusText) = when {
                        state.modelError != null -> Color(0xFFED8796) to "Model Error"
                        !state.modelReady -> Color(0xFFFDD663) to "Loading Gemma 4..."
                        state.processing -> Color(0xFF8AB4F8) to "Processing"
                        state.cameraReady && state.permissionsGranted -> Color(0xFF81C995) to "Ready"
                        else -> Color(0xFFFDD663) to "Setting up..."
                    }
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = statusColor,
                    )

                    if (state.processing && state.tokenCount > 0) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "${state.tokenCount} tokens",
                            style = MaterialTheme.typography.bodySmall,
                            color = DarkColors.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }

                // ── Active Intent ──
                state.activeIntent?.let { intent ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = intentIcon(intent),
                                fontSize = 20.sp,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = intent.name,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White,
                                )
                                Text(
                                    text = intent.systemHint.take(60),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DarkColors.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                // ── Tool Call Indicator ──
                state.lastToolCall?.let { tool ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (state.lastToolOk) "OK" else "ERR",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (state.lastToolOk) Color(0xFF81C995) else Color(0xFFED8796),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (state.lastToolOk) Color(0xFF81C995).copy(alpha = 0.15f) else Color(0xFFED8796).copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Tool: $tool",
                            style = MaterialTheme.typography.bodySmall,
                            color = DarkColors.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Response Area ──
                val scrollState = rememberScrollState()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF181825)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(scrollState),
                    ) {
                        if (state.responseText.isNotEmpty()) {
                            Text(
                                text = state.responseText,
                                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                                color = Color.White,
                            )
                        } else if (state.processing) {
                            Text(
                                text = "Analyzing image...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = DarkColors.onSurface.copy(alpha = 0.4f),
                            )
                        } else if (state.modelReady) {
                            Text(
                                text = "Press a gamepad button, say \"Hey Gemma\", or send an ADB intent to start.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = DarkColors.onSurface.copy(alpha = 0.3f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                text = state.modelError ?: "Loading Gemma 4 E2B model...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (state.modelError != null) Color(0xFFED8796) else DarkColors.onSurface.copy(alpha = 0.3f),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Footer ──
                Text(
                    text = "Gemma 4 E2B  |  LiteRT-LM  |  On-Device  |  No Internet",
                    style = MaterialTheme.typography.labelSmall,
                    color = DarkColors.onSurface.copy(alpha = 0.3f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun intentIcon(intent: VisionIntent): String = when (intent) {
    VisionIntent.DescribeScene, VisionIntent.DescribeSceneDetailed -> "EYE"
    VisionIntent.ReadText, VisionIntent.ReadTextLong -> "DOC"
    VisionIntent.IdentifyObject -> "OBJ"
    VisionIntent.IdentifyCurrency -> "USD"
    VisionIntent.IdentifyColor -> "CLR"
    VisionIntent.ScanBarcode -> "BAR"
    VisionIntent.VoiceQuery -> "MIC"
    VisionIntent.CallVolunteer -> "SOS"
    VisionIntent.DebugTextOnly -> "DBG"
}
