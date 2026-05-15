package org.cmu.gemmavision2.tools

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Result of a tool dispatch, serialized back into the model's context as
 *
 *     <gv:tool_response>{...}</gv:tool_response>
 *
 * (See ToolCallRouter.RESP_OPEN / RESP_CLOSE.) Always includes `ok` so the
 * model can branch cleanly on failure without parsing free text.
 */
@JsonClass(generateAdapter = true)
data class ToolResponse(
    val ok: Boolean,
    val data: Map<String, Any?>? = null,
    val error: String? = null,
) {
    fun toWireString(): String = adapter.toJson(this)

    companion object {
        private val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(ToolResponse::class.java)

        fun success(data: Map<String, Any?>) = ToolResponse(ok = true, data = data)
        fun error(message: String) = ToolResponse(ok = false, error = message)
    }
}
