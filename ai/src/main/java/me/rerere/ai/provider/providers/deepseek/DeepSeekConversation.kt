package me.rerere.ai.provider.providers.deepseek

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.deepSeekToolsEnabled
import me.rerere.ai.provider.primaryDeepSeekApiKey
import me.rerere.ai.provider.supportsDeepSeekFlash
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import java.security.MessageDigest

internal const val DEEPSEEK_WEB_SEARCH = "deepseek_web_search"
internal const val DEEPSEEK_READ_URL = "deepseek_read_url"
internal const val DEEPSEEK_FILES_BETA = "files-api-2025-04-14"
internal const val DEEPSEEK_REFUSAL = "DeepSeek could not provide a response because it did not pass content review."

internal enum class DeepSeekProtocol(val wireName: String) {
    CHAT_COMPLETIONS("chat_completions"),
    RESPONSES("responses"),
    MESSAGES("messages"),
}

internal class DeepSeekConversation(
    val protocol: DeepSeekProtocol,
    private val providerId: String,
    credentials: String,
    private val params: TextGenerationParams,
) {
    private val credential = credentials.primaryDeepSeekApiKey()
    val primaryKey: String? = credential.takeIf { params.model.deepSeekToolsEnabled }
    private val replayCredentialKnown = primaryKey != null || credentials.trim() == credential
    private val credentialFingerprint by lazy {
        MessageDigest.getInstance("SHA-256").digest(credential.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    val nativeTools: List<JsonObject> = if (params.model.deepSeekToolsEnabled && params.model.deepSeek.webTools) {
        listOf(DEEPSEEK_WEB_SEARCH, DEEPSEEK_READ_URL).map { name ->
            buildJsonObject {
                put("type", name)
                if (protocol == DeepSeekProtocol.MESSAGES) put("name", name)
            }
        }
    } else {
        emptyList()
    }

    val safetyCheck = params.model.deepSeekToolsEnabled && params.model.deepSeek.safetyCheck
    private val requestTitle = params.model.deepSeekToolsEnabled &&
        params.deepSeekRequestTitle && params.model.deepSeek.conversationTitle
    val replacesGenericServerTools = nativeTools.isNotEmpty() || safetyCheck || requestTitle

    fun JsonObjectBuilder.putExtensions() {
        if (safetyCheck) put("safety_check", buildJsonObject { put("level", "high") })
        if (requestTitle) put("title", buildJsonObject { put("enabled", true) })
    }

    fun shouldBuffer(body: JsonObject): Boolean = params.model.deepSeekToolsEnabled ||
        (body["safety_check"] as? JsonObject)?.string("level") == "high" ||
        (body["title"] as? JsonObject)?.get("enabled") == JsonPrimitive(true) ||
        body.objectList("tools").any { it.string("type") in setOf(DEEPSEEK_WEB_SEARCH, DEEPSEEK_READ_URL) }

    fun enforceExtensions(body: JsonObject, defaultBody: JsonObject): JsonObject = buildJsonObject {
        body.forEach { (key, value) -> put(key, value) }
        putExtensions()

        // Custom request parameters may augment client functions. Native declarations are emitted once.
        if (replacesGenericServerTools) {
            val existing = body["tools"] as? JsonArray
            val clients = ((defaultBody["tools"] as? JsonArray).orEmpty() + existing.orEmpty())
                .filterIsInstance<JsonObject>().filter { tool ->
                val type = tool.string("type")
                type == "function" || protocol == DeepSeekProtocol.MESSAGES &&
                    type == null && tool["input_schema"] is JsonObject
            }.associateBy { tool ->
                tool.string("name") ?: (tool["function"] as? JsonObject)?.string("name") ?: tool.toString()
            }.values
            if (existing != null || nativeTools.isNotEmpty()) put("tools", JsonArray(clients + nativeTools))
        }
    }.let { updated ->
        if (!replacesGenericServerTools) return@let updated
        val result = updated.toMutableMap()
        result.remove("web_search_options")
        val choice = result["tool_choice"]
        if (choice != null && !choice.isAutoOrNone()) result.remove("tool_choice")
        JsonObject(result)
    }

    fun reminder(message: UIMessage): JsonObject? {
        if (protocol == DeepSeekProtocol.RESPONSES || !params.model.deepSeekToolsEnabled ||
            !params.model.deepSeek.userMetadata
        ) return null
        val text = params.deepSeekReminders[message.id.toString()]?.takeIf { it.isNotBlank() } ?: return null
        return buildJsonObject {
            put("role", "latest_reminder")
            put("content", text)
        }
    }

    fun envelope(payload: JsonElement): JsonObject = buildJsonObject {
        put("protocol", protocol.wireName)
        put("provider_id", providerId)
        put("model", params.model.modelId)
        put("credential_fingerprint", credentialFingerprint)
        put("payload", payload)
    }

    fun payloads(message: UIMessage): List<JsonElement> {
        if (!replayCredentialKnown) return emptyList()
        val segments = message.deepSeekReplay.mapNotNull { replay ->
            replay["payload"].takeIf {
                replay.string("protocol") == protocol.wireName &&
                    replay.string("provider_id") == providerId &&
                    replay.string("model") == params.model.modelId &&
                    replay.string("credential_fingerprint") == credentialFingerprint
            }
        }
        // Replay all segments in order or rebuild from visible history. Using only some segments
        // would silently drop answers from another provider or another credential.
        return segments.takeIf { it.size == message.deepSeekReplay.size }.orEmpty()
    }

    fun bind(message: UIMessage, payload: JsonElement, title: String?): UIMessage = message.copy(
        deepSeekReplay = if (replayCredentialKnown) listOf(envelope(payload)) else emptyList(),
        deepSeekTitle = title?.takeIf { it.isNotBlank() },
    )
}

internal fun ProviderSetting.deepSeekConversation(
    protocol: DeepSeekProtocol,
    params: TextGenerationParams,
): DeepSeekConversation? {
    if (!supportsDeepSeekFlash(params.model)) return null
    val key = when (this) {
        is ProviderSetting.OpenAI -> apiKey
        is ProviderSetting.Claude -> apiKey
        else -> return null
    }
    return DeepSeekConversation(protocol, id.toString(), key, params)
}

internal fun UIMessage.withoutDeepSeekReplayReasoning(): UIMessage =
    if (deepSeekReplay.isEmpty()) this else copy(parts = parts.filterNot { it is UIMessagePart.Reasoning })

internal fun ReasoningLevel.deepSeekEffort(): String = when (this) {
    ReasoningLevel.OFF -> "none"
    ReasoningLevel.LOW -> "low"
    ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
    else -> "high"
}

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.hasError(): Boolean = get("error")?.let { it != JsonNull } == true

internal fun JsonObject.objectList(key: String): List<JsonObject> =
    (get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

internal fun JsonObject.urlCitation(): UIMessageAnnotation.UrlCitation? {
    val url = string("url")?.takeIf { it.isNotBlank() } ?: return null
    return UIMessageAnnotation.UrlCitation(title = string("title")?.ifBlank { url } ?: url, url = url)
}

internal fun JsonArrayBuilder.addDeepSeekReplay(
    payloads: List<JsonElement>,
    message: UIMessage,
    calls: (JsonElement) -> List<String>,
    appendPayload: JsonArrayBuilder.(JsonElement) -> Unit,
    appendResults: JsonArrayBuilder.(List<UIMessagePart.Tool>) -> Unit,
): Boolean {
    if (payloads.isEmpty()) return false
    val executedTools = message.getTools().filter { it.isExecuted }.associateBy { it.toolCallId }
    val seen = mutableSetOf<String>()
    val callIds = payloads.map(calls)
    // A mixed or edited assistant message can contain calls from another provider. Fall back to the
    // ordinary serializer rather than dropping their outputs or sending a result with no call.
    if (executedTools.keys.any { id -> callIds.none { id in it } }) return false
    for ((index, payload) in payloads.withIndex()) {
        appendPayload(payload)
        val results = callIds[index].mapNotNull { id -> executedTools[id]?.takeIf { seen.add(id) } }
        if (results.isNotEmpty()) appendResults(results)
    }
    return true
}

private fun JsonElement.isAutoOrNone(): Boolean = when (this) {
    is JsonObject -> string("type") in setOf("auto", "none")
    is JsonPrimitive -> contentOrNull in setOf("auto", "none")
    else -> false
}
