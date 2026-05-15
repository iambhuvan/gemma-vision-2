package org.cmu.gemmavision2.tools

import android.graphics.Bitmap

/**
 * Context-aware scene description tool.
 *
 * Implements the Stangl et al. (ASSETS 2021) and Context-Aware Image
 * Descriptions (ASSETS 2024) findings: BLV users want descriptions
 * conditioned on the context of encounter (navigation vs social vs
 * shopping vs kitchen).
 *
 * The tool itself doesn't run a separate model — Gemma 4 multimodal does
 * the description. This tool's job is to return an instruction-stub that
 * pushes the model toward the right tone, length, and content for the
 * context the caller declared.
 */
class DescribeSceneTool {

    fun execute(args: Map<String, Any?>, image: Bitmap?): ToolResponse {
        image ?: return ToolResponse.error("No image captured")

        val context = args["context"]?.toString() ?: "general"
        val detailLevel = args["detail_level"]?.toString() ?: "standard"
        val allowDemographic = (args["allow_demographic_inference"] as? Boolean) ?: false

        val templates = mapOf(
            "navigation" to "Describe what is in front of the user RIGHT NOW. Focus on path, obstacles, signage. State left/right ONLY if confident. NEVER say it is safe to proceed; describe and let the user decide.",
            "social" to "Describe the scene as you would to a sighted friend. Include demographic descriptors only if appropriate and user has opted in.",
            "shopping" to "Describe the product, packaging text, brand cues, and notable label warnings. Read prices and weights when visible.",
            "kitchen" to "Describe cooking state: color, doneness cues, visible steam, oil shimmer, what is in the pan. Do NOT adjudicate food safety.",
            "general" to "Describe the most salient subject and any text. Keep it brief unless detail_level is 'detailed'.",
        )

        val sentenceCap = when (detailLevel) {
            "brief" -> "Keep to 1 sentence."
            "detailed" -> "Up to 5 sentences."
            else -> "Up to 3 sentences."
        }

        val demographicRule = if (allowDemographic && context == "social")
            "User has opted into demographic descriptors; include race/age/gender cues when present and contextually relevant."
        else
            "Do not infer race, age, or gender."

        val instruction = (templates[context] ?: templates.getValue("general")) +
            " " + sentenceCap + " " + demographicRule +
            " If image is blurry or occluded: begin 'I can't tell from this image — ...'."

        return ToolResponse.success(
            mapOf(
                "instruction_for_model" to instruction,
                "context" to context,
                "detail_level" to detailLevel,
                "image_token_budget_used" to (args["image_token_budget"] ?: 280),
            )
        )
    }
}
