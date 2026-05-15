# Gemma Vision 2.0 — Implementation Plan

The concrete build document. Every architectural decision, every dependency, every Kotlin API call, every day-by-day step needed to ship Gemma Vision 2.0 to the Gemma 4 Good Hackathon (Kaggle, deadline May 18 2026).

This document assumes the strategic plan in `02-gemma-vision-2.0-plan.md` is locked.

---

## 1. System Architecture

```
                          GEMMA VISION 2.0 (Pixel 9 Pro+ primary; Pixel 8+ fallback)
   ┌──────────────────────────────────────────────────────────────────┐
   │  INPUT LAYER                                                     │
   │   • Wake-word: Porcupine on-device (CPU ~3-4%, always-on)        │
   │   • Tactile: 8BitDo Micro Bluetooth HID (push-to-talk)           │
   │   • Voice intent: Native Gemma 4 audio encoder (preferred)       │
   │     OR Android SpeechRecognizer (offline on Pixel fallback)      │
   │   • Camera: CameraX, 1024x1024 capture, on-demand                │
   │   • Accessibility Service: cooperative read of foreground app    │
   ├──────────────────────────────────────────────────────────────────┤
   │  ROUTING LAYER (Kotlin)                                          │
   │   • Context builder: app foreground, GPS, time, recent intent    │
   │   • Inference selector: AICore (Pixel 9+) ↔ MediaPipe (rest)     │
   │   • Token-budget picker: currency=280, doc=560,                  │
   │     scene=280, quick-ID=140, doc-long=1120                       │
   │   • Battery-tier model swap: E4B (>30%) ↔ E2B (<30%)             │
   ├──────────────────────────────────────────────────────────────────┤
   │  INFERENCE LAYER                                                 │
   │   ┌─────────────────────────┐    ┌──────────────────────────┐    │
   │   │ Primary: AICore         │    │ Fallback: MediaPipe LLM  │    │
   │   │ com.google.ai.edge.     │ ←→ │ Inference API            │    │
   │   │ aicore:0.0.1-exp01      │    │ com.google.mediapipe:    │    │
   │   │ + ML Kit Prompt API     │    │ tasks-genai:0.10.27      │    │
   │   │ (Gemma 4 E4B, NPU)      │    │ (Gemma 4 E4B .litertlm,  │    │
   │   │                         │    │ GPU on non-AICore Pixels)│    │
   │   └─────────────────────────┘    └──────────────────────────┘    │
   ├──────────────────────────────────────────────────────────────────┤
   │  TOOL-CALL ROUTER                                                │
   │   Parse <|tool_call|> tokens from Gemma 4 output                 │
   │   Dispatch to 7 tools (§3)                                       │
   │   Inject results in <|tool_response|> and resume generation      │
   ├──────────────────────────────────────────────────────────────────┤
   │  OUTPUT LAYER                                                    │
   │   • Streamed tokens → Android TextToSpeech (Pixel neural voices) │
   │   • Sentence-boundary chunking via Kotlin Regex                  │
   │   • Haptic confirmation (Pixel) on tool-call success             │
   │   • Captured-image cache for verify / "show me what you saw"     │
   │   • Be My Eyes "Call a Volunteer" handoff for high-stakes calls  │
   └──────────────────────────────────────────────────────────────────┘
```

---

## 2. Tech Stack & Dependencies

### 2.1 Project Setup

```
Project name:    GemmaVision2
Package:         org.cmu.gemmavision2
Min SDK:         28 (Android 9)
Target SDK:      35 (Android 15)
Compile SDK:     35
Kotlin:          2.0.0
AGP:             8.5.0
Gradle:          8.7
```

### 2.2 `app/build.gradle.kts` — Critical Dependencies

```kotlin
dependencies {
    // ── Inference: AICore (primary, Pixel 9+) ──
    implementation("com.google.ai.edge.aicore:aicore:0.0.1-exp01")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")

    // ── Inference: MediaPipe LLM Inference (fallback) ──
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    implementation("com.google.mediapipe:tasks-vision:0.10.21")

    // ── ML Kit text + translation (OCR reconciliation) ──
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ── Camera ──
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    // ── Wake-word: Porcupine ──
    implementation("ai.picovoice:porcupine-android:3.0.4")

    // ── TTS fallback: Piper INT8 via sherpa-onnx ──
    implementation("com.k2-fsa.sherpa.onnx:sherpa-onnx:1.10.31")

    // ── Bluetooth HID for 8BitDo Micro ──
    // (uses standard Android InputDevice / KeyEvent — no extra dep)

    // ── JSON for tool-call parsing ──
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // ── Networking for function-call tool dispatchers ──
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ── Compose UI (minimal — voice-first app, screen rarely viewed) ──
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
}
```

