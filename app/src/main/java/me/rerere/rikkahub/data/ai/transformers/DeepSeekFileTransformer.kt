package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.deepseek.DeepSeekFilePurpose
import me.rerere.ai.provider.deepseek.withDeepSeekFileId
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.rikkahub.data.ai.tools.DeepSeekAttachment
import me.rerere.rikkahub.data.ai.tools.DeepSeekAttachmentKind
import me.rerere.rikkahub.data.ai.tools.DeepSeekAttachmentService
import me.rerere.rikkahub.data.ai.tools.toDeepSeekAttachment
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.time.Duration.Companion.days

/** Runs only on the request copy, ahead of local document parsing and image OCR. */
internal class DeepSeekFileTransformer(
    private val apiKey: String,
    private val documentPurpose: DeepSeekFilePurpose,
) : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        withContext(Dispatchers.IO) {
            val inputs = messages.map { message ->
                if (message.role == MessageRole.USER) {
                    message.parts.map { it.toDeepSeekAttachment(ctx.context) }
                } else {
                    emptyList()
                }
            }
            if (inputs.none { row -> row.any { it != null } }) return@withContext messages
            require(inputs.sumOf { row -> row.count { it != null && it.kind != DeepSeekAttachmentKind.IMAGE } } <= 20) {
                "DeepSeek supports at most 20 documents per request"
            }
            ctx.processingStatus.value = "Uploading attachments to DeepSeek…"
            try {
                messages.mapIndexed { messageIndex, message ->
                    val row = inputs[messageIndex]
                    if (row.none { it != null }) return@mapIndexed message
                    message.copy(parts = message.parts.mapIndexed parts@{ partIndex, part ->
                        val input = row[partIndex] ?: return@parts part
                        val purpose = if (input.kind == DeepSeekAttachmentKind.PARSED) {
                            documentPurpose
                        } else {
                            DeepSeekFilePurpose.USER_DATA
                        }
                        val fileId = try {
                            DeepSeekFileIds.getOrUpload(ctx.context, apiKey, input, purpose)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw IOException("Could not upload ${input.fileName} to DeepSeek: ${e.message}", e)
                        }
                        val wirePart = when {
                            part is UIMessagePart.Document && input.kind == DeepSeekAttachmentKind.IMAGE ->
                                UIMessagePart.Image(part.url, part.metadata)
                            part is UIMessagePart.Image && input.kind == DeepSeekAttachmentKind.PARSED ->
                                UIMessagePart.Document(part.url, input.fileName, input.mime.orEmpty(), part.metadata)
                            else -> part
                        }
                        wirePart.withDeepSeekFileId(fileId)
                    })
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
}

private object DeepSeekFileIds {
    private val locks = Array(32) { Mutex() }
    private lateinit var cache: LruCache<String, String>

    @Synchronized
    private fun cacheFor(context: Context): LruCache<String, String> {
        if (!::cache.isInitialized) {
            cache = LruCache(
                capacity = 256,
                store = SingleFileCacheStore(
                    file = File(context.cacheDir, "deepseek_file_ids.json"),
                    keySerializer = String.serializer(),
                    valueSerializer = String.serializer(),
                ),
                deleteOnEvict = true,
                preloadFromStore = true,
                expireAfterWriteMillis = 29.days.inWholeMilliseconds,
            )
        }
        return cache
    }

    suspend fun getOrUpload(
        context: Context,
        apiKey: String,
        input: DeepSeekAttachment,
        purpose: DeepSeekFilePurpose,
    ): String {
        // App uploads use unique file names. Include file attributes to invalidate changed files.
        val cacheKey = listOf(
            apiKey.trim(), input.file.path, input.fileName, input.mime.orEmpty(),
            input.file.length().toString(), input.file.lastModified().toString(), purpose.name,
        ).joinToString("\u0000").sha256()
        return locks[(cacheKey.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val ids = cacheFor(context)
            ids.get(cacheKey)?.let { return@withLock it }
            DeepSeekAttachmentService.client.upload(
                apiKey = apiKey,
                file = input.file,
                fileName = input.fileName,
                mime = input.mime,
                purpose = purpose,
                isImage = input.kind == DeepSeekAttachmentKind.IMAGE,
                expiresInSeconds = 30.days.inWholeSeconds,
            ).also { ids.put(cacheKey, it) }
        }
    }
}

internal fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
