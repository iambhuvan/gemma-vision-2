package org.cmu.gemmavision2.tools

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * On-device translation via ML Kit. Used after read_document on foreign signs
 * or menus. Models download once per language pair and then run offline.
 */
class TranslateTextTool {

    private val languageId = LanguageIdentification.getClient()

    suspend fun execute(args: Map<String, Any?>): ToolResponse {
        val text = args["text"]?.toString().orEmpty()
        if (text.isBlank()) return ToolResponse.error("No text to translate")

        val targetTag = args["target_lang"]?.toString()
            ?: return ToolResponse.error("target_lang required")
        val target = TranslateLanguage.fromLanguageTag(targetTag)
            ?: return ToolResponse.error("Unsupported target_lang: $targetTag")

        val sourceTag = args["source_lang"]?.toString() ?: detectLanguage(text)
        val source = TranslateLanguage.fromLanguageTag(sourceTag)
            ?: return ToolResponse.error("Unsupported source_lang: $sourceTag")

        if (source == target) {
            return ToolResponse.success(mapOf("text" to text, "translated" to false))
        }

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
        )
        return try {
            // Ensure the on-device model is available before translating.
            withContext(Dispatchers.IO) {
                Tasks.await(
                    translator.downloadModelIfNeeded(
                        DownloadConditions.Builder().build()
                    )
                )
            }
            val translated = withContext(Dispatchers.IO) {
                Tasks.await(translator.translate(text))
            }
            ToolResponse.success(
                mapOf(
                    "source_lang" to sourceTag,
                    "target_lang" to targetTag,
                    "text" to translated,
                    "translated" to true,
                )
            )
        } finally {
            translator.close()
        }
    }

    private suspend fun detectLanguage(text: String): String =
        suspendCancellableCoroutine { cont ->
            languageId.identifyLanguage(text)
                .addOnSuccessListener { tag -> cont.resume(if (tag == "und") "en" else tag) }
                .addOnFailureListener { cont.resume("en") }
        }
}