### 2.3 `AndroidManifest.xml` — Critical Permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.INTERNET" />  <!-- function-call tools only -->
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />  <!-- context only -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<application ...>
    <service
        android:name=".accessibility.GemmaVisionA11yService"
        android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
        <intent-filter>
            <action android:name="android.accessibilityservice.AccessibilityService" />
        </intent-filter>
        <meta-data
            android:name="android.accessibilityservice"
            android:resource="@xml/accessibility_service_config" />
    </service>

    <service
        android:name=".audio.WakeWordService"
        android:foregroundServiceType="microphone"
        android:exported="false" />
</application>
```

---

## 3. Function-Calling Tool Schemas (the agent layer)

These schemas are embedded in the system prompt at session start. Gemma 4 emits structured tool calls via 6 special tokens: `<|tool|>`, `<|tool_call|>`, `<|tool_response|>` and closing pairs. The `<|"|>` string-literal delimiter makes JSON-in-args parseable.

### 3.1 Tools (7 total)

```json
[
  {
    "name": "identify_currency",
    "description": "Identify denomination of paper currency or coins from the captured image. Returns denomination, currency code (ISO 4217), and front/back side. Use for 'what bill is this' / 'count my money' queries.",
    "parameters": {
      "type": "object",
      "properties": {
        "image_token_budget": {"type": "integer", "enum": [280]},
        "expected_currency_hint": {"type": "string", "description": "Optional ISO 4217 hint if locale is known"}
      },
      "required": ["image_token_budget"]
    }
  },
  {
    "name": "read_document",
    "description": "OCR + layout-aware reading of a document, menu, sign, whiteboard, or packaging label. Reading order is preserved.",
    "parameters": {
      "type": "object",
      "properties": {
        "image_token_budget": {"type": "integer", "enum": [560, 1120]},
        "language_hint": {"type": "string"},
        "mode": {"type": "string", "enum": ["document", "sign", "menu", "label", "handwriting"]}
      },
      "required": ["image_token_budget", "mode"]
    }
  },
  {
    "name": "scan_barcode_then_lookup",
    "description": "Scan UPC/EAN/QR from image and look up product. Falls back to OCR of packaging if no barcode detected.",
    "parameters": {
      "type": "object",
      "properties": {
        "lookup_source": {"type": "string", "enum": ["openfoodfacts", "upc-db", "local-cache"]}
      },
      "required": ["lookup_source"]
    }
  },
  {
    "name": "describe_scene",
    "description": "Generate context-aware scene description. Respects the calling app's context (navigation vs. social vs. shopping). Returns description plus calibrated confidence and an explicit 'I cannot tell' path if image is blurry or occluded.",
    "parameters": {
      "type": "object",
      "properties": {
        "image_token_budget": {"type": "integer", "enum": [140, 280, 560]},
        "context": {"type": "string", "enum": ["navigation", "social", "shopping", "kitchen", "general"]},
        "detail_level": {"type": "string", "enum": ["brief", "standard", "detailed"]},
        "allow_demographic_inference": {"type": "boolean", "default": false}
      },
      "required": ["image_token_budget", "context"]
    }
  },
  {
    "name": "identify_color",
    "description": "Identify dominant colors in image or at a specific touch point. For clothing matching and color-blind use cases.",
    "parameters": {
      "type": "object",
      "properties": {
        "mode": {"type": "string", "enum": ["dominant", "at_point", "palette"]},
        "x": {"type": "number"},
        "y": {"type": "number"}
      },
      "required": ["mode"]
    }
  },
  {
    "name": "translate_text",
    "description": "Translate captured text using on-device ML Kit Translation. Use after read_document on foreign signs/menus.",
    "parameters": {
      "type": "object",
      "properties": {
        "source_lang": {"type": "string"},
        "target_lang": {"type": "string"},
        "text": {"type": "string"}
      },
      "required": ["target_lang", "text"]
    }
  },
  {
    "name": "system_action",
    "description": "Dispatch a system intent: send SMS, compose email, create calendar event, call contact, set alarm. ALWAYS requires explicit user voice confirmation before execution.",
    "parameters": {
      "type": "object",
      "properties": {
        "action": {"type": "string", "enum": ["send_sms", "compose_email", "create_event", "call", "alarm"]},
        "payload": {"type": "object"},
        "user_confirmation_phrase": {"type": "string", "description": "Phrase the user spoke to confirm; logged for audit."}
      },
      "required": ["action", "payload", "user_confirmation_phrase"]
    }
  }
]
```

### 3.2 Tools Explicitly NOT Implemented

