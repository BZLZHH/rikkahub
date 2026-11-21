package me.rerere.ai.provider.deepseek

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart

private const val DEEPSEEK_FILE_ID = "deepseek_file_id"

/** Only set on copies of attachment parts in outgoing DeepSeek requests. */
val UIMessagePart.deepSeekFileId: String?
    get() = when (this) {
        is UIMessagePart.Image, is UIMessagePart.Document ->
            (metadata?.get(DEEPSEEK_FILE_ID) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        else -> null
    }

fun UIMessagePart.withDeepSeekFileId(fileId: String): UIMessagePart {
    val updatedMetadata = JsonObject(metadata.orEmpty() + (DEEPSEEK_FILE_ID to JsonPrimitive(fileId)))
    return when (this) {
        is UIMessagePart.Document -> copy(metadata = updatedMetadata)
        is UIMessagePart.Image -> copy(metadata = updatedMetadata)
        else -> this
    }
}
