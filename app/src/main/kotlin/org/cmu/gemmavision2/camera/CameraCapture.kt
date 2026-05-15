package org.cmu.gemmavision2.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX wrapper.
 *
 * Single shutter call returns a [Bitmap] sized to the configured target.
 * We intentionally do NOT keep a preview running for the BLV use case —
 * the screen is rarely shown — but [Preview] hooks are kept available so
 * sighted-co-designer testing can use a viewfinder.
 */
class CameraCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {

    private var imageCapture: ImageCapture? = null

    suspend fun bind(preview: Preview? = null): Boolean = suspendCancellableCoroutine { cont ->
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(android.view.Surface.ROTATION_0)
                    .build()
                val useCases = listOfNotNull(preview, imageCapture)
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    *useCases.toTypedArray(),
                )
                if (cont.isActive) cont.resume(true)
            } catch (t: Throwable) {
                Log.e(TAG, "Camera bind failed", t)
                if (cont.isActive) cont.resume(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Capture a photo. On emulators the virtual camera sometimes hangs, so
     * we enforce a 10-second timeout and return a solid-grey placeholder on
     * timeout so inference can still proceed (demo / debugging).
     */
    suspend fun takePicture(): Bitmap? {
        // Check for a pushed test image (useful for demo/development via ADB)
        val testImage = loadTestImage()
        if (testImage != null) return testImage

        val result = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val capture = imageCapture
                if (capture == null) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                capture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val raw: Bitmap = image.toBitmap()
                                val rotation = image.imageInfo.rotationDegrees
                                val rotated = if (rotation != 0) {
                                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                                    val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                                    if (out !== raw) raw.recycle()
                                    out
                                } else raw
                                if (cont.isActive) cont.resume(rotated)
                            } catch (t: Throwable) {
                                if (cont.isActive) cont.resumeWithException(t)
                            } finally {
                                image.close()
                            }
                        }

                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Image capture failed", exc)
                            if (cont.isActive) cont.resumeWithException(exc)
                        }
                    },
                )
            }
        }
        if (result != null) return result

        // Timeout — return a small placeholder so inference runs anyway.
        Log.w(TAG, "Camera capture timed out after ${CAPTURE_TIMEOUT_MS}ms — using placeholder")
        return Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.DKGRAY)
        }
    }

    /**
     * Look for a test image pushed via ADB to the app's external files dir.
     * Supports .jpg, .jpeg, and .png extensions.
     *
     * Usage:
     *   adb push photo.jpg /sdcard/Android/data/org.cmu.gemmavision2/files/test_image.jpg
     *   adb shell rm /sdcard/Android/data/org.cmu.gemmavision2/files/test_image.jpg
     */
    private fun loadTestImage(): Bitmap? {
        val extensions = listOf("jpg", "jpeg", "png")
        val baseDir = context.getExternalFilesDir(null) ?: return null
        for (ext in extensions) {
            val file = java.io.File(baseDir, "test_image.$ext")
            if (file.exists() && file.length() > 0) {
                Log.i(TAG, "Using test image: ${file.absolutePath} (${file.length()} bytes)")
                return android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            }
        }
        return null
    }

    companion object {
        private const val TAG = "CameraCapture"
        private const val CAPTURE_TIMEOUT_MS = 10_000L
    }
}