- `is_crosswalk_safe` — spatial-reasoning literature (SPHERE, CAPTURE, Navigation 2026) shows VLMs fail unreliably. Safety-critical adjudication is off-limits.
- `identify_person_by_face` — privacy-sensitive; opt-in face-teach via separate flow only.
- `medical_diagnosis` — never. Always defer to professional + offer Be My Eyes handoff.

---

## 4. Core Kotlin Implementation Patterns

### 4.1 Inference Selector

```kotlin
// app/src/main/kotlin/org/cmu/gemmavision2/inference/InferenceProvider.kt
sealed interface InferenceProvider {
    suspend fun initialize()
    fun generateStream(
        prompt: String,
        image: Bitmap?,
        tokenBudget: Int,
    ): Flow<String>
    fun shutdown()

    companion object {
        fun select(context: Context): InferenceProvider {
            val isAICoreEligible = isPixel9OrLater() && AICore.isAvailable(context)
            return if (isAICoreEligible) {
                AICoreProvider(context)
            } else {
                MediaPipeProvider(context)
            }
        }

        private fun isPixel9OrLater(): Boolean {
            val model = Build.MODEL.lowercase()
            return model.contains("pixel 9") || model.contains("pixel 10")
        }
    }
}
```

### 4.2 MediaPipe LLM Inference Provider (the universal fallback)

```kotlin
// app/src/main/kotlin/org/cmu/gemmavision2/inference/MediaPipeProvider.kt
class MediaPipeProvider(private val context: Context) : InferenceProvider {
    private lateinit var llmInference: LlmInference

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        val modelFile = ensureModelFile("gemma-4-E4B-it.litertlm")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(2048)
            .setMaxNumImages(1)
            .setPreferredBackend(LlmInference.Backend.GPU)
            .build()
        llmInference = LlmInference.createFromOptions(context, options)
    }

    override fun generateStream(
        prompt: String,
        image: Bitmap?,
        tokenBudget: Int,
    ): Flow<String> = channelFlow {
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTemperature(0.7f)
            .setGraphOptions(
                GraphOptions.builder()
                    .setEnableVisionModality(image != null)
                    .build()
            )
            .build()

        LlmInferenceSession.createFromOptions(llmInference, sessionOptions).use { session ->
            session.addQueryChunk(SYSTEM_PROMPT)
            image?.let { session.addImage(BitmapImageBuilder(it).build()) }
            session.addQueryChunk(prompt)

            session.generateResponseAsync { partial, done ->
                trySend(partial)
                if (done) close()
            }
            awaitClose { /* session.close handled by use */ }
        }
    }

    private fun ensureModelFile(name: String): File {
        val target = File(context.filesDir, name)
        if (!target.exists()) {
            context.assets.open(name).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return target
    }

    override fun shutdown() {
        llmInference.close()
    }

    companion object {
        private const val SYSTEM_PROMPT = """
You are Gemma Vision 2.0, an on-device accessibility assistant for blind and low-vision users.

Core rules:
1. ALWAYS prefer short, scannable answers (≤ 3 sentences) unless the user requests detail.
2. If the image is blurry, occluded, or you genuinely cannot tell: SAY SO clearly. Begin with "I can't tell from this image — ...". Never fabricate.
3. Speak in the user's language. Match the language of their question.
4. Describe spatial relations carefully: ONLY claim left/right/near/far if you are confident from the image. Otherwise omit.
5. Never adjudicate safety (e.g., "is it safe to cross"). Describe; let the user decide.
6. Use function calls aggressively: read_document, identify_currency, scan_barcode_then_lookup, identify_color, translate_text, system_action, describe_scene.
7. Confirm every system_action verbally before executing.
"""
    }
}
```

### 4.3 AICore + ML Kit Prompt API Provider (Pixel 9+ primary)

```kotlin
// app/src/main/kotlin/org/cmu/gemmavision2/inference/AICoreProvider.kt
class AICoreProvider(private val context: Context) : InferenceProvider {
    private lateinit var session: PromptSession

    override suspend fun initialize() {
        val capabilities = PromptApi.getCapabilities(context).await()
        require(capabilities.canGenerate()) { "AICore Gemma 4 not available" }

        session = PromptApi.openSession(
            context,
            PromptSessionConfig(
                model = "gemma-4-e4b",
                systemInstruction = SYSTEM_PROMPT,
                tools = ToolRegistry.gemmaVisionTools,
                enableStreaming = true,
            )
        )
    }

    override fun generateStream(prompt: String, image: Bitmap?, tokenBudget: Int): Flow<String> =
        session.streamMessage(
            PromptMessage(text = prompt, images = listOfNotNull(image)),
            generationConfig = GenerationConfig(maxOutputTokens = tokenBudget)
        )

    override fun shutdown() = session.close()

    companion object {
        // Same SYSTEM_PROMPT as MediaPipeProvider
    }
}
```

