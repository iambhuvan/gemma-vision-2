package org.cmu.gemmavision2.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log

/**
 * Dispatches Android intents on the model's behalf.
 *
 * Hard-rule per the system prompt: every system_action requires explicit
 * voice confirmation by the user. The confirmation phrase is logged in
 * [args["user_confirmation_phrase"]]; we audit-trail it but do NOT
 * cryptographically verify (out of scope for v0).
 *
 * All Intents flagged FLAG_ACTIVITY_NEW_TASK so we can dispatch from a
 * background coroutine without an Activity context.
 */
class SystemActionTool(private val app: Context) {

    fun execute(args: Map<String, Any?>): ToolResponse {
        val action = args["action"]?.toString() ?: return ToolResponse.error("Missing action")
        val payload = @Suppress("UNCHECKED_CAST") (args["payload"] as? Map<String, Any?>)
            ?: return ToolResponse.error("Missing payload")
        val confirmation = args["user_confirmation_phrase"]?.toString().orEmpty()
        if (confirmation.isBlank()) {
            return ToolResponse.error("Refusing: no user_confirmation_phrase. Confirm verbally first.")
        }
        Log.i(TAG, "system_action=$action confirmed_by='$confirmation'")

        return try {
            when (action) {
                "send_sms" -> sendSms(payload)
                "compose_email" -> composeEmail(payload)
                "create_event" -> createCalendarEvent(payload)
                "call" -> placeCall(payload)
                "alarm" -> setAlarm(payload)
                else -> ToolResponse.error("Unknown action: $action")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "system_action $action failed", t)
            ToolResponse.error("Failed: ${t.message}")
        }
    }

    private fun sendSms(p: Map<String, Any?>): ToolResponse {
        val to = p["to"]?.toString() ?: return ToolResponse.error("Missing 'to'")
        val body = p["body"]?.toString().orEmpty()
        // Composer (not silent send) so the user sees / hears confirmation.
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$to")
            putExtra("sms_body", body)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        app.startActivity(intent)
        return ToolResponse.success(mapOf("staged" to "sms", "to" to to))
    }

    private fun composeEmail(p: Map<String, Any?>): ToolResponse {
        val to = p["to"]?.toString().orEmpty()
        val subject = p["subject"]?.toString().orEmpty()
        val body = p["body"]?.toString().orEmpty()
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        app.startActivity(intent)
        return ToolResponse.success(mapOf("staged" to "email", "to" to to))
    }

    private fun createCalendarEvent(p: Map<String, Any?>): ToolResponse {
        val title = p["title"]?.toString().orEmpty()
        val start = (p["start_epoch_millis"] as? Number)?.toLong()
        val end = (p["end_epoch_millis"] as? Number)?.toLong()
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            start?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
            end?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        app.startActivity(intent)
        return ToolResponse.success(mapOf("staged" to "calendar", "title" to title))
    }

    private fun placeCall(p: Map<String, Any?>): ToolResponse {
        val number = p["number"]?.toString() ?: return ToolResponse.error("Missing 'number'")
        // ACTION_DIAL (not ACTION_CALL) — user taps to actually place call.
        // Safer for accessibility: avoids accidental dials.
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        app.startActivity(intent)
        return ToolResponse.success(mapOf("staged" to "dialer", "number" to number))
    }

    private fun setAlarm(p: Map<String, Any?>): ToolResponse {
        val minutes = (p["minutes"] as? Number)?.toInt()
        val message = p["message"]?.toString() ?: "Gemma Vision alarm"
        if (minutes != null) {
            // Relative timer via android.intent.action.SET_TIMER
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            app.startActivity(intent)
            return ToolResponse.success(mapOf("staged" to "timer", "minutes" to minutes))
        }
        val hour = (p["hour"] as? Number)?.toInt() ?: return ToolResponse.error("Missing 'minutes' or 'hour'")
        val minute = (p["minute"] as? Number)?.toInt() ?: 0
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        app.startActivity(intent)
        return ToolResponse.success(mapOf("staged" to "alarm", "hour" to hour, "minute" to minute))
    }

    companion object {
        private const val TAG = "SystemActionTool"
    }
}
