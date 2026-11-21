package me.rerere.ai.provider.deepseek

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.util.json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

@Serializable
enum class DeepSeekFilePurpose(internal val wireName: String) {
    @SerialName("user_data")
    USER_DATA("user_data"),

    @SerialName("text_recognition")
    TEXT_RECOGNITION("text_recognition"),

    @SerialName("rich_layout_recognition")
    RICH_LAYOUT_RECOGNITION("rich_layout_recognition"),
}

@Serializable
data class DeepSeekOcrResult(
    val id: String,
    val purpose: DeepSeekFilePurpose,
    @SerialName("page_count") val pageCount: Int,
    val pages: List<DeepSeekOcrPage>,
    val usage: DeepSeekOcrUsage? = null,
)

@Serializable
data class DeepSeekOcrPage(
    val index: Int,
    val text: String,
    @SerialName("finish_reason") val finishReason: String,
)

@Serializable
data class DeepSeekOcrUsage(
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)

private const val MIB = 1024L * 1024
private const val FILES_URL = "https://api.deepseek.com/files"
private const val OCR_URL = "https://api.deepseek.com/v1/tools/ocr"

/** Streams attachments to the official Files and standalone OCR endpoints. */
class DeepSeekAttachmentClient internal constructor(private val calls: Call.Factory) {
    constructor(httpClient: OkHttpClient) : this(attachmentTransport(httpClient))

    suspend fun upload(
        apiKey: String,
        file: File,
        fileName: String,
        mime: String?,
        purpose: DeepSeekFilePurpose,
        isImage: Boolean = false,
        expiresInSeconds: Long? = null,
    ): String {
        val maxMiB = when {
            purpose != DeepSeekFilePurpose.USER_DATA -> 100
            isImage -> 64
            else -> 32
        }
        require(expiresInSeconds == null || expiresInSeconds in 3_600..2_592_000) {
            "DeepSeek file expiry must be between 1 hour and 30 days"
        }
        val response = post(
            url = FILES_URL,
            apiKey = apiKey,
            file = file,
            fileName = fileName,
            mime = mime,
            purpose = purpose,
            maxMiB = maxMiB,
            timeoutSeconds = if (purpose == DeepSeekFilePurpose.USER_DATA) 610 else 245,
            expiresInSeconds = expiresInSeconds,
        )
        return (response["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.takeIf { it.startsWith("file-api-") && it.length > "file-api-".length }
            ?: throw IOException("DeepSeek Files returned no file ID")
    }

    suspend fun recognize(
        apiKey: String,
        file: File,
        fileName: String,
        mime: String?,
        purpose: DeepSeekFilePurpose = DeepSeekFilePurpose.TEXT_RECOGNITION,
    ): DeepSeekOcrResult {
        require(purpose != DeepSeekFilePurpose.USER_DATA) { "Choose a DeepSeek OCR recognition purpose" }
        val response = post(OCR_URL, apiKey, file, fileName, mime, purpose, maxMiB = 100, timeoutSeconds = 185)
        return try {
            val result = json.decodeFromJsonElement(DeepSeekOcrResult.serializer(), response)
            require(result.pageCount == result.pages.size && result.purpose == purpose)
            result
        } catch (e: IllegalArgumentException) {
            throw IOException("DeepSeek OCR returned an invalid result", e)
        }
    }

    private suspend fun post(
        url: String,
        apiKey: String,
        file: File,
        fileName: String,
        mime: String?,
        purpose: DeepSeekFilePurpose,
        maxMiB: Int,
        timeoutSeconds: Long,
        expiresInSeconds: Long? = null,
    ): JsonObject {
        val key = apiKey.trim()
        require(key.isNotEmpty()) { "A DeepSeek API key is required" }
        require(key.all { it.code in 33..126 }) { "The DeepSeek API key contains invalid characters" }
        require(file.isFile) { "Attachment is no longer available" }
        require(file.length() <= maxMiB * MIB) { "The DeepSeek attachment limit is $maxMiB MiB" }
        val body = deepSeekMultipartBody(file, fileName, mime, purpose, expiresInSeconds)
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        val call = calls.newCall(request)
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        return call.readJson(key)
    }
}

private fun attachmentTransport(httpClient: OkHttpClient): Call.Factory = httpClient.newBuilder().apply {
    // Preserve the configured network transport, but exclude request tracing and retries.
    interceptors().clear()
    networkInterceptors().clear()
    followRedirects(false)
    followSslRedirects(false)
    retryOnConnectionFailure(false)
    readTimeout(130, TimeUnit.SECONDS)
    writeTimeout(30, TimeUnit.SECONDS)
}.build()

internal fun deepSeekMultipartBody(
    file: File,
    fileName: String,
    mime: String?,
    purpose: DeepSeekFilePurpose,
    expiresInSeconds: Long? = null,
): MultipartBody = MultipartBody.Builder().setType(MultipartBody.FORM)
    .addFormDataPart("purpose", purpose.wireName)
    .apply {
        if (expiresInSeconds != null) {
            addFormDataPart("expires_after[anchor]", "created_at")
            addFormDataPart("expires_after[seconds]", expiresInSeconds.toString())
        }
    }
    .addFormDataPart("file", fileName.ifBlank { file.name }, file.asRequestBody(mime?.toMediaTypeOrNull()))
    .build()

private suspend fun Call.readJson(apiKey: String): JsonObject = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resumeWith(runCatching {
                response.use {
                    decodeDeepSeekAttachmentResponse(response.code, response.body.string(), apiKey)
                }
            })
        }
    })
}

internal fun decodeDeepSeekAttachmentResponse(status: Int, body: String, apiKey: String): JsonObject {
    val response = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val error = response?.get("error")
    if (status !in 200..299 || error != null) {
        val message = ((error as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?.let { if (apiKey.isEmpty()) it else it.replace(apiKey, "[redacted]") }
            ?.take(512)
        val failure = if (status in 200..299) "DeepSeek attachment processing failed" else "DeepSeek attachment request failed (HTTP $status)"
        throw IOException(if (message.isNullOrBlank()) failure else "$failure: $message")
    }
    return response ?: throw IOException("DeepSeek returned an invalid attachment response")
}