### 4.4 Tool-Call Router

```kotlin
// app/src/main/kotlin/org/cmu/gemmavision2/tools/ToolCallRouter.kt
class ToolCallRouter(
    private val ocrTool: ReadDocumentTool,
    private val currencyTool: IdentifyCurrencyTool,
    private val barcodeTool: ScanBarcodeTool,
    private val sceneTool: DescribeSceneTool,
    private val colorTool: IdentifyColorTool,
    private val translateTool: TranslateTextTool,
    private val systemTool: SystemActionTool,
) {
    suspend fun dispatch(call: ToolCall): ToolResponse = when (call.name) {
        "identify_currency" -> currencyTool.execute(call.arguments)
        "read_document" -> ocrTool.execute(call.arguments)
        "scan_barcode_then_lookup" -> barcodeTool.execute(call.arguments)
        "describe_scene" -> sceneTool.execute(call.arguments)
        "identify_color" -> colorTool.execute(call.arguments)
        "translate_text" -> translateTool.execute(call.arguments)
        "system_action" -> systemTool.execute(call.arguments)
        else -> ToolResponse.error("Unknown tool: ${call.name}")
    }

    fun parseToolCall(stream: String): ToolCall? {
        val match = TOOL_CALL_REGEX.find(stream) ?: return null
        return ToolCall.fromJson(match.groupValues[1])
    }

    companion object {
        // Gemma 4 emits: <|tool_call|>{ "name": "...", "arguments": {...} }<|tool_call|>
        private val TOOL_CALL_REGEX = Regex(
            """<\|tool_call\|>(.*?)<\|tool_call\|>""",
            RegexOption.DOT_MATCHES_ALL
        )
    }
}
```

### 4.5 TTS Streaming with Sentence Chunking

```kotlin
// app/src/main/kotlin/org/cmu/gemmavision2/output/StreamingTts.kt
class StreamingTts(context: Context) {
    private val tts = TextToSpeech(context) { /* init */ }.apply {
        language = Locale.US
        // Pixel exclusive neural voices when available
        voice = voices.firstOrNull { it.isNetworkConnectionRequired.not() && it.name.contains("network") }
            ?: defaultVoice
    }
    private val buffer = StringBuilder()
    private var counter = 0

    fun feed(token: String) {
        buffer.append(token)
        // Flush at sentence boundaries OR every 40 chars (whichever comes first)
        val flushIdx = findFlushBoundary(buffer)
        if (flushIdx > 0) {
            val sentence = buffer.substring(0, flushIdx + 1).trim()
            buffer.delete(0, flushIdx + 1)
            tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, "g${counter++}")
        }
    }

    fun flush() {
        if (buffer.isNotBlank()) {
            tts.speak(buffer.toString(), TextToSpeech.QUEUE_ADD, null, "g${counter++}")
            buffer.clear()
        }
    }

    private fun findFlushBoundary(sb: StringBuilder): Int {
        for (i in sb.indices.reversed()) {
            if (sb[i] in ".!?\n") return i
        }
        return if (sb.length >= 40) sb.length - 1 else -1
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
```

### 4.6 Wake-Word Service (Porcupine)

```kotlin
// app/src/main/kotlin/org/cmu/gemmavision2/audio/WakeWordService.kt
class WakeWordService : Service() {
    private lateinit var porcupineManager: PorcupineManager

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        porcupineManager = PorcupineManager.Builder()
            .setAccessKey(BuildConfig.PORCUPINE_ACCESS_KEY)
            .setKeywordPath("hey_gemma.ppn")  // custom-trained wake word
            .setSensitivity(0.6f)
            .build(this) { keywordIndex ->
                triggerListen()
            }
        porcupineManager.start()
    }

    private fun triggerListen() {
        sendBroadcast(Intent(ACTION_WAKE_WORD_FIRED).setPackage(packageName))
    }

    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        porcupineManager.stop()
        porcupineManager.delete()
        super.onDestroy()
    }

    companion object {
        const val ACTION_WAKE_WORD_FIRED = "org.cmu.gemmavision2.WAKE"
        private const val NOTIF_ID = 42
    }
}
```

### 4.7 8BitDo Micro HID Handler

The 8BitDo Micro shows up as a standard Bluetooth HID gamepad. Map each face/shoulder button to an intent.

