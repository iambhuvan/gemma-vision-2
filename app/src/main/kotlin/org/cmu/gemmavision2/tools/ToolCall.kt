package org.cmu.gemmavision2.tools

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.squareup.moshi.Types

/**
 * A single tool invocation emitted by the model between our custom tags:
 *
 *     <gv:tool_call>{"name":"<tool>","arguments":{...}}</gv:tool_call>
 *
 * The model is instructed to use this format by the system prompt (see
 * SystemPrompt.kt). We deliberately do NOT use Gemma 4's native function-
 * calling tokens (asymmetric `<|tool_call>` + DSL payload) because the
 * native protocol is harder to parse correctly under streaming. Custom
 * JSON-in-XML is more robust for the hackathon timeline.
 */
@JsonClass(generateAdapter = true)
data class ToolCall(
    val name: String,
    val arguments: Map<String, Any?> = emptyMap(),
) {
    fun toWireString(): String = adapter.toJson(this)

    companion object {
        private val moshi: Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        private val adapter: JsonAdapter<ToolCall> =
            moshi.adapter(ToolCall::class.java).lenient()

        /** Parse one tool-call JSON payload. Returns null on malformed input. */
        fun fromJson(json: String): ToolCall? = try {
            adapter.fromJson(json.trim())
        } catch (_: Throwable) {
            null
        }
    }
}
