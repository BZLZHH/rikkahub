package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.deepseek.DeepSeekAttachmentClient
import me.rerere.ai.provider.deepseek.DeepSeekFilePurpose
import me.rerere.ai.provider.primaryDeepSeekApiKey
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File

private const val TOOL_NAME = "deepseek_ocr"

internal object DeepSeekAttachmentService : KoinComponent {
    val client by lazy { DeepSeekAttachmentClient(get()) }
}

internal fun Settings.resolveDeepSeekOcrApiKey(): String? {
    deepSeekOcrApiKey.primaryDeepSeekApiKey().takeIf { it.isNotEmpty() }?.let { return it }
    return providers.asSequence()
        .filterIsInstance<ProviderSetting.ApiKey>()
        .filter { provider ->
            provider.enabled && (provider is ProviderSetting.OpenAI || provider is ProviderSetting.Claude) &&
                provider.baseUrl.toHttpUrlOrNull()?.let { url ->
                    url.isHttps && url.host == "api.deepseek.com" && url.port == 443 &&
                        url.username.isEmpty() && url.password.isEmpty()
                } == true
        }
        .map { it.apiKey.primaryDeepSeekApiKey() }
        .firstOrNull { it.isNotEmpty() }
}

/** Only attachments from the current user message are addressable by the model. */
internal fun buildDeepSeekOcrTool(
    context: Context,
    apiKey: String,
    attachments: List<UIMessagePart>,
): Tool? {
    val inputs = attachments.mapNotNull { part ->
        part.toDeepSeekAttachment(context)?.takeIf { it.supportsOcr }
    }
    if (inputs.isEmpty()) return null

    return Tool(
        name = TOOL_NAME,
        description = "Recognize text in an attached document or image with the standalone DeepSeek OCR service. Returns numbered pages, layout markers, and truncation status.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("attachment", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", inputs.size)
                        put("description", "The 1-based attachment number listed in the system prompt.")
                    })
                    put("purpose", buildJsonObject {
                        put("type", "string")
                        put("description", "Use text_recognition by default, or rich_layout_recognition for full page layout.")
                        put("enum", JsonArray(listOf(JsonPrimitive("text_recognition"), JsonPrimitive("rich_layout_recognition"))))
                    })
                },
                required = listOf("attachment"),
            )
        },
        systemPrompt = { _, _ ->
            inputs.mapIndexed { index, attachment ->
                "${index + 1}. ${JsonPrimitive(attachment.fileName)}"
            }.joinToString("\n", prefix = "User attachments eligible for deepseek_ocr:\n")
        },
        needsApproval = { true },
        execute = { arguments ->
            val args = arguments as? JsonObject ?: error("Expected an OCR tool argument object")
            val number = (args["attachment"] as? JsonPrimitive)?.intOrNull
            require(number != null && number in 1..inputs.size) { "Select a listed attachment number" }
            val purpose = when (args["purpose"]) {
                null, JsonPrimitive("text_recognition") -> DeepSeekFilePurpose.TEXT_RECOGNITION
                JsonPrimitive("rich_layout_recognition") -> DeepSeekFilePurpose.RICH_LAYOUT_RECOGNITION
                else -> throw IllegalArgumentException("Invalid DeepSeek OCR purpose")
            }
            val result = withContext(Dispatchers.IO) {
                val input = inputs[number - 1]
                val file = resolveAppUpload(context, input.file.toUri().toString())
                    ?: error("Attachment is no longer available")
                DeepSeekAttachmentService.client.recognize(apiKey, file, input.fileName, input.mime, purpose)
            }
            listOf(UIMessagePart.Text(Json.encodeToString(result)))
        },
    )
}

internal data class DeepSeekAttachment(
    val file: File,
    val fileName: String,
    val mime: String?,
    val kind: DeepSeekAttachmentKind,
) {
    val supportsOcr: Boolean
        get() = kind != DeepSeekAttachmentKind.USER_DATA
}

internal enum class DeepSeekAttachmentKind { IMAGE, PARSED, USER_DATA }

private val imageExtensions = setOf("jpeg", "jpg", "png", "gif", "webp")
private val imageMimes = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
private val parsedExtensions = setOf("pdf", "doc", "docx", "ppt", "pptx", "bmp", "tif", "tiff")
private val parsedMimes = setOf(
    "application/pdf", "application/msword", "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "image/bmp", "image/tiff",
)
private val userDataExtensions = setOf(
    "xls", "xlsx", "epub", "mobi", "txt", "md", "csv", "tsv", "log", "json", "xml", "yaml", "yml",
    "html", "htm", "css", "js", "ts", "tsx", "jsx", "py", "java", "kt", "kts", "go", "rs", "rb", "php",
    "swift", "c", "cpp", "h", "sh", "sql", "toml", "ini", "rst", "tex",
)
private val userDataMimes = setOf(
    "application/json", "application/xml", "application/yaml", "application/javascript", "application/epub+zip",
    "application/x-mobipocket-ebook", "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
)

internal fun UIMessagePart.toDeepSeekAttachment(context: Context): DeepSeekAttachment? {
    val (uri, displayName, declaredMime) = when (this) {
        is UIMessagePart.Document -> Triple(url, fileName, mime)
        is UIMessagePart.Image -> Triple(url, null, null)
        else -> return null
    }
    val file = resolveAppUpload(context, uri) ?: return null
    val name = displayName?.substringAfterLast('/')?.substringAfterLast('\\')?.ifBlank { null } ?: file.name
    val extension = name.substringAfterLast('.', "").lowercase().ifEmpty { file.extension.lowercase() }
    val normalizedMime = declaredMime?.substringBefore(';')?.trim()?.lowercase()
    val kind = when {
        extension in imageExtensions || normalizedMime in imageMimes -> DeepSeekAttachmentKind.IMAGE
        extension in parsedExtensions || normalizedMime in parsedMimes -> DeepSeekAttachmentKind.PARSED
        extension in userDataExtensions || normalizedMime in userDataMimes ||
            (normalizedMime != null && normalizedMime != "text/*" && normalizedMime.startsWith("text/")) ->
            DeepSeekAttachmentKind.USER_DATA
        else -> return null
    }
    return DeepSeekAttachment(file, name, normalizedMime?.takeUnless { it.contains('*') }, kind)
}

private fun resolveAppUpload(context: Context, uri: String): File? = runCatching {
    val uploadDir = context.filesDir.resolve(FileFolders.UPLOAD).canonicalFile
    val file = uri.toUri().toFile().canonicalFile
    file.takeIf { it.parentFile == uploadDir && it.isFile }
}.getOrNull()