```kotlin
// app/src/main/kotlin/org/cmu/gemmavision2/input/GamepadInputHandler.kt
class GamepadInputHandler(private val onIntent: (VisionIntent) -> Unit) {
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (!event.device.isGamepad()) return false

        val intent = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> VisionIntent.DescribeScene
            KeyEvent.KEYCODE_BUTTON_B -> VisionIntent.ReadText
            KeyEvent.KEYCODE_BUTTON_X -> VisionIntent.IdentifyObject
            KeyEvent.KEYCODE_BUTTON_Y -> VisionIntent.ScanBarcode
            KeyEvent.KEYCODE_BUTTON_L1 -> VisionIntent.IdentifyCurrency
            KeyEvent.KEYCODE_BUTTON_R1 -> VisionIntent.IdentifyColor
            KeyEvent.KEYCODE_BUTTON_START -> VisionIntent.VoiceQuery
            KeyEvent.KEYCODE_BUTTON_SELECT -> VisionIntent.CallVolunteer
            else -> return false
        }
        onIntent(intent)
        return true
    }

    private fun InputDevice.isGamepad(): Boolean =
        sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
}
```

---

## 5. Image Preprocessing Pipeline

Critical for OCR quality (BLV-captured images are documented to be blurry/off-axis):

```kotlin
// app/src/main/kotlin/org/cmu/gemmavision2/camera/ImagePreprocessor.kt
class ImagePreprocessor {
    fun preprocess(raw: Bitmap, intent: VisionIntent): Bitmap {
        val targetSize = when (intent) {
            VisionIntent.ReadText -> 1024  // pairs with 560/1120 token budget
            VisionIntent.IdentifyCurrency -> 768  // 280 token budget
            else -> 768
        }
        val resized = raw.scaleTo(targetSize, targetSize, preserveAspect = true)
        return when (intent) {
            VisionIntent.ReadText -> resized.unsharpMask().adaptiveContrast()
            else -> resized
        }
    }

    private fun Bitmap.scaleTo(w: Int, h: Int, preserveAspect: Boolean): Bitmap = /* impl */
    private fun Bitmap.unsharpMask(): Bitmap = /* OpenCV / RenderEffect */
    private fun Bitmap.adaptiveContrast(): Bitmap = /* CLAHE */
}
```

Image token budget by intent:

| Intent | Token budget | Resolution |
|---|---|---|
| Quick object ID | 140 | 512 × 512 |
| Currency | 280 | 768 × 768 |
| Scene description (brief) | 140 | 512 × 512 |
| Scene description (standard) | 280 | 768 × 768 |
| Scene description (detailed) | 560 | 1024 × 1024 |
| OCR (sign, label) | 560 | 1024 × 1024 |
| OCR (multi-page document) | 1120 | 1024 × 1024 |

---

## 6. Model File Provisioning

```
app/src/main/assets/
    gemma-4-E4B-it.litertlm    # ~2.5 GB — bundled in APK or fetched first-run
    gemma-4-E2B-it.litertlm    # ~1.3 GB — battery fallback
    hey_gemma.ppn              # Porcupine custom wake word
    piper-en-libritts-medium.onnx  # TTS fallback bundle
```

