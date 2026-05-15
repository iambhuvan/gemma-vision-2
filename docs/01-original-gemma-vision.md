# Gemma Vision (Original) — Deep Analysis

A definitive technical brief on Tommaso Giovannini's **Gemma Vision**, the project that won 1st place ($50K) and the Google AI Edge Special Technology Prize at the Gemma 3n Impact Challenge (Kaggle, June–Aug 2025). This document is the baseline against which Gemma Vision 2.0 must be designed — we need to understand exactly what won, why, and where the open headroom is.

---

## 1. Identity and Recognition

**Project.** "An AI vision assistant for the blind. The first ever accessibility app with fully on device AI and a tactile interface through a controller." Built on Google Gemma 3n to describe scenes, read text, and identify objects. Domain: [gemmavision.com](https://gemmavision.com/).

**Author.** Tommaso Giovannini — Italian indie developer, self-described "Flutter app developer with a strong interest in Machine Learning and Data Science." Prior shipped products: Flashcards AI (iOS/Android), James AI, Compare Portfolios. Portfolio: [tommasogiovannini.com](https://www.tommasogiovannini.com/). GitHub user [tommasogiovannini](https://github.com/tommasogiovannini) exists but contains no public Gemma Vision repository — **the implementation is closed source**, which means our open-source Gemma Vision 2.0 has an automatic differentiation axis.

**Co-design partner.** Tommaso's brother is blind and acted as the primary co-design partner. This single fact drove every meaningful design decision (chest mount over hand-held; gamepad over touchscreen; streamed TTS over batched response). The Google blog post praising the project leads with this — *lived experience is the load-bearing narrative*, not the tech.

**Recognition.**
- **1st place ($50,000)** — Google Gemma 3n Impact Challenge, December 2025
- **Special Technology Prize — Google AI Edge ($10,000)** — for best use of the MediaPipe/on-device runtime
- Featured in Google's Dec 10, 2025 blog post "[These developers are changing lives with Gemma 3n](https://blog.google/innovation-and-ai/technology/developers-tools/developers-changing-lives-with-gemma-3n/)" co-authored by Glenn Cameron Jr. and Kristen Quan — **the same two PMMs judging Gemma 4 Good**

**Glenn Cameron's framing quote** (signature phrase to mirror in our writeup): "on-device, multimodal capabilities to make a difference in people's lives."

---

## 2. Technical Architecture

### 2.1 Model

- **Gemma 3n** in the multimodal vision+text configuration
- Practical inference target: **Gemma 3n E2B (INT4)** (~5B raw / ~2B effective params via MatFormer + Per-Layer Embeddings; ~1.3 GB on disk; 2–3 GB RAM) on most devices; **E4B (INT4)** on flagships with ≥8 GB RAM (~8B raw / ~4B effective; ~2.5 GB on disk; 4–5 GB RAM)
- **No fine-tuning** in the public writeup — base instruction-tuned weights plus careful prompt engineering
- Throughput on Pixel 9 Pro–class hardware: ~10–25 tok/s sustained

### 2.2 Runtime Stack

- **MediaPipe LLM Inference API** (Google AI Edge runtime) — TFLite-backed `.task` bundle execution
- **flutter_gemma** package ([pub.dev/packages/flutter_gemma](https://pub.dev/packages/flutter_gemma), [GitHub: DenisovAV/flutter_gemma](https://github.com/DenisovAV/flutter_gemma)) — Dart FFI wrapper over `mediapipe_genai`
- **Flutter** as the UI framework, deployed to **Android** primarily (Tommaso's portfolio shows Flutter-on-Android as primary; iOS path possible but undocumented in the writeup)
- **Token streaming to TTS** — flutter_gemma's streamed-response API pipes tokens to Android `TextToSpeech` so the first phoneme plays well before generation completes. This is the single most important UX decision in the project — it converts a 3–6 s full-answer latency into a sub-second perceived TTFT.

### 2.3 Hardware Loop

Three physical pieces:

1. **Android phone in a chest harness/mount.** Quote from the writeup: *"holding a phone can be impractical while using a cane."* Hands stay free; camera points outward at user-eye-level; the touchscreen is never used.

2. **8BitDo Micro Bluetooth gamepad** ([8bitdo.com/micro](https://www.8bitdo.com/micro/)) — a $25, ~25-gram, keychain-sized Bluetooth HID controller with 16 buttons. Acts as a tactile, discoverable, non-visual function bank: each face/shoulder button is mapped to a distinct vision intent ("describe scene," "read text," "find object," "where am I"). Deterministic button feel; instantly recognisable by touch.

3. **Voice input as the second activation channel.** No published wake-word — the design is **button-or-voice**. The controller acts as hardware push-to-talk, and free-form follow-ups ("what color is the bottle on the left?") route through on-device ASR.

### 2.4 Function Set (publicly described)

A deliberately narrow, well-instrumented surface:

| Function | Trigger | Description |
|---|---|---|
| Scene description | Button + camera capture | "What's in front of me" |
| OCR / text reading | Button + camera capture | Mail, signs, packaging |
| Object identification | Button + camera capture | "What is this object" |
| Conversational follow-up | Voice | Multi-turn — "tell me more about X" |

Narrow surface beat broad ambition. Multiple Gemma 3n submissions tried to ship more features and shipped worse demos.

---

## 3. Why It Won — The Three Load-Bearing Claims

### 3.1 "Fully on device"

No internet. No cloud. The camera frame — which captures the user's home, family, mail, medical paperwork — **never leaves the chest-mounted phone**. This is a categorical privacy improvement over Be My AI (which uploads every image to OpenAI). It also works on a subway, in rural areas, on a flight, in a country without reliable LTE. Glenn Cameron's signature phrase emphasizes "on-device" specifically because it's the differentiator the entire Gemma product team is publicly invested in.

### 3.2 "Tactile interface through a controller"

The 8BitDo Micro eliminates the touchscreen as a failure mode for non-visual interaction. Every prior accessibility app required the user to find buttons on a flat glass surface — exhausting at best, error-prone in motion. The gamepad gives deterministic button feel and a discoverable function map. This is the design move that made the demo viscerally different from every other entry.

### 3.3 Streaming TTS via flutter_gemma

Tommaso's writeup explicitly calls out *"streamed responses in the flutter_gemma package to make the experience fluid."* This pushes time-to-first-spoken-word into sub-second range even when full-answer latency is 3–6 seconds. The cognitive experience of *"I asked, it's already answering"* vs *"I asked, I'm waiting"* is the difference between a winning demo and a tepid one.

---

## 4. The Demo Video Structure (from Glenn/Kristen's blog post + YouTube)

[YouTube demo](https://www.youtube.com/watch?v=Fx6IuEggeac)

The video follows the canonical Gemma 3n winning template:

- **0–10s cold open:** the blind user (Tommaso's brother) tries to navigate / read something traditional — humanises the problem
- **10–30s:** the chest mount + 8BitDo controller in use, button-press → spoken response
- **30–90s:** multiple use cases in tight sequence (read mail, identify object, describe scene)
- **90–135s:** "no internet" / "airplane mode" beat — visible cue that this is fully on-device
- **135–180s:** beneficiary reaction shot + tagline; "Built on Gemma 3n"

This is the exact rhythm we must replicate in the Gemma Vision 2.0 video.

---

## 5. What the Original Did Not Do (the headroom for 2.0)

These gaps are not failures — they're the legitimate "what's new because of Gemma 4" runway:

1. **No native audio understanding.** Gemma 3n needed two separate model passes (vision pass + audio/ASR pass) or relied on OS-level STT. Gemma 4 E4B has native USM-style audio understanding in one model pass — partner ASR + scene reasoning fuse in a single inference call.

2. **No function calling / tool use.** Gemma 3n's tool-calling support was experimental and didn't ship in the project. Gemma 4 has 6 native special tokens (`<|tool_call>`, `<|tool_response>`, etc.) designed in from training. This unlocks: currency lookup, barcode-to-Amazon, OCR-then-translate, calendar/SMS dispatch — turning the passive describer into an *agent*.

3. **Single-image only.** Gemma 3n's typical operating mode is one image per turn. Gemma 4 E4B's 128K context plus session reuse enables multi-image cross-reference ("compare this label to the one from the previous bottle").

4. **English-dominant.** The Gemma 3n demo was English. Gemma 4 trains on 140+ languages (35 multimodal) — uncovers the long tail of non-English BLV users (Spanish, Hindi, Arabic, Swahili). There are ~253M visually impaired people globally; the non-English long tail is the equity unlock.

5. **No context awareness.** The app does the same thing regardless of GPS, time-of-day, or foreground app. Context-Aware Image Descriptions (Mukhiddinov et al., ASSETS 2024) showed BLV users *strongly* prefer context-conditioned descriptions across every measured axis.

6. **Closed source.** No public repo — limits academic citability, reproducibility, and the "judge can reproduce in 2 minutes" trope that wins Reproducibility points.

7. **No abstention / hedging.** Yu et al. (COLM 2024) documented that frontier VLMs rarely abstain on unanswerable questions, even when the BLV user's photo is blurry or occluded. The original Gemma Vision did not visibly address this; we should.

8. **No tactile-graphic or accessibility-API integration.** Could cooperate with TalkBack (audio ducking, focus handoff) and read foreground-app context — neither was reported.

9. **Latency on E4B specifically.** Original used E2B for safety on most hardware. Gemma 4 E4B is functionally smarter than 3n E4B and runs in the same RAM envelope on Pixel 9+ — we can ship E4B as default with E2B as low-battery fallback.

---

## 6. The "Why Gemma 4 Vision 2.0 is Legitimate, Not Derivative" Argument

Both Glenn Cameron and Kristen Quan flagged in research that "remakes" of 3n winners are a risk — judges have already publicly championed the original. The legitimacy of 2.0 rests on **delta capability**, not delta polish. Five concrete deltas Gemma 4 enables that Gemma 3n could not:

1. **Trimodal in one model pass** (vision + audio + text, native to E4B). One model. One inference. Half the latency. New use cases like "while you're describing the menu, my friend just said something — repeat what he said" become coherent.

2. **Native function calling** via 6 special tokens (`<|tool_call>`, etc.). Calls system intents (SMS, calendar, call), external APIs (currency, barcode lookup, translate), and on-device tools (ML Kit Translation) — turning a describer into an agent. This is the headline feature of the Gemma 4 launch ("Bring state-of-the-art agentic skills to the edge with Gemma 4," Apr 2 2026 developer blog).

3. **256K context on the 26B-A4B MoE** (and 128K on E2B/E4B) — enables sustained multi-image sessions and reading-back-history without context loss.

4. **140+ language pre-training** vs Gemma 3n's English-best behavior on multimodal tasks. Multilingual BLV users get native-language scene descriptions and OCR.

5. **Apache 2.0 license** vs the more restrictive Gemma 3n terms. Forkable, fine-tunable for population-specific dialects without legal review — important for the disability-justice methodology of building tools *with* communities.

---

## 7. Source URLs

**Project:**
- [Gemma Vision Kaggle writeup](https://www.kaggle.com/competitions/google-gemma-3n-hackathon/writeups/gemma-vision)
- [Gemma Vision repost on the Gemma 4 Good Hackathon page](https://www.kaggle.com/competitions/gemma-4-good-hackathon/writeups/winner-of-gemma-3n-challenge-gemma-vision)
- [Project site](https://gemmavision.com/)
- [YouTube demo](https://www.youtube.com/watch?v=Fx6IuEggeac)
- [Hackathon winners page](https://www.kaggle.com/competitions/google-gemma-3n-hackathon/hackathon-winners)

**Author:**
- [Portfolio](https://www.tommasogiovannini.com/)
- [Projects page](https://www.tommasogiovannini.com/projects)
- [GitHub (no public Gemma Vision repo)](https://github.com/tommasogiovannini)

**Google recognition:**
- [Google blog: These developers are changing lives with Gemma 3n](https://blog.google/innovation-and-ai/technology/developers-tools/developers-changing-lives-with-gemma-3n/)
- [Google AI Devs winners thread (X)](https://x.com/googleaidevs/status/1998808731870797875)

**Runtime stack:**
- [flutter_gemma package on pub.dev](https://pub.dev/packages/flutter_gemma)
- [flutter_gemma on GitHub](https://github.com/DenisovAV/flutter_gemma)
- [Gemma 3n model overview](https://ai.google.dev/gemma/docs/gemma-3n)
- [Gemma 3n DeepMind page](https://deepmind.google/models/gemma/gemma-3n/)
- [Introducing Gemma 3n (Google Developers)](https://developers.googleblog.com/en/introducing-gemma-3n/)
- [Gemma 3n developer guide](https://developers.googleblog.com/en/introducing-gemma-3n-developer-guide/)
- [Gemma 4 model card](https://ai.google.dev/gemma/docs/core/model_card_4)
- [Gemma 4 launch — Hugging Face blog](https://huggingface.co/blog/gemma4)

**Hardware:**
- [8BitDo Micro Bluetooth gamepad](https://www.8bitdo.com/micro/)

---

## 8. Key Takeaways for Gemma Vision 2.0

1. **Disability access is the validated archetype.** Four of four top-prize 3n winners hit this; the panel publicly chose this beat. Do not deviate.
2. **The signature phrase to mirror:** "on-device, multimodal capabilities to make a difference in people's lives" (Glenn Cameron). Use it.
3. **Lived-experience grounding is the load-bearing narrative.** Cite a named real or composite user, ideally with a clip in the demo video. The blind brother was the heart of the original story.
4. **Token-streamed TTS is non-negotiable.** Sub-second time-to-first-spoken-word is the perceived-latency win.
5. **Tactile activation channel matters.** The 8BitDo Micro became the signature visual of the original. Gemma Vision 2.0 keeps it (or evolves to a tactile bone-conduction earpiece for v2.1).
6. **Narrow function surface, deep instrumentation.** Five well-tested intents beat fifteen half-baked ones.
7. **No fine-tuning is OK.** Original used base Gemma 3n + prompt engineering and won 1st place. We can do the same with Gemma 4 base — fine-tuning is a stretch goal, not a gate.
8. **Open-source the repo.** Original was closed; that's our automatic differentiation.
9. **Cite the gaps the literature exposes** (abstention, spatial reasoning, OCR-on-curved-surfaces) and design *around* them — claim only what we can defend.
10. **Replicate the demo-video rhythm.** Cold open → use-case montage → airplane-mode beat → beneficiary reaction → tagline.
