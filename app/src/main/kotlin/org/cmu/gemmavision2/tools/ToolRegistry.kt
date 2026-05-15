package org.cmu.gemmavision2.tools

/**
 * The 7 function-calling tools exposed to Gemma 4.
 *
 * The schema strings are appended to the system prompt at session start so
 * the model knows their names and argument shape. We keep them as raw JSON
 * (not generated structurally) to match the documented Gemma 4 prompt
 * formatting at ai.google.dev/gemma/docs/core/prompt-formatting-gemma4.
 *
 * Tools explicitly NOT included (and why):
 *  • is_crosswalk_safe — SPHERE/CAPTURE/Navigation literature shows VLM
 *    spatial reasoning is unreliable; safety adjudication is reckless.
 *  • identify_person_by_face — privacy-sensitive; only via opt-in face-teach.
 *  • medical_diagnosis — always defer to a professional + Be My Eyes handoff.
 */
object ToolRegistry {

    val toolSchemas: String = """
TOOLS AVAILABLE:

{"name":"identify_currency","description":"Identify denomination and currency code of paper bills or coins from the captured image. Returns denomination, ISO 4217 code, and front/back side. Use for 'what bill is this' or 'count my money'.","parameters":{"type":"object","properties":{"image_token_budget":{"type":"integer","enum":[280]},"expected_currency_hint":{"type":"string","description":"Optional ISO 4217 hint (e.g. USD, INR)"}},"required":["image_token_budget"]}}

{"name":"read_document","description":"OCR + layout-aware reading of a document, menu, sign, whiteboard, or packaging label. Preserves reading order. Reconciled against ML Kit classical OCR for accuracy.","parameters":{"type":"object","properties":{"image_token_budget":{"type":"integer","enum":[560,1120]},"language_hint":{"type":"string","description":"BCP-47 hint like 'en' or 'es'"},"mode":{"type":"string","enum":["document","sign","menu","label","handwriting"]}},"required":["image_token_budget","mode"]}}

{"name":"scan_barcode_then_lookup","description":"Scan UPC/EAN/QR from image and look up the product. Falls back to OCR of packaging if no barcode detected.","parameters":{"type":"object","properties":{"lookup_source":{"type":"string","enum":["openfoodfacts","upc-db","local-cache"]}},"required":["lookup_source"]}}

{"name":"describe_scene","description":"Context-aware scene description. Conditioned on the calling app's context (navigation vs social vs shopping vs kitchen). Returns description + calibrated confidence; abstains when image is too blurry or occluded.","parameters":{"type":"object","properties":{"image_token_budget":{"type":"integer","enum":[140,280,560]},"context":{"type":"string","enum":["navigation","social","shopping","kitchen","general"]},"detail_level":{"type":"string","enum":["brief","standard","detailed"]},"allow_demographic_inference":{"type":"boolean","default":false,"description":"Off by default; context-dependent per Stangl et al. ASSETS 2021"}},"required":["image_token_budget","context"]}}

{"name":"identify_color","description":"Identify dominant colors in the image or at a specific touch point. For clothing matching and color-blind queries.","parameters":{"type":"object","properties":{"mode":{"type":"string","enum":["dominant","at_point","palette"]},"x":{"type":"number"},"y":{"type":"number"}},"required":["mode"]}}

{"name":"translate_text","description":"Translate text using on-device ML Kit Translation. Use after read_document on foreign signs/menus.","parameters":{"type":"object","properties":{"source_lang":{"type":"string","description":"BCP-47 source; ML Kit auto-detects if omitted"},"target_lang":{"type":"string"},"text":{"type":"string"}},"required":["target_lang","text"]}}

{"name":"system_action","description":"Dispatch an Android intent: SMS, email, calendar event, phone call, or alarm. ALWAYS requires the user to verbally confirm the action before execution.","parameters":{"type":"object","properties":{"action":{"type":"string","enum":["send_sms","compose_email","create_event","call","alarm"]},"payload":{"type":"object","description":"Action-specific fields, e.g. {to,body} for send_sms or {minutes} for alarm"},"user_confirmation_phrase":{"type":"string","description":"The phrase the user spoke to confirm; logged for audit"}},"required":["action","payload","user_confirmation_phrase"]}}
""".trim()
}
