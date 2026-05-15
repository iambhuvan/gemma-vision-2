package org.cmu.gemmavision2.tools

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR via ML Kit classical text recognition.
 *
 * Used in two modes:
 *   1. Standalone — when Gemma 4 emits read_document, this tool returns the
 *      raw, layout-preserved text directly to the model in a tool response;
 *      the model then re-renders it for the user.
 *   2. Reconciliation — Gemma 4's multimodal head produces its own OCR pass.
 *      For curved or low-contrast surfaces (medicine bottles, receipts) we
 *      compare both and prefer the classical pipeline.
 *
 * OCRBench v2 (NeurIPS 2025) reports most LMMs < 50/100 on real-world OCR;
 * a classical fallback significantly improves the worst case.
 */
class ReadDocumentTool {

    // Latin recognizer is bundled with ML Kit (no Google Play Services
    // download required). For CJK/Devanagari/Arabic, swap in the respective
    // language-specific recognizer module — we keep Latin as the safe default.
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun execute(args: Map<String, Any?>, image: Bitmap?): ToolResponse {
        image ?: return ToolResponse.error("No image captured")

        val mode = args["mode"]?.toString() ?: "document"

        val text = recognize(image)
            ?: return ToolResponse.error("OCR returned no text. Image may be too blurry or low-contrast.")

        return ToolResponse.success(
            mapOf(
                "mode" to mode,
                "text" to text.full,
                "blocks" to text.blocks,
            )
        )
    }

    private suspend fun recognize(bitmap: Bitmap): OcrResult? = suspendCancellableCoroutine { cont ->
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks.map { b ->
                    mapOf(
                        "text" to b.text,
                        "box" to b.boundingBox?.let {
                            listOf(it.left, it.top, it.right, it.bottom)
                        },
                    )
                }
                cont.resume(OcrResult(result.text, blocks))
            }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
            .addOnCanceledListener { cont.resume(null) }
    }

    private data class OcrResult(
        val full: String,
        val blocks: List<Map<String, Any?>>,
    )
}
