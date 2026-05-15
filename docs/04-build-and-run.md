# Build & Run — Gemma Vision 2.0

5-day sprint, working backwards from the May 18 2026 Kaggle deadline.
This is the operational doc — what to do, in order, to get the app on a Pixel.

---

## Prereqs (one-time, ~30 min)

1. **Android Studio Ladybug (2024.2)+** with Android SDK 35 installed.
2. **Pixel 8 Pro or Pixel 9 / 9 Pro** for testing. (AICore + Gemma 4 Dev Preview targets Pixel 9 line.)
3. **Hugging Face CLI** with auth: `pip install -U huggingface_hub && huggingface-cli login`.
4. **Picovoice Console account** (free) for the custom wake-word model.

---

## Step 1: Gradle wrapper

`gradlew` and the wrapper jar are not committed. From the project root run **once**:

```bash
cd /Users/bhuvan/Desktop/vibe/GEMMA
# Use a system Gradle (8.7+) to generate the wrapper, then it's self-contained
gradle wrapper --gradle-version 8.9 --distribution-type bin
# Or open in Android Studio — it will create the wrapper automatically.
```

After this step `./gradlew tasks` should succeed.

---

## Step 2: Acquire Gemma 4 model

The app expects `app/src/main/assets/gemma-4-E4B-it.litertlm` (~2.5 GB INT4).

```bash
mkdir -p app/src/main/assets
huggingface-cli download litert-community/gemma-4-E4B-it-litert-lm \
    gemma-4-E4B-it.litertlm \
    chat_template.jinja \
    --local-dir app/src/main/assets
```

The `litert-community/` org publishes the LiteRT-LM bundles for the Gemma family. We pull both the `.litertlm` weights (~2-3 GB INT4) and the separate `chat_template.jinja` because Gemma 4 ships its chat template as a standalone file, not embedded in `tokenizer_config.json` (a known Gemma 4 quirk).

**Alternative paths if you want a different format:**
- `litert-community/gemma-4-E2B-it-litert-lm` — smaller (~1.3 GB) for low-RAM devices
- `unsloth/gemma-4-E4B-it-GGUF` — GGUF for `llama.cpp` (not directly compatible with MediaPipe LLM Inference; requires conversion)
- `google/gemma-4-E4B-it` — full safetensors (~16 GB); convert via [AI Edge Torch Generative API](https://github.com/google-ai-edge/litert-torch)

The `noCompress` directive in `app/build.gradle.kts` keeps these files mmap-able from the APK; no first-run decompression needed.

---

## Step 3: Picovoice wake-word

1. Sign in at <https://console.picovoice.ai/>.
2. Train a custom keyword **"Hey Gemma"** for Android. Download the `.ppn` file.
3. Place at `app/src/main/assets/hey_gemma.ppn`.
4. Copy your **AccessKey** from the console.
5. Add to `~/.gradle/gradle.properties` or `local.properties`:

   ```
   PORCUPINE_ACCESS_KEY=YOUR_KEY_HERE
   ```

If you skip this step, the wake-word service no-ops gracefully — push-to-talk via the 8BitDo Micro still works.

---

## Step 4: 8BitDo Micro Bluetooth pairing

1. Pair the Micro in Android Settings → Connected devices → Pair new device.
2. The Micro identifies as a standard HID gamepad — no driver needed.
3. Verify by launching any Android game-controller test app and pressing each button.

Button → intent map:

| Button | VisionIntent |
|---|---|
| A | DescribeScene |
| B | ReadText |
| X | IdentifyObject |
| Y | ScanBarcode |
| L1 | IdentifyCurrency |
| R1 | IdentifyColor |
| START | VoiceQuery (push-to-talk) |
| SELECT | CallVolunteer (Be My Eyes handoff) |

---

## Step 5: Build & install

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
adb shell am start -n org.cmu.gemmavision2/.MainActivity
```

First launch:
- Grant CAMERA + RECORD_AUDIO + BLUETOOTH_CONNECT.
- The model load is cold-start: **40–50 s on Pixel 9 Pro** for E4B. App says "Setting up..." during this.
- After load, press any 8BitDo button or say "Hey Gemma".

---

## Step 6: Enable the cooperative accessibility service (optional)

Settings → Accessibility → Installed services → **Gemma Vision 2** → On.

This lets the app read foreground-app name to enrich Gemma 4's context — purely a READ; we never drive UI from this service.

---

## Verification checklist

Before recording the demo video:

- [ ] Model loads without crash (cold + warm)
- [ ] Camera capture returns a non-null bitmap
- [ ] Each of 5 demo use cases works end-to-end (see `02-gemma-vision-2.0-plan.md` §3.1)
- [ ] Time-to-first-spoken-word ≤ 2.5 s on Pixel 9 Pro
- [ ] Airplane-mode demo (toggle in quick settings) keeps everything working except the function-call tools that use the network (barcode lookup)
- [ ] Battery Historian dump shows ≤0.75% per 25-conversation session
- [ ] Abstention rate ≥ 30% on the held-out blurry/occluded eval set
- [ ] Multilingual: Spanish menu test succeeds
- [ ] TalkBack ducks correctly when Gemma Vision speaks

---

## Known gaps to address before submission

These are tracked, not blockers for the spine:

1. Tool-response re-injection loop (multi-turn after a tool call) — the model needs the `<|tool_response|>` wrapped result fed back. Currently we dispatch tools but don't re-prompt.
2. AICore Dev Preview path is reflection-gated to avoid breakage from API churn; force MediaPipe until GA.
3. No `gradle-wrapper.jar` committed — must run `gradle wrapper` once or open in Android Studio.
4. Adaptive launcher icons exist but no PNG fallbacks for pre-O devices. With `minSdk=28` (Android 9), pre-O is irrelevant.
5. Eval notebook (`notebooks/gemma-vision-2-eval.ipynb`) is empty — populate before submission.

---

## Quick smoke test

After `installDebug`, with the device tethered:

```bash
adb shell input keyevent KEYCODE_BUTTON_A   # simulate 8BitDo A press
adb logcat -s MediaPipeProvider:V MainActivity:V StreamingTts:V WakeWordService:V
```

You should see the model load log, an intent dispatch, and TTS output.
