# Gemma Vision 2.0 — Demo Video Script

## Duration: 3-5 minutes

---

## Scene 1: Problem Statement (30 sec)

**[Screen: Statistics on visual impairment]**

"285 million people worldwide live with visual impairments. 90% are in low-income countries where cloud-based AI assistants don't work — there's no reliable internet. Existing solutions like Be My Eyes require a human volunteer. What if we could give every blind person an intelligent visual assistant that works entirely on their phone, with no internet required?"

---

## Scene 2: Introducing Gemma Vision 2.0 (30 sec)

**[Screen: App icon → App launch on phone/emulator]**

"Gemma Vision 2.0 runs Google's Gemma 4 model directly on Android using LiteRT-LM — the same runtime used by Google's AI Edge Gallery. No cloud. No internet. No images leaving the device. Just point, capture, and listen."

---

## Scene 3: Architecture Overview (45 sec)

**[Screen: Architecture diagram from notebook]**

"The app captures a photo via CameraX, sends it through Gemma 4's vision encoder along with a context-aware system prompt, and streams the response through our sentence-boundary TTS pipeline. 

What makes this powerful is our multi-hop tool-calling system. The model can chain up to 4 tools per request — scanning a barcode, looking up the product, translating the label, and reading it aloud — all in one seamless interaction."

---

## Scene 4: Live Demo — Inference Proof (60 sec)

**[Screen: Android Studio logcat showing model load + token generation]**

"Here's Gemma 4 E2B loading on the device. The LiteRT-LM engine initializes with XNNPACK delegates... 3.4 seconds cold load from cache. Now we fire a test intent..."

**[Show logcat scrolling with tokens appearing]**

"Watch the tokens stream in: 'Hello, I am happy to assist you!' — that's Gemma 4 generating text entirely on-device. On a real Pixel 9, this would be 15-20 tokens per second."

---

## Scene 5: Tool Calling Demo (60 sec)

**[Screen: Code walkthrough of ToolCallRouter + tool implementations]**

"Our seven specialized tools handle real BLV tasks:
- Currency identification for shopping
- Barcode scanning with product lookup
- Document reading with ML Kit OCR
- Color identification using histogram analysis
- Translation to the user's language
- System actions like making calls or setting alarms

Each tool is dispatched locally. The model sees the result and continues generating a natural spoken response."

---

## Scene 6: Research Grounding (30 sec)

**[Screen: Research paper citations]**

"Every design decision is grounded in peer-reviewed BLV research. From VizWiz's finding that models must learn to abstain on unanswerable questions, to SPHERE's demonstration that VLMs fail at spatial reasoning — we've built safety rails that real users need."

---

## Scene 7: Impact & Closing (30 sec)

**[Screen: App UI showing 'Listening' state]**

"Gemma Vision 2.0 proves that Gemma 4's multimodal capabilities can make a real difference in people's lives — running entirely on-device, preserving privacy, working offline. No subscription. No cloud. Just AI that serves everyone, everywhere."

**[Screen: CMU logo + contact info]**

---

## Recording Notes

- Use QuickTime to record the emulator screen
- Show logcat in split view with the app
- Highlight key log lines (Engine ready, onMessage tokens)
- Keep narration clear and paced for accessibility (fitting for a BLV project)
- Total runtime: aim for 3 minutes, max 5 minutes
