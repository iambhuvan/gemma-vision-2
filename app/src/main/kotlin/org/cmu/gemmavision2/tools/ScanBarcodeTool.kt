package org.cmu.gemmavision2.tools

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.cmu.gemmavision2.BuildConfig
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Detects a UPC/EAN/QR barcode in the captured image, then looks it up.
 *
 * Lookup sources:
 *  • openfoodfacts — anonymous public REST API, food products only
 *  • upc-db        — placeholder for a future commercial source
 *  • local-cache   — in-app history of previously scanned products
 *
 * If no barcode is detected, we return ok=false so the model falls back
 * to read_document on the package OCR.
 */
class ScanBarcodeTool(private val cache: ProductCache = ProductCache()) {

    private val scanner = BarcodeScanning.getClient()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun execute(args: Map<String, Any?>, image: Bitmap?): ToolResponse {
        image ?: return ToolResponse.error("No image captured")

        val source = args["lookup_source"]?.toString() ?: "openfoodfacts"
        val barcode = detectFirst(image)
            ?: return ToolResponse.error("No barcode detected; try read_document on the package")

        cache.get(barcode)?.let { return ToolResponse.success(it) }

        val product = withContext(Dispatchers.IO) {
            when (source) {
                "openfoodfacts" -> openFoodFactsLookup(barcode)
                "local-cache" -> null
                else -> openFoodFactsLookup(barcode)
            }
        } ?: return ToolResponse.error("Product not found for barcode $barcode")

        cache.put(barcode, product)
        return ToolResponse.success(product)
    }

    private suspend fun detectFirst(bitmap: Bitmap): String? = suspendCancellableCoroutine { cont ->
        val input = InputImage.fromBitmap(bitmap, 0)
        scanner.process(input)
            .addOnSuccessListener { barcodes: List<Barcode> ->
                cont.resume(barcodes.firstOrNull()?.rawValue)
            }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
            .addOnCanceledListener { cont.resume(null) }
    }

    private fun openFoodFactsLookup(barcode: String): Map<String, Any?>? = try {
        val req = Request.Builder()
            .url("https://world.openfoodfacts.org/api/v2/product/$barcode.json")
            .header("User-Agent", BuildConfig.OPENFOODFACTS_USER_AGENT)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            if (json.optInt("status") != 1) return null
            val product = json.optJSONObject("product") ?: return null
            mapOf(
                "barcode" to barcode,
                "name" to product.optString("product_name"),
                "brand" to product.optString("brands"),
                "quantity" to product.optString("quantity"),
                "allergens" to product.optString("allergens_imported"),
                "ingredients" to product.optString("ingredients_text"),
                "nutriscore" to product.optString("nutriscore_grade"),
            )
        }
    } catch (_: Throwable) {
        null
    }

    /** Tiny in-memory product cache. Cleared on process death. */
    class ProductCache(private val capacity: Int = 32) {
        private val store = object : LinkedHashMap<String, Map<String, Any?>>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Map<String, Any?>>) =
                size > capacity
        }

        @Synchronized fun get(key: String): Map<String, Any?>? = store[key]
        @Synchronized fun put(key: String, value: Map<String, Any?>) { store[key] = value }
    }
}
