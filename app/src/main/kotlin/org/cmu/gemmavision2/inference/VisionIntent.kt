package org.cmu.gemmavision2.inference

/**
 * High-level user intents. Each intent maps to:
 *  • a button on the 8BitDo Micro,
 *  • a token budget for image preprocessing,
 *  • a prompt scaffold that nudges Gemma 4 toward the right tool call.
 *
 * Token budgets follow the Gemma 4 vision encoder's discrete settings:
 * 70 / 140 / 280 / 560 / 1120. See gemma-4 model card for the rationale.
 */
enum class VisionIntent(
    val tokenBudget: Int,
    val systemHint: String,
) {
    DescribeScene(280, "Describe the scene briefly. If image is blurry or occluded, say so."),
    DescribeSceneDetailed(560, "Describe the scene in detail. Read salient text aloud. Hedge on spatial claims."),
    ReadText(560, "Read the visible text in reading order. Preserve layout when possible."),
    ReadTextLong(1120, "Read the multi-page document or dense text. Preserve layout."),
    IdentifyObject(140, "Identify the most prominent object. One sentence."),
    IdentifyCurrency(280, "Identify currency denomination and side. Use identify_currency tool."),
    IdentifyColor(140, "Identify the dominant color. Use identify_color tool."),
    ScanBarcode(280, "Scan barcode; if not found, OCR the package. Use scan_barcode_then_lookup tool."),
    VoiceQuery(280, "Answer the user's spoken question about the captured image."),
    CallVolunteer(0, "Hand off to Be My Eyes volunteer."),
    /** Debug-only: text-only ping to verify the model is generating tokens. */
    DebugTextOnly(0, "Say hello in one sentence. No image needed."),
    ;

    /** Whether this intent requires an image capture before inference. */
    val requiresImage: Boolean get() = tokenBudget > 0 && this != CallVolunteer && this != DebugTextOnly
}
