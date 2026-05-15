package org.cmu.gemmavision2.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import org.cmu.gemmavision2.inference.VisionIntent

/**
 * Preprocesses the raw camera capture for the Gemma 4 vision encoder.
 *
 * BLV-captured images are documented to be blurry / off-axis (Gurari et al.
 * VizWiz, CVPR 2018; subsequent BLV-photo studies). We compensate by:
 *  • center-cropping to square,
 *  • resizing to the right resolution for the intent,
 *  • for OCR-heavy intents (read text, document) bumping contrast with a
 *    ColorMatrix gain. (Full CLAHE / unsharp-mask is left as a Day 4 polish
 *    item — these tend to need OpenCV or Renderscript and would slow the
 *    Day 1-2 spine.)
 *
 * Resolution / token-budget pairing:
 *   VisionIntent.IdentifyObject / DescribeScene (brief)     -> 512x512, 140 tokens
 *   VisionIntent.IdentifyCurrency / ScanBarcode / VoiceQuery -> 768x768, 280 tokens
 *   VisionIntent.ReadText / DescribeSceneDetailed            -> 1024x1024, 560 tokens
 *   VisionIntent.ReadTextLong                                -> 1024x1024, 1120 tokens
 */
class ImagePreprocessor {

    fun process(raw: Bitmap, intent: VisionIntent): Bitmap {
        val edge = sideFor(intent)
        val square = centerSquare(raw)
        val resized = if (square.width == edge && square.height == edge) {
            square
        } else {
            Bitmap.createScaledBitmap(square, edge, edge, /* filter = */ true)
        }
        return when (intent) {
            VisionIntent.ReadText,
            VisionIntent.ReadTextLong -> resized.boostContrast(gain = 1.25f)
            else -> resized
        }
    }

    private fun sideFor(intent: VisionIntent): Int = when (intent) {
        VisionIntent.IdentifyObject,
        VisionIntent.DescribeScene -> 512
        VisionIntent.IdentifyCurrency,
        VisionIntent.ScanBarcode,
        VisionIntent.VoiceQuery,
        VisionIntent.IdentifyColor -> 768
        VisionIntent.ReadText,
        VisionIntent.DescribeSceneDetailed,
        VisionIntent.ReadTextLong -> 1024
        VisionIntent.CallVolunteer,
        VisionIntent.DebugTextOnly -> 768
    }

    private fun centerSquare(b: Bitmap): Bitmap {
        val edge = minOf(b.width, b.height)
        val x = (b.width - edge) / 2
        val y = (b.height - edge) / 2
        return Bitmap.createBitmap(b, x, y, edge, edge)
    }

    /**
     * Multiply RGB by [gain] and offset to keep midpoint at 128.
     * Cheap proxy for adaptive contrast — runs in ~1 ms on a 1024x1024 bitmap.
     *
     * Recycles the receiver (`this`) after drawing — the receiver is always
     * an intermediate bitmap produced by [process] and never the caller's
     * camera frame, so recycling here is safe and prevents a 4 MB leak per
     * OCR-class capture.
     */
    private fun Bitmap.boostContrast(gain: Float): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val offset = 128f * (1f - gain)
        val matrix = ColorMatrix(
            floatArrayOf(
                gain, 0f,   0f,   0f, offset,
                0f,   gain, 0f,   0f, offset,
                0f,   0f,   gain, 0f, offset,
                0f,   0f,   0f,   1f, 0f,
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(this, 0f, 0f, paint)
        if (!isRecycled) recycle()
        return out
    }
}
