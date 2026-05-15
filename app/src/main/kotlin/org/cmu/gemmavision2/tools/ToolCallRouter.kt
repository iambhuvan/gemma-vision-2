package org.cmu.gemmavision2.tools

import android.graphics.Bitmap
import android.util.Log

/**
 * Parses our custom tool-call protocol out of the Gemma 4 token stream and
 * dispatches each call to its concrete tool.
 *
 * Why a custom protocol instead of Gemma 4's documented native tokens?
 * The Gemma 4 native function-calling tokens are asymmetric (`<|tool_call>`
 * open, `<tool_call|>` close) and embed a non-JSON DSL payload
 * (`call:name{k:<|"|>v<|"|>}`). For a 5-day hackathon we prefer a simpler,
 * deterministic protocol the model can be system-prompt-instructed to emit:
 *
 *     <gv:tool_call>{"name":"<tool>","arguments":{...}}</gv:tool_call>
 *
 * The system prompt teaches the model this format. Parsing is a plain
 * substring search + JSON decode, with a hard buffer cap to avoid runaway
 * memory when generation truncates mid-tag.
 *
 * Tool responses are re-injected as:
 *
 *     <gv:tool_response>{"ok":true,"data":{...}}</gv:tool_response>
 *
 * Caller (MainActivity.runOneTurn) implements a multi-hop generation loop:
 * append the tool-call span + tool-response wrapper to the prompt and
 * re-invoke generateStream until the model emits no further tool call or
 * MAX_HOPS is reached.
 */
class ToolCallRouter(
    private val currencyTool: IdentifyCurrencyTool,
    private val documentTool: ReadDocumentTool,
    private val barcodeTool: ScanBarcodeTool,
    private val sceneTool: DescribeSceneTool,
    private val colorTool: IdentifyColorTool,
    private val translateTool: TranslateTextTool,
    private val systemTool: SystemActionTool,
) {

    /**
     * Find a complete tool-call span in [buffer]. Returns the parsed call,
     * or null if no complete span yet exists.
     *
     * Buffer is bounded by [MAX_BUFFER_CHARS]; callers should periodically
     * call [trimBuffer] to enforce the cap.
     */
    fun tryParse(buffer: String): ToolCall? {
        val open = buffer.indexOf(OPEN_TAG)
        if (open < 0) return null
        val payloadStart = open + OPEN_TAG.length
        val close = buffer.indexOf(CLOSE_TAG, payloadStart)
        if (close < 0) return null
        val json = buffer.substring(payloadStart, close).trim()
        return ToolCall.fromJson(json)
    }

    /** Returns the buffer with the first complete tool-call span removed. */
    fun stripFirstSpan(buffer: String): String {
        val open = buffer.indexOf(OPEN_TAG)
        if (open < 0) return buffer
        val payloadStart = open + OPEN_TAG.length
        val close = buffer.indexOf(CLOSE_TAG, payloadStart)
        if (close < 0) return buffer
        return buffer.removeRange(open, close + CLOSE_TAG.length)
    }

    /**
     * The text portion BEFORE any open tag — safe to flush to TTS without
     * leaking partial tag fragments. Keeps a tail equal to OPEN_TAG.length-1
     * so that a tag arriving across two stream chunks isn't split.
     */
    fun safePrefixToSpeak(buffer: String): String {
        val open = buffer.indexOf(OPEN_TAG)
        if (open >= 0) return buffer.substring(0, open)
        val keep = OPEN_TAG.length - 1
        return if (buffer.length > keep) buffer.substring(0, buffer.length - keep) else ""
    }

    /** Remove the safe-to-speak prefix from [buffer]; only call after speaking it. */
    fun consumeSafePrefix(buffer: StringBuilder) {
        val prefix = safePrefixToSpeak(buffer.toString())
        if (prefix.isNotEmpty()) buffer.delete(0, prefix.length)
    }

    /**
     * Cap unbounded buffer growth on truncated streams. Drops the oldest
     * data with a logged warning. Should be called by the caller after
     * each chunk append.
     */
    fun trimBuffer(buffer: StringBuilder) {
        if (buffer.length > MAX_BUFFER_CHARS) {
            val drop = buffer.length - MAX_BUFFER_CHARS
            Log.w(TAG, "Buffer exceeded ${MAX_BUFFER_CHARS}; dropping $drop oldest chars")
            buffer.delete(0, drop)
        }
    }

    /** Dispatch a parsed call. */
    suspend fun dispatch(call: ToolCall, image: Bitmap?): ToolResponse = try {
        when (call.name) {
            "identify_currency" -> currencyTool.execute(call.arguments, image)
            "read_document" -> documentTool.execute(call.arguments, image)
            "scan_barcode_then_lookup" -> barcodeTool.execute(call.arguments, image)
            "describe_scene" -> sceneTool.execute(call.arguments, image)
            "identify_color" -> colorTool.execute(call.arguments, image)
            "translate_text" -> translateTool.execute(call.arguments)
            "system_action" -> systemTool.execute(call.arguments)
            else -> ToolResponse.error("Unknown tool: ${call.name}")
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Tool '${call.name}' threw", t)
        ToolResponse.error("Tool '${call.name}' failed: ${t.message}")
    }

    /** Encode a tool response back to wire format for re-injection. */
    fun encodeResponse(response: ToolResponse): String =
        "$RESP_OPEN${response.toWireString()}$RESP_CLOSE"

    /** Encode an outgoing tool call (used when re-prompting the model with history). */
    fun encodeCall(call: ToolCall): String =
        "$OPEN_TAG${call.toWireString()}$CLOSE_TAG"

    companion object {
        const val OPEN_TAG = "<gv:tool_call>"
        const val CLOSE_TAG = "</gv:tool_call>"
        const val RESP_OPEN = "<gv:tool_response>"
        const val RESP_CLOSE = "</gv:tool_response>"

        /** Hard cap on the streaming-parse buffer. ~64 KB of text. */
        const val MAX_BUFFER_CHARS = 65_536

        private const val TAG = "ToolCallRouter"
    }
}
