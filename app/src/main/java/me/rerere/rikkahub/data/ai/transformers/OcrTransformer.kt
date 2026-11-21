package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.deepseek.deepSeekFileId
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.ai.UsageTag
import me.rerere.rikkahub.data.ai.tools.DeepSeekAttachmentService
import me.rerere.rikkahub.data.ai.tools.resolveDeepSeekOcrApiKey
import me.rerere.rikkahub.data.ai.tools.toDeepSeekAttachment
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.days

object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "ocr_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 64,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            expireAfterWriteMillis = 3.days.inWholeMilliseconds,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.IMAGE)) {
            return messages
        }

        val hasImages = messages.any { message ->
            message.parts.any { it is UIMessagePart.Image && it.url.startsWith("file:") && it.deepSeekFileId == null }
        }
        if (!hasImages) return messages

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = "正在识别图片..."
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.url.startsWith("file:") && part.deepSeekFileId == null -> {
                                    UIMessagePart.Text(performOcr(part, ctx.settings, ctx.context))
                                }

                                else -> part
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    suspend fun performOcr(part: UIMessagePart.Image): String = withContext(Dispatchers.IO) {
        performOcr(part, get<SettingsStore>().settingsFlow.value, get<Context>())
    }

    private suspend fun performOcr(part: UIMessagePart.Image, settings: Settings, context: Context): String = try {
        val input = if (settings.deepSeekOcrEnabled) {
            part.toDeepSeekAttachment(context)?.takeIf { it.supportsOcr }
        } else {
            null
        }
        if (input != null) {
            val key = settings.resolveDeepSeekOcrApiKey() ?: error("A DeepSeek OCR API key is required")
            val cacheKey = "deepseek:" + listOf(
                key, part.url, input.file.length().toString(), input.file.lastModified().toString(),
            ).joinToString("\u0000").sha256()
            cache.get(cacheKey) ?: run {
                val result = DeepSeekAttachmentService.client.recognize(key, input.file, input.fileName, input.mime)
                val text = result.pages.sortedBy { it.index }.joinToString("\n\n") { page ->
                    buildString {
                        if (result.pages.size > 1) appendLine("[Page ${page.index + 1}]")
                        append(page.text)
                        if (page.finishReason == "length") append("\n[OCR output truncated]")
                    }
                }
                check(text.isNotBlank()) { "DeepSeek OCR returned no text" }
                wrapOcrText(text).also { cache.put(cacheKey, it) }
            }
        } else {
            performModelOcr(part, settings)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "[ERROR, OCR failed: ${e.message}]"
    }

    private suspend fun performModelOcr(part: UIMessagePart.Image, settings: Settings): String {
        cache.get(part.url)?.let { return it }
        val model = settings.findModelById(settings.ocrModelId) ?: return "[Image]"
        val providerSetting = model.findProvider(settings.providers) ?: return "[Image]"
        val provider = get<UsageRecorder>().wrap(
            get<ProviderManager>().getProviderByType(providerSetting),
            UsageTag(UsageSource.OCR),
        )
        val result = provider.generateText(
            providerSetting = providerSetting,
            messages = listOf(
                UIMessage.system(settings.ocrPrompt),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Image(part.url))
                )
            ),
            params = TextGenerationParams(
                model = model,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val content = result.message.toText().ifBlank { "[ERROR, OCR failed]" }
        return wrapOcrText(content).also { cache.put(part.url, it) }
    }

    private fun wrapOcrText(content: String): String = """
        <image_file_ocr>
           $content
        </image_file_ocr>
        * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
    """.trimIndent()
}