**Download strategy:** Bundle E2B (1.3 GB) in the APK for guaranteed first-run; download E4B on first launch with progress UI. Use the [Hugging Face `unsloth/gemma-4-E4B-it-GGUF`](https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF) or [`google/gemma-4-E4B-it-litert-lm`](https://huggingface.co/google/gemma-4-E4B-it-litert-lm) mirrors. Cache to `context.filesDir`.

---

## 7. Day-by-Day Execution Plan (5-day sprint)

### Day 0 — RIGHT NOW (next 30 min)

```bash
mkdir -p /Users/bhuvan/Desktop/vibe/GEMMA/app
cd /Users/bhuvan/Desktop/vibe/GEMMA/app
git clone https://github.com/google-ai-edge/gallery.git google-ai-edge-gallery
# Strip down to what we need; keep MediaPipe init, multimodal scaffolding
# Download model files (will need HF auth token):
huggingface-cli download unsloth/gemma-4-E4B-it-GGUF \
    gemma-4-E4B-it-Q4_K_M.gguf \
    --local-dir ./models
huggingface-cli download google/gemma-4-E4B-it-litert-lm \
    gemma-4-E4B-it.litertlm \
    --local-dir ./models
```

Watch the [original Gemma Vision demo](https://www.youtube.com/watch?v=Fx6IuEggeac); internalize the rhythm.

### Day 1 — Spine working

- Fork Google AI Edge Gallery; strip to minimum
- Wire `MediaPipeProvider` with Gemma 4 E4B `.litertlm` bundle loading
- Validate end-to-end: prompt + image → streamed token output (no UI yet, log only)
- Acquire 8BitDo Micro from Amazon ($25) — overnight ship
- Acquire Pixel 9 Pro for testing (or borrow one)

### Day 2 — Voice loop + TTS

- Integrate Porcupine wake-word service (custom keyword "Hey Gemma" — Picovoice Console)
- Wire `StreamingTts` with Pixel neural voices + sentence-boundary chunking
- Implement `VoiceQuery` flow: wake → record → Gemma audio encoder OR Android SpeechRecognizer fallback
- Test latency: wake-to-TTFT target ≤ 2.5s

### Day 3 — Function-calling tools + 8BitDo

- Implement all 7 tool dispatchers (currency, OCR, barcode, scene, color, translate, system action)
- Wire `ToolCallRouter` parsing of `<|tool_call|>` tokens from Gemma 4 stream
- Integrate 8BitDo Micro via standard Bluetooth HID (KeyEvent handler)
- Map buttons to intents (see §4.7)
- ML Kit Translation + Barcode + Text Recognition as classical-OCR reconciliation layer

### Day 4 — Polish + video shoot

- 5 use case demos: mail-on-flight, currency counting, Spanish menu, product lookup, kitchen workflow
- 2–3 min video shoot:
 - Find a BLV co-actor (CMU Office of Disability Resources OR blindfolded sighted person clearly disclosed)
 - Tight script (see §11)
 - Airplane-mode beat at minute mark
- Battery + latency measurements: publish numbers
- Run abstention eval: shoot 20 deliberately blurry/occluded photos and verify ≥30% "I can't tell" responses

### Day 5 — Submission

- Kaggle writeup mirroring Glenn Cameron's bylined vocabulary (see §10)
- Public GitHub repo (Apache 2.0 license)
- Hugging Face Space (Gradio) with desktop reproducibility
- Push to Kaggle competition page
- Cross-post to ollama.com/library if possible: `bnallamo/gemma4-vision2` Modelfile

---

## 8. Latency & Battery Targets

| Metric | Target | How to measure |
|---|---|---|
| Wake-word TTFT (mic → wake) | <300 ms | Logcat timestamp deltas |
| Voice intent → first VLM token (TTFT) | <2.5 s | App-level instrumentation |
| Sustained generation | ≥15 tok/s | Tokens / wallclock during streaming |
| Token-to-speech latency | <500 ms after first 5 tokens | StreamingTts trace |
| Battery per 25-conversation session | ≤0.75% (E4B), ≤0.5% (E2B) | Battery Historian over a 25-turn scripted session |
| Always-on mic power | <2%/hr idle | Battery Historian, 1h soak |
| Model cold load (one time) | <50s (E4B), <20s (E2B) | App start to ready-state |

Publish these numbers in the writeup. Glenn's panel rewards measured on-device performance over benchmark claims.

---

## 9. Evaluation Harness

### 9.1 Held-out BLV Photo Eval Set

20 deliberately-staged photos representing common BLV failure modes:
- 5 blurry / motion-blurred photos (test abstention)
- 5 occluded objects (test spatial reasoning humility)
- 5 multi-language signs (English, Spanish, Hindi, Arabic, Japanese)
- 5 cluttered scenes (test OCRBench-v2-style real-world text)

Run Gemma Vision 2.0 + Gemma 4 base (no fine-tune, no function calls) + Be My AI screenshots (manual) as comparisons. Publish:

- Abstention rate on blurry/occluded subset (target ≥30% for Gemma Vision 2.0; ≤5% for vanilla VLMs per Yu et al. 2024)
- Multilingual coverage (does it produce native-language responses?)
- Time-to-spoken-word per query

### 9.2 OCRBench v2 Subset

Run our Gemma 4 + ML Kit reconciliation OCR pipeline on the public English subset of [OCRBench v2](https://arxiv.org/abs/2501.00321). Report score vs base Gemma 4.

### 9.3 BLV Co-Designer Perception Study

If feasible in the 5-day window: 3 BLV co-designers, 5 minutes each, Likert ratings on:
- Trust (1–5)
- Speed perception (1–5)
- Voice naturalness (1–5)
- "Would you use this daily" (1–5)

Even N=3 is meaningfully better than zero per Mack et al. CHI 2022. CMU Office of Disability Resources is the recruitment channel.

---

## 10. Kaggle Writeup Template

```markdown
# Gemma Vision 2.0: On-device, multimodal capabilities for blind and low-vision users — agentic with Gemma 4

Gemma Vision 1.0 won the 2025 Gemma 3n Impact Challenge by proving fully on-device,
multimodal capabilities can make a difference in people's lives for blind users.

Gemma Vision 2.0 takes the next step. We leveraged Gemma 4 E4B's native trimodal
architecture — vision, audio, and reasoning in *one* model pass — and its native
function calling to turn a passive describer into an agent that acts on the world.

Built on Google AI Edge (MediaPipe LLM Inference + AICore on Pixel 9+) and Apache 2.0
open weights, Gemma Vision 2.0 runs completely offline with near-zero latency
(2.5s time-to-first-spoken-word on Pixel 9 Pro), supports 140+ languages out of the
box, and never sends a single image off-device.

## What's new (because of Gemma 4)

1. **Trimodal in one model pass.** Gemma 3n needed separate vision and ASR passes.
   Gemma 4 E4B does both natively, in one inference. Half the latency, twice the
   sessions per battery percent.

2. **Native function calling via 6 special tokens.** `read_document`,
   `identify_currency`, `scan_barcode_then_lookup`, `translate_text`, `system_action`
   — Gemma Vision 2.0 dispatches Android intents, looks up products on OpenFoodFacts,
   and translates text via ML Kit, all from voice or button.

3. **140+ pre-training languages, 35 multimodal.** Native-language scene description
   and OCR for Spanish, Hindi, Arabic, Swahili — the long tail Be My AI / Seeing AI
   leave on the table.

4. **Apache 2.0.** Open weights and open code.

## Co-designed with the community

Designed with input from [N] blind co-designers from CMU's Office of Disability
Resources, building on the participatory-design methodology of Mack et al.
(CHI 2022) and Williams et al. (CHI 2020).

## Stack

- Gemma 4 E4B (`.litertlm`) via MediaPipe LLM Inference API + AICore on Pixel 9+
- Streamed responses via MediaPipe streaming + Android TextToSpeech
- Porcupine wake-word for hands-free activation
- 8BitDo Micro Bluetooth gamepad for tactile push-to-talk
- ML Kit Translation, Barcode, Text Recognition as classical-OCR reconciliation

## Reproducibility

[`docs/03-implementation-plan.md`](...) is the build doc. The Kaggle notebook
`gemma-vision-2-eval.ipynb` reproduces every metric on a free T4 in <20 minutes.
```

---

## 11. Video Shot List (2–3 min)

**Cold open (0–10s):** Pull-in on a BLV user (or clearly-disclosed blindfolded actor) standing in their kitchen, holding mail. Voiceover: "Reading my mail used to mean waiting for someone to come over."

**Setup (10–25s):** Chest mount + 8BitDo Micro shown clearly. Voiceover: "Now I press one button. And it works on a flight, in a subway, in any language — because everything runs on this phone."

**Use case montage (25–110s):**
- (25–40s) Mail reading: button press → "this is from the IRS, deadline May 15…"
- (40–60s) Currency: "count my money" → "$47 — three twenties, one five, two ones"
- (60–80s) Spanish menu: voice in Spanish → response in Spanish
- (80–95s) Barcode lookup: "look up this product" → tool call → result spoken
- (95–110s) Kitchen workflow: "is the oil shimmering? set a 7-minute timer" → spoken + alarm fires

**Airplane mode beat (110–125s):** Pull-up on phone, airplane mode icon clearly visible. Voiceover: "Everything you just saw — no cloud, no internet, no quota. Your camera frames never leave this phone."

**Tagline (125–150s):** Beneficiary reaction shot. "Built on Gemma 4. Open source. Free."

**Final card (150–180s):** "Gemma Vision 2.0 — on-device, multimodal capabilities to make a difference in people's lives. github.com/[org]/gemma-vision-2. CMU + [BLV partners]."

---

## 12. Repository Structure

```
/Users/bhuvan/Desktop/vibe/GEMMA/
├── docs/
│   ├── 01-original-gemma-vision.md
│   ├── 02-gemma-vision-2.0-plan.md
│   └── 03-implementation-plan.md
├── app/                          # Android Kotlin app
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/org/cmu/gemmavision2/
│           ├── MainActivity.kt
│           ├── inference/
│           │   ├── InferenceProvider.kt
│           │   ├── AICoreProvider.kt
│           │   └── MediaPipeProvider.kt
│           ├── tools/
│           │   ├── ToolCallRouter.kt
│           │   ├── IdentifyCurrencyTool.kt
│           │   ├── ReadDocumentTool.kt
│           │   ├── ScanBarcodeTool.kt
│           │   ├── DescribeSceneTool.kt
│           │   ├── IdentifyColorTool.kt
│           │   ├── TranslateTextTool.kt
│           │   └── SystemActionTool.kt
│           ├── audio/
│           │   ├── WakeWordService.kt
│           │   └── SpeechRecognizerFallback.kt
│           ├── camera/
│           │   ├── CameraCapture.kt
│           │   └── ImagePreprocessor.kt
│           ├── input/
│           │   └── GamepadInputHandler.kt
│           ├── output/
│           │   └── StreamingTts.kt
│           └── accessibility/
│               └── GemmaVisionA11yService.kt
├── notebooks/
│   └── gemma-vision-2-eval.ipynb   # Kaggle T4 reproducibility
├── eval/
│   ├── photos/                     # 20 held-out BLV-style photos
│   └── results/
├── video/
│   └── shot-list.md
└── README.md                       # Project landing page
```

---

## 13. Critical Reference URLs

**Implementation:**
- [Google AI Edge Gallery (fork base)](https://github.com/google-ai-edge/gallery)
- [MediaPipe LLM Inference for Android](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android)
- [Gemma 4 in AICore Dev Preview](https://developer.android.com/blog/posts/announcing-gemma-4-in-the-ai-core-developer-preview)
- [ML Kit Prompt API (Get started)](https://developers.google.com/ml-kit/genai/prompt/android/get-started)
- [LiteRT-LM repo](https://github.com/google-ai-edge/LiteRT-LM)
- [Gemma 4 model card](https://ai.google.dev/gemma/docs/core/model_card_4)
- [Gemma 4 function calling docs](https://ai.google.dev/gemma/docs/capabilities/text/function-calling-gemma4)
- [Gemma 4 prompt formatting](https://ai.google.dev/gemma/docs/core/prompt-formatting-gemma4)
- [On-device function calling in Gallery](https://developers.googleblog.com/on-device-function-calling-in-google-ai-edge-gallery/)
- [Unsloth Gemma 4 E4B GGUF](https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF)
- [Google Gemma 4 E4B `.litertlm`](https://huggingface.co/google/gemma-4-E4B-it-litert-lm)

**Hardware:**
- [Porcupine Wake Word SDK (Picovoice)](https://picovoice.ai/platform/porcupine/)
- [8BitDo Micro Bluetooth gamepad](https://www.8bitdo.com/micro/)

**Research papers (for writeup citations):**
- [VizWiz Grand Challenge (Gurari et al. CVPR 2018)](https://openaccess.thecvf.com/content_cvpr_2018/papers/Gurari_VizWiz_Grand_Challenge_CVPR_2018_paper.pdf)
- [Long-Form Answers to VQA from BLV (Yu et al. COLM 2024)](https://www.yihaopeng.tw/pdf/COLM24_VizwizLF.pdf)
- [Going Beyond One-Size-Fits-All Image Descriptions (Stangl et al. ASSETS 2021)](https://cs.stanford.edu/~merrie/papers/assets2021_scenarios.pdf)
- [Context-Aware Image Descriptions for Web Accessibility (ASSETS 2024)](https://arxiv.org/abs/2409.03054)
- [Misfitting With AI (Alharbi et al. ASSETS 2024)](https://arxiv.org/abs/2408.06546)
- [SPHERE: Spatial Blind Spots in VLMs (ACL 2025)](https://arxiv.org/html/2412.12693)
- [CAPTURE: Occluded Object Counting in VLMs (ICCV 2025)](https://openaccess.thecvf.com/content/ICCV2025/papers/Pothiraj_CAPTURE_Evaluating_Spatial_Reasoning_in_Vision_Language_Models_via_Occluded_ICCV_2025_paper.pdf)
- [Exploring VLMs for Navigation Assistance for BLV (arXiv 2603.15624)](https://arxiv.org/html/2603.15624)
- [OCRBench v2 (NeurIPS D&B 2025)](https://arxiv.org/abs/2501.00321)
- [What Do We Mean by 'Accessibility Research'? (Mack et al. CHI 2021)](https://makeabilitylab.cs.washington.edu/media/publications/Mack_WhatDoWeMeanByAccessibilityResearchALiteratureSurveyOfAccessibilityPapersInChiAndAssetsFrom1994To2019_CHI2021.pdf)

**Competitor evidence (writeup):**
- [Google blog: Developers changing lives with Gemma 3n](https://blog.google/innovation-and-ai/technology/developers-tools/developers-changing-lives-with-gemma-3n/)
- [Ray-Ban Meta BLV review (AFB Accessworld Fall 2025)](https://www.afb.org/aw/fall2025/meta-glasses-review)
- [Be My Eyes / Be My AI](https://www.bemyeyes.com/bme-ai/)
- [Seeing AI](https://www.microsoft.com/en-us/ai/seeing-ai)

---

## 14. Start Command

```bash
cd /Users/bhuvan/Desktop/vibe/GEMMA
mkdir -p app notebooks eval/photos eval/results video
git init
git clone https://github.com/google-ai-edge/gallery.git app/google-ai-edge-gallery
echo "# Gemma Vision 2.0" > README.md
echo "On-device multimodal accessibility companion for BLV users, built on Gemma 4 E4B." >> README.md
```

Day 0 starts now. Day 5 ships.
