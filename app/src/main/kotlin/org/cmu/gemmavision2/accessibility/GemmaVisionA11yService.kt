package org.cmu.gemmavision2.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Cooperative accessibility service.
 *
 * Purpose: enrich Gemma 4's prompt with the user's current foreground-app
 * context (e.g. "WhatsApp", "Google Maps"), per the Stangl et al.
 * (ASSETS 2021) finding that context-aware descriptions outperform
 * one-size-fits-all on every BLV-measured axis.
 *
 * Hard rule: this service NEVER performs AccessibilityActions or drives
 * UI. TalkBack remains the authoritative screen-reader; we only READ
 * window state. This is explicit in the user-facing service description
 * (res/values/strings.xml).
 */
class GemmaVisionA11yService : AccessibilityService() {

    @Volatile var lastForegroundPackage: String? = null
        private set

    @Volatile var lastWindowTitle: String? = null
        private set

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                event.packageName?.toString()?.let { lastForegroundPackage = it }
                event.text?.firstOrNull()?.toString()?.let { lastWindowTitle = it }
            }
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "A11y interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        INSTANCE = this
        Log.i(TAG, "Connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        INSTANCE = null
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "GemmaVisionA11y"

        @Volatile private var INSTANCE: GemmaVisionA11yService? = null
        fun get(): GemmaVisionA11yService? = INSTANCE
    }
}
