# Gemma Vision 2.0 — Project Plan

A fully on-device, multimodal accessibility companion for blind and low-vision (BLV) users, built on Gemma 4 E4B trimodal (vision + audio + text in a single inference pass), with native function calling for agentic tool use, packaged via LiteRT + MediaPipe LLM Inference on Android (AICore on Pixel 9+ for NPU acceleration).

This document is the **what** and **why we win**. Implementation specifics are in `03-implementation-plan.md`.

---

## 1. One-Paragraph Pitch (the writeup opener)

> Gemma Vision (2025) proved that on-device, multimodal capabilities can make a difference in people's lives — winning the Gemma 3n Impact Challenge by helping blind users describe scenes and read text without an internet connection. **Gemma Vision 2.0** is the next step: Gemma 4 E4B does vision, audio, and reasoning in *one* model pass, with native function calling that turns a passive describer into an agent. Built on Google AI Edge (LiteRT + MediaPipe LLM Inference) and Apache-2.0 open weights, it runs fully on a Pixel 9 with sub-second time-to-first-spoken-word, supports 140+ languages out of the box, and never sends a single image off-device. Co-designed with BLV users at CMU's Office of Disability Resources, with a calibrated abstention path the research literature has been demanding for three years.

---

## 2. The Gap (what's still broken in 2026)

The BLV accessibility AI market in May 2026 is busy but broken in specific, citable ways:

### 2.1 The Competitive Landscape

