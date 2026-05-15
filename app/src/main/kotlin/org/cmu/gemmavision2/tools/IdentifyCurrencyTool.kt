package org.cmu.gemmavision2.tools

import android.graphics.Bitmap

/**
 * Currency identification tool.
 *
 * Approach: this tool does NOT itself classify the bill. Instead it returns
 * a structured prompt-stub that asks Gemma 4 to refocus on the captured image
 * with a tighter, currency-specific instruction. The model's vision head
 * already has strong currency priors (Gemma 4 model card lists currency
 * identification as a tested capability), so the win is in (a) reducing
 * abstain-rate by being explicit about token budget and (b) giving the user
 * the ISO 4217 code so the model never says "this is a 10" without context.
 *
 * Future: bundle a small dedicated currency classifier head for wrinkled /
 * partial bills. OrCam and Supersense ship one; we should too in v2.1.
 */
class IdentifyCurrencyTool {

    fun execute(args: Map<String, Any?>, image: Bitmap?): ToolResponse {
        image ?: return ToolResponse.error("No image captured")

        val hint = args["expected_currency_hint"]?.toString()
        val instruction = buildString {
            append("Re-examine the image at high attention. ")
            append("Identify the denomination and ISO 4217 currency code. ")
            append("Report front/back and any visible serial. ")
            if (!hint.isNullOrBlank()) append("Likely currency: $hint. ")
            append("If unsure, respond exactly: 'I can't tell which bill this is.'")
        }

        return ToolResponse.success(
            mapOf(
                "instruction_for_model" to instruction,
                "image_token_budget_used" to (args["image_token_budget"] ?: 280),
                "expected_currency_hint" to hint,
            )
        )
    }
}
