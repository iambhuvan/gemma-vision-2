package org.cmu.gemmavision2.input

import android.view.InputDevice
import android.view.KeyEvent
import org.cmu.gemmavision2.inference.VisionIntent

/**
 * Translates 8BitDo Micro (or any Bluetooth HID gamepad) key events into
 * [VisionIntent] dispatches.
 *
 * Default mapping — matches the original Gemma Vision (Giovannini 2025)
 * spirit but expands to cover the 7-tool surface of v2.0:
 *
 *   A      -> DescribeScene
 *   B      -> ReadText
 *   X      -> IdentifyObject
 *   Y      -> ScanBarcode
 *   L1     -> IdentifyCurrency
 *   R1     -> IdentifyColor
 *   START  -> VoiceQuery (push-to-talk)
 *   SELECT -> CallVolunteer (Be My Eyes handoff)
 *
 * Holding L2 + a face button reads back the LAST captured image's history
 * (handled in MainActivity, not here).
 */
class GamepadInputHandler(private val onIntent: (VisionIntent) -> Unit) {

    /**
     * Consume a KeyEvent if it originates from a gamepad and maps to an intent.
     * Returns true when consumed; false lets the system route the event normally.
     */
    fun handle(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount != 0) return false  // ignore key-held repeats
        if (!event.fromGamepad()) return false

        val intent = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> VisionIntent.DescribeScene
            KeyEvent.KEYCODE_BUTTON_B -> VisionIntent.ReadText
            KeyEvent.KEYCODE_BUTTON_X -> VisionIntent.IdentifyObject
            KeyEvent.KEYCODE_BUTTON_Y -> VisionIntent.ScanBarcode
            KeyEvent.KEYCODE_BUTTON_L1 -> VisionIntent.IdentifyCurrency
            KeyEvent.KEYCODE_BUTTON_R1 -> VisionIntent.IdentifyColor
            KeyEvent.KEYCODE_BUTTON_START -> VisionIntent.VoiceQuery
            KeyEvent.KEYCODE_BUTTON_SELECT -> VisionIntent.CallVolunteer
            else -> null
        } ?: return false
        onIntent(intent)
        return true
    }

    private fun KeyEvent.fromGamepad(): Boolean {
        val src = device?.sources ?: source
        return src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }
}