| Product | Surface | Cloud / On-device | Pricing | Documented limitations |
|---|---|---|---|---|
| **Be My AI** (OpenAI / Be My Eyes) | iOS, Android, Mac, Ray-Ban Meta | **Cloud** (every image uploads to OpenAI API) | Free (rate-limited) | Cloud privacy concerns; hallucinates ([Misfitting With AI, ASSETS '24](https://dl.acm.org/doi/10.1145/3663548.3675659)); refuses to read mail recipient names; 2–5s round-trip |
| **Seeing AI** (Microsoft) | iOS, Android | **Hybrid** (rich features cloud) | Free | Crashes; "Richer Descriptions" only in English ([AppleVis](https://applevis.com/forum/ios-ipados/seeing-ai-becoming-unusable)); offline degradation |
| **Envision AI / Glasses** | App + Google Glass EE2 | Mostly cloud | App free; **Glasses $3,030–$4,030** | Glasses aging hardware; price barrier; battery 5–6h |
| **Lookout** (Google) | Android | Hybrid | Free | "Explore mode is still in beta and is less accurate"; iOS users excluded |
| **Aira** (live human agents) | Phone app | Cloud + humans | Free 5 min / 48 h, then $132/mo | Premium price; no privacy (a human sees every frame) |
| **Ray-Ban Meta + Meta AI** | Smart glasses | **Cloud** | $299–$799 | Spatial reasoning fails ([AFB Accessworld Fall 2025](https://www.afb.org/aw/fall2025/meta-glasses-review)); selective refusals; airport dead zones |
| **OrCam MyEye 2 Pro** | Clip-on hardware | **Fully offline** | **$3,500–$4,500** | Short battery; closed ecosystem; rare updates |
| **Supersense** | Phone | Mostly on-device | $4.99/mo, $49.99/yr | Simpler model; no conversational follow-up |
| **TapTapSee** | Phone | Cloud + crowd | Free | 15–60s latency; cloud-only |
| **OKO** | Phone | On-device CV | Free | Single-task (crosswalks only) |

**The unfilled niche:** *No deployed product today combines (a) fully on-device multimodal, (b) free, (c) conversational follow-up, (d) tactile + voice activation, (e) cross-language, (f) function-calling tool use.* That's our target shape.

### 2.2 The Five Most-Cited BLV Pain Points (from the research literature)

These are the empirical research findings — every one is a design requirement, not an opinion:

1. **Hallucinated confidence on unanswerable questions.** [Yu et al. (COLM 2024)](https://www.yihaopeng.tw/pdf/COLM24_VizwizLF.pdf) documents that frontier VLMs rarely abstain even when photos are blurry; [Alharbi et al. (ASSETS 2024)](https://arxiv.org/abs/2408.06546) report "matter-of-fact confidence" as the dominant friction. *Implication:* mandatory abstention path with verbal hedges.

2. **One-size-fits-all descriptions miss what matters.** [Stangl/Morris et al. (ASSETS 2021)](https://cs.stanford.edu/~merrie/papers/assets2021_scenarios.pdf): preferences vary dramatically by *context of encounter* (news photo vs dating profile vs shopping). [Context-Aware Image Descriptions (ASSETS 2024)](https://dl.acm.org/doi/10.1145/3663548.3675658) confirms: context-conditioned descriptions outperform on every axis. *Implication:* condition on app foreground, GPS, time, recent voice intent before generating.

3. **Spatial reasoning failure on safety-critical scenes.** [SPHERE (ACL 2025)](https://arxiv.org/html/2412.12693) and [CAPTURE (ICCV 2025)](https://openaccess.thecvf.com/content/ICCV2025/papers/Pothiraj_CAPTURE_Evaluating_Spatial_Reasoning_in_Vision_Language_Models_via_Occluded_ICCV_2025_paper.pdf) show frontier VLMs reliably mis-locate left/right, near/far, and miss occluded objects. [Exploring VLMs for Navigation (arXiv 2603.15624)](https://arxiv.org/html/2603.15624) says even GPT-4o is "not fully reliable" for BLV navigation. *Implication:* do not market navigation safety; describe only, never adjudicate "is it safe to cross."

4. **OCR on cluttered real-world scenes remains weak.** [OCRBench v2 (NeurIPS D&B 2025)](https://arxiv.org/abs/2501.00321): most LMMs score below 50/100. *Implication:* ship a classical OCR fallback (ML Kit text detection) in parallel and reconcile outputs.

5. **Verification cost falls on the user.** [Misfitting With AI (ASSETS 2024)](https://arxiv.org/abs/2408.06546): BLV users develop elaborate non-visual verification workflows because no provenance or contestation affordance exists. *Implication:* tap-to-rephrase, "show me what you saw" image cache, captured-image history.

### 2.3 The "Gemma 4 Trimodal On-Device Wins Because…" Argument

Seven specific advantage axes, each defensible against the deployed competition:

| Axis | The win |
|---|---|
| **Privacy by construction** | Camera frames containing mail, medical bills, family members, and home interiors **never leave the device**. Stony Brook research confirms BLV users self-censor on cloud apps because they cannot preview frames non-visually ([Can AI Assistants Protect Privacy of Blind, Low-Vision Users](https://news.stonybrook.edu/university/can-ai-assistants-protect-the-privacy-of-blind-and-low-vision-users/)). |
| **Offline reliability** | Works in airports, subways, rural LTE shadows, on flights, in developing-country connectivity. Be My AI / Seeing AI's rich features collapse offline; AFB Accessworld explicitly cites this gap for Ray-Ban Meta. |
| **Latency / sustained interaction** | Cloud round-trip is 2–5s plus network. Gemma 4 E4B on Pixel 9 Pro: 1.5–2s prefill + streamed TTS = sub-second perceived TTFT. The difference between "ask, wait, re-aim" and "ask, hear, react" — crucial for any user in motion. |
| **Cost / no quota** | Be My AI is free but quota-limited; Aira is $132/mo; Envision Glasses $3,030+; OrCam $3,500+; Ray-Ban Meta $299–$799. Gemma 4 on a phone the user already owns: **zero marginal cost, no quota, unlimited turns**. |
| **Multilingual** | Gemma 4: 140+ pre-training languages, 35 multimodal. Be My AI / Seeing AI / Lookout degrade outside English. ~253M visually impaired people globally — the long tail is non-English. |
| **Conversational follow-up that doesn't time out** | 128K context, fully local memory, unlimited turns. Be My AI / cloud APIs are stateless with quota timeouts. |
| **Tool use / function calling** | Gemma 4's 6 native tool tokens enable agentic flows — *"scan this product and add it to my Amazon cart"*, *"save this business card to Contacts"*, *"translate this menu and message it to my friend"*. None of Be My AI / Seeing AI / Lookout / Ray-Ban Meta does this. |

---

## 3. What We're Building (the system)

### 3.1 Five Concrete Use Cases (the demo montage)

These are the use cases that will appear in the 2–3 min demo video. Each must work end-to-end before submission.

1. **Mail / document reading on a flight (offline OCR + Q&A)**
 User boards a flight, opens a stack of mail, presses the "read text" button on the 8BitDo Micro: *"read each envelope and tell me which is from the IRS."* Demonstrates: offline, 128K context for multi-image cross-reference, narrow function-calling for "read_document" + "translate_text" tools.

2. **Currency identification with cross-bill counting**
 User holds a wallet: *"count the total."* Demonstrates: vision + function-calling (`identify_currency` tool), multi-image session, narrow domain accuracy.

3. **Multilingual menu reading (Spanish)**
 *"Lee este menú y dime qué platos no tienen carne."* Demonstrates: 140+ language pre-training advantage, OCR + reasoning + Spanish response, all on-device. None of Be My AI / Seeing AI does this well.

4. **Product lookup with function calling**
 User points at an unfamiliar package: *"read the allergens. Now look up this UPC on OpenFoodFacts."* Demonstrates: `read_document` + `scan_barcode_then_lookup` function calling — the agentic capability that no deployed competitor has.

5. **Sustained kitchen workflow**
 Multi-turn 30-minute session: *"is the oil shimmering? is the chicken golden? set a 7-minute timer. what color is this pan now?"* Demonstrates: unlimited turns, no quota, system-intent function calling (`system_action: alarm`), context retention.

### 3.2 Function-Calling Tool Set (7 tools)

Gemma 4's 6 special tokens (`<|tool|>`, `<|tool_call|>`, `<|tool_response|>` + closing pairs) carry JSON schemas embedded in the system prompt. Tool list:

1. `identify_currency(image_token_budget, expected_currency_hint?)` — denomination, currency code, side
2. `read_document(image_token_budget, language_hint, mode={document, sign, menu, label, handwriting})` — layout-aware OCR
3. `scan_barcode_then_lookup(lookup_source={openfoodfacts, upc-db, local-cache})` — UPC/EAN/QR → product
4. `describe_scene(image_token_budget, context={navigation, social, shopping, kitchen, general}, detail_level, allow_demographic_inference?)` — context-aware (per Stangl et al.)
5. `identify_color(mode={dominant, at_point, palette}, x?, y?)` — for clothing matching, accessibility
6. `translate_text(source_lang?, target_lang, text)` — on-device ML Kit Translation post-OCR
7. `system_action(action={send_sms, compose_email, create_event, call, alarm}, payload, user_confirmation_phrase)` — Android intent dispatch with explicit voice-confirmation requirement

**Explicitly NOT a tool:** `is_crosswalk_safe`. The spatial-reasoning literature is unanimous that frontier VLMs cannot do this reliably. We will *describe* the crosswalk, but never *adjudicate* its safety.

### 3.3 What We Will NOT Claim

A "responsible deployment" section is itself a judging asset (and aligns with Glenn Cameron's signals around responsible release). Honest concessions:

- **Raw reasoning ceiling.** GPT-4-class behind Be My AI is still smarter on long-tail visual reasoning. We win on *delivery surface* (privacy, latency, cost, offline, multilingual, tool use), not per-token smartness.
- **Human fallback for high-stakes calls.** Ship a "Call a Volunteer" button that hands off to Be My Eyes' volunteer network — explicitly *not* replacing the human network.
- **Currency edge cases.** Wrinkled bills, partial captures, rare denominations may need a dedicated head later — we ship Gemma 4 base with curated few-shot prompting and acknowledge this.
- **Curved-surface OCR.** Medicine bottles, soup cans cause hallucinations in generative VLMs. Reconcile with ML Kit's classical OCR as a fallback.
- **Form factor.** Chest-mounted phone is great for ergonomics but has worse "look where I'm looking" semantics than glasses. Glasses-class accessory (USB-C camera on temple) is a v2.1 stretch.
- **Demographic inference.** [Stangl et al.](https://cs.stanford.edu/~merrie/papers/assets2021_scenarios.pdf) showed BLV users *want* this context-dependently. Make it an opt-in setting, default off, never produce in navigation context.

---

## 4. Why We Win — Judge-Aligned Framing

### 4.1 The Judge Panel (publicly confirmed)

| Judge | Role | Public preference signal |
|---|---|---|
| **Glenn Cameron** | Sr PMM, Gemma team | Co-wrote the blog post celebrating Gemma Vision 1.0. Signature phrase: *"on-device, multimodal capabilities to make a difference in people's lives."* On-device + accessibility + named-user + named-OSS-framework (LiteRT, MediaPipe, Ollama). |
| **Kristen Quan** | PMM, partner to Glenn | Same vocabulary. Co-wrote Oct 2025 Gemma 3 270M post emphasizing "fast, private, and accessible to anyone, anywhere." |
| **Ian Ballantyne** | Dev marketing pod | Co-author of Oct 2025 Gemma 3 270M on-device fine-tuning post. On-device + fine-tunability + developer-experience focus. |
| **Gusthema** (Gustavo Costa) | Product Manager, Google | TensorFlow Lite → MediaPipe → LiteRT lineage. Likely the most technically-rigorous LiteRT/AI-Edge judge. |
| **Zhipeng Yan** | (uncertain) | Possibly research / Kaggle / engineering — wildcard. |

**4 of 5 confirmed judges are the Gemma dev-marketing pod that judged Gemma Vision 1.0 to first place.** This panel composition is the single most decisive piece of strategic intel.

### 4.2 What This Means for Framing

1. **Polish + narrative > benchmark deltas.** Marketing-grade panel. Demo video carries the most weight.
2. **Mirror their vocabulary exactly:**
 - *"on-device, multimodal capabilities to make a difference in people's lives"*
 - *"designed with input from"* [named user]
 - *"foster autonomy"* / *"autonomy for"* [community]
 - *"leveraged Gemma 4's native multimodal capabilities"*
 - *"streamed responses via MediaPipe LLM Inference API"*
 - *"completely offline with near-zero latency"*
 - *"frontier multimodal intelligence on device"* (Gemma 4 launch tagline)
 - *"reproducible notebook"*
3. **Name the stack explicitly:** LiteRT-LM, MediaPipe LLM Inference API, AICore, Google AI Edge.
4. **Cloud fallback is a poison pill.** Glenn's entire identity is on-device. Do not add a cloud-handoff path. The "Call a Volunteer" Be My Eyes button is acceptable because it's a *human handoff*, not a cloud-LLM handoff.
5. **The legitimate "what's new for Gemma 4 vs 3n" delta is essential.** Lead with trimodal-in-one-pass + native function calling. Avoid framing as "evolution of Gemma Vision."

### 4.3 Required Submission Artifacts

Per Kaggle judging structure (Impact & Vision + Technical Execution + Video Pitch + Reproducibility):

- **Public code repository** (open-source — automatic differentiation from closed-source original)
- **Working demo APK** + Hugging Face Space for desktop reproducibility
- **2–3 minute cinematic video** with BLV beneficiary on camera + airplane-mode beat
- **Kaggle writeup** mirroring Glenn Cameron's bylined language
- **Reproducibility notebook** — Kaggle T4 / Colab one-click that runs the eval harness
- **Custom Modelfile** or `.litertlm` bundle published to ollama.com/library and/or HuggingFace litert-community

---

## 5. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| "Just a remake of 3n winner" perception | Lead writeup with trimodal-one-pass + native function calling — the legitimate Gemma 4 deltas. Do not open with "evolution of Gemma Vision." |
| Multiple teams pick this archetype | Execution + polished video + open-source repo wins. The 7 function-calling tools + 140-language demo are differentiators competitors won't have. |
| AICore Dev Preview API churn | Gate AICore behind capability check; fall back to MediaPipe LLM Inference on all non-AICore devices. Both paths run Gemma 4 E4B. |
| `.litertlm` NPU acceleration not yet on Tensor G4 | Ship GPU-backed launch on Pixel via MediaPipe; document NPU as roadmap. NPU works today on Snapdragon 8 Elite via Qualcomm AI Engine Direct delegate. |
| Cold-load latency (40–50s for E4B on Pixel 9 Pro) | Load once at app start on first wake-word trigger. Keep resident. Document in onboarding. |
| Hallucination on unanswerable questions | Mandatory abstention prompt; positive haptic on "I can't tell" path; ship abstention evaluation row. |
| Spatial-reasoning safety claims | Do not market navigation safety. Stick to *description*; defer adjudication to the user. |
| Demographic inference policy | Opt-in setting, default off, never auto-fire in navigation context. Document the design tradeoff in the writeup. |
| TalkBack focus / audio conflicts | Request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` so TalkBack ducks rather than fights. Test on Pixel 9 with Accessibility Suite. |
| Battery under sustained use | E2B fallback at <30% battery; throttle to 140-token image budget; publish measured battery numbers (target: ≥4h continuous, ≥8h intermittent). |

---

## 6. Success Metrics

**Headline metrics to publish:**

1. **Time to first spoken word (TTFT) ≤ 2.5 s** on Pixel 9 Pro from wake-word → first phoneme of TTS
2. **Sustained generation ≥ 15 tok/s** for E4B on Pixel 9 Pro
3. **Battery: ≤ 0.75% per 25-conversation session** on E4B (matching the published Gemma 4 efficiency claims)
4. **OCRBench v2 score** for our specific OCR pipeline (Gemma 4 + ML Kit reconciliation) vs baseline Gemma 4 alone
5. **VizWiz-LF abstention rate** on a held-out blurry/occluded photo subset (should be ≥ 30%, vs <5% for vanilla VLMs per Yu et al. 2024)
6. **Multilingual coverage**: demo OCR + reasoning in English, Spanish, Hindi, Arabic — all on-device

**Qualitative metrics:**

- 5 BLV co-designer perception-study Likert scores (CMU HCII / UPMC stroke-rehab connections — same pipeline as VoicePreserve plan)
- Glenn Cameron / Kristen Quan blog-post alignment: every term in their published vocabulary appears verbatim in our writeup

---

## 7. Stack Decision Summary

| Layer | Primary | Fallback | Rationale |
|---|---|---|---|
| Inference (Pixel 9+) | **AICore** (`com.google.ai.edge.aicore:0.0.1-exp01`) | MediaPipe LLM Inference (`com.google.mediapipe:tasks-genai:0.10.27`) | AICore = NPU acceleration + system-managed updates; MediaPipe = stable, broadly compatible |
| Inference (non-AICore) | MediaPipe LLM Inference | — | Required for older Pixels, Samsung, etc. |
| Model file format | `.litertlm` (Gemma 4) | `.task` (legacy MediaPipe) | NPU acceleration requires `.litertlm` |
| Primary model | **Gemma 4 E4B INT4** | Gemma 4 E2B INT4 (low-battery fallback) | E4B has better reasoning + function calling; E2B saves ~50% RAM |
| Audio input | Native Gemma 4 audio encoder | Android `SpeechRecognizer` (offline on Pixel) | Gemma 4 E4B does ASR natively; SR is a fallback for noisy conditions |
| Wake word | **Porcupine** (on-device, 1 MB model, ~3.8% CPU) | None (button-only) | Always-on, on-device, no audio leaves device |
| TTS | **Android `TextToSpeech`** with Pixel neural offline voices | Piper INT8 via sherpa-onnx (bundled) | Native, lowest latency, TalkBack-cooperative |
| Camera | CameraX, 1024×1024 capture | — | Standard Android camera with backpressure handling |
| Function-call tools (external) | OpenFoodFacts, UPC-DB lookups | Local cache | Only network calls; user-consented per call |
| Tactile activation | **8BitDo Micro Bluetooth gamepad** | Volume-button hold | Same as original Gemma Vision; signature visual |
| App framework | **Native Android (Kotlin)** | — | AICore is Android-native; Flutter adds latency |
| Repo base | Fork of [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) | — | Production-grade Google sample app; Apache 2.0; on Play Store; already integrates MediaPipe LLM Inference + multimodal scaffolding |

---

## 8. One-Sentence Differentiation from Gemma Vision 1.0

> Gemma Vision 1.0 proved a blind user can get scene descriptions on-device; Gemma Vision 2.0 proves a blind user can *act* on the world from their phone — read a menu in Spanish, scan a product, dispatch a text, all without leaving the device, all in one Gemma 4 inference pass.

That sentence is the writeup TL;DR.
