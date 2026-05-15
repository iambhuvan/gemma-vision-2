package org.cmu.gemmavision2.tools

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure-pixel color identification.
 *
 * Three modes:
 *   • dominant — single most common color in image
 *   • at_point — color sampled at (x, y) (normalized 0..1 or pixel coords)
 *   • palette  — 3-color palette via simple bucket histogram
 *
 * We avoid pulling in OpenCV/Palette libraries; this keeps the binary small.
 */
class IdentifyColorTool {

    suspend fun execute(args: Map<String, Any?>, image: Bitmap?): ToolResponse {
        image ?: return ToolResponse.error("No image captured")

        return withContext(Dispatchers.Default) {
            when (val mode = args["mode"]?.toString() ?: "dominant") {
                "dominant" -> {
                    val (rgb, name) = dominantColor(image)
                    ToolResponse.success(
                        mapOf("rgb" to rgbHex(rgb), "name" to name, "mode" to mode)
                    )
                }
                "at_point" -> {
                    val xv = (args["x"] as? Number)?.toDouble() ?: 0.5
                    val yv = (args["y"] as? Number)?.toDouble() ?: 0.5
                    val px = if (xv <= 1.0) (xv * (image.width - 1)).toInt() else xv.toInt()
                    val py = if (yv <= 1.0) (yv * (image.height - 1)).toInt() else yv.toInt()
                    val pixel = image.getPixel(
                        px.coerceIn(0, image.width - 1),
                        py.coerceIn(0, image.height - 1),
                    )
                    ToolResponse.success(
                        mapOf("rgb" to rgbHex(pixel), "name" to nameFromRgb(pixel), "mode" to mode)
                    )
                }
                "palette" -> {
                    val palette = paletteOf(image, k = 3)
                    ToolResponse.success(
                        mapOf(
                            "mode" to mode,
                            "palette" to palette.map {
                                mapOf("rgb" to rgbHex(it), "name" to nameFromRgb(it))
                            },
                        )
                    )
                }
                else -> ToolResponse.error("Unknown mode: $mode")
            }
        }
    }

    /** Bucket-histogram dominant color. Down-samples to 64x64 for speed. */
    private fun dominantColor(src: Bitmap): Pair<Int, String> {
        val small = Bitmap.createScaledBitmap(src, SAMPLE, SAMPLE, true)
        val histogram = IntArray(BUCKET_COUNT)
        for (y in 0 until SAMPLE) {
            for (x in 0 until SAMPLE) {
                histogram[bucketOf(small.getPixel(x, y))]++
            }
        }
        if (small != src) small.recycle()
        val winner = histogram.indices.maxBy { histogram[it] }
        val rgb = colorFromBucket(winner)
        return rgb to nameFromRgb(rgb)
    }

    private fun paletteOf(src: Bitmap, k: Int): List<Int> {
        val small = Bitmap.createScaledBitmap(src, SAMPLE, SAMPLE, true)
        val histogram = IntArray(BUCKET_COUNT)
        for (y in 0 until SAMPLE) for (x in 0 until SAMPLE) histogram[bucketOf(small.getPixel(x, y))]++
        if (small != src) small.recycle()
        return histogram.indices.sortedByDescending { histogram[it] }.take(k).map { colorFromBucket(it) }
    }

    private fun bucketOf(c: Int): Int {
        val r = (Color.red(c) ushr BUCKET_SHIFT)
        val g = (Color.green(c) ushr BUCKET_SHIFT)
        val b = (Color.blue(c) ushr BUCKET_SHIFT)
        return (r shl (2 * BUCKET_BITS)) or (g shl BUCKET_BITS) or b
    }

    private fun colorFromBucket(bucket: Int): Int {
        val mask = (1 shl BUCKET_BITS) - 1
        val r = (bucket ushr (2 * BUCKET_BITS)) and mask
        val g = (bucket ushr BUCKET_BITS) and mask
        val b = bucket and mask
        // Reconstitute centered in the bucket
        val rOut = (r shl BUCKET_SHIFT) or (1 shl (BUCKET_SHIFT - 1))
        val gOut = (g shl BUCKET_SHIFT) or (1 shl (BUCKET_SHIFT - 1))
        val bOut = (b shl BUCKET_SHIFT) or (1 shl (BUCKET_SHIFT - 1))
        return Color.rgb(rOut, gOut, bOut)
    }

    private fun rgbHex(c: Int): String = "#%02X%02X%02X".format(Color.red(c), Color.green(c), Color.blue(c))

    /** Lightweight color naming by HSV. Sufficient for "navy", "lime", "salmon" etc. */
    private fun nameFromRgb(c: Int): String {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        val (h, s, v) = Triple(hsv[0], hsv[1], hsv[2])
        if (v < 0.15f) return "black"
        if (s < 0.10f) return when {
            v > 0.90f -> "white"
            v > 0.65f -> "light gray"
            else -> "gray"
        }
        return when (h.toInt()) {
            in 0..14 -> if (v < 0.5f) "dark red" else "red"
            in 15..40 -> "orange"
            in 41..65 -> "yellow"
            in 66..160 -> if (v < 0.4f) "dark green" else "green"
            in 161..200 -> "cyan"
            in 201..250 -> if (v < 0.4f) "navy" else "blue"
            in 251..290 -> "purple"
            in 291..335 -> "pink"
            else -> if (v < 0.5f) "dark red" else "red"
        }
    }

    companion object {
        private const val SAMPLE = 64
        private const val BUCKET_BITS = 4
        private const val BUCKET_SHIFT = 8 - BUCKET_BITS
        private const val BUCKET_COUNT = 1 shl (3 * BUCKET_BITS)
    }
}
